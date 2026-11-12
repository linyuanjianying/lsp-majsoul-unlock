//! Synchronous C ABI around the upstream MajsoulMax Rust Modder.

mod proto;
mod settings;
mod capture;

use std::{
    collections::HashMap,
    ffi::CStr,
    os::raw::{c_char, c_int},
    panic::{AssertUnwindSafe, catch_unwind},
    path::PathBuf,
    ptr, slice,
    sync::{Mutex, OnceLock},
};

use anyhow::{Context, Result, bail};
use bytes::Bytes;
use settings::{MaxData, ModSettings, SettingsPatch};
use tokio::{runtime::Runtime, sync::RwLock};

// Keep the upstream source pinned while adding the two accessors required by
// the in-game page in the same Rust module. This preserves access to its
// private RwLock without rebuilding Modder (which would lose its account,
// contract and request state).
mod upstream_modder {
    include!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../external/MajsoulMax-rs/src/modder.rs"
    ));

    impl Modder {
        pub async fn settings_snapshot(&self) -> crate::settings::ModSettings {
            self.mod_settings.read().await.clone()
        }

        pub async fn replace_settings(&self, settings: crate::settings::ModSettings) {
            *self.mod_settings.write().await = settings;
        }
    }
}

use upstream_modder::Modder;

const PASS: c_int = 0;
const DROP: c_int = 1;
const REPLACE: c_int = 2;

#[repr(C)]
pub struct OwnedBuffer {
    pub data: *mut u8,
    pub len: usize,
}

impl Default for OwnedBuffer {
    fn default() -> Self {
        Self {
            data: ptr::null_mut(),
            len: 0,
        }
    }
}

#[repr(C)]
#[derive(Default)]
pub struct ProcessResult {
    pub action: c_int,
    pub message: OwnedBuffer,
    pub injection: OwnedBuffer,
}

struct Core {
    runtime: Runtime,
    modder: Modder,
    requests: HashMap<(usize, u16), String>,
    active_enabled: bool,
}

static CORE: OnceLock<Mutex<Core>> = OnceLock::new();

/// What the in-game display needs to know about the opponent-naming choice.
///
/// Kept outside [`CORE`] on purpose: the Lua side reads it from Unity's main
/// thread, while `CORE` is held for the whole duration of a packet being
/// modified on a network thread. A brief lock here can therefore never wait for
/// traffic, at the cost of one extra mutex that only ever holds a short string
/// and at most three names.
#[derive(Default)]
struct NamingView {
    /// `<mode>\n<show_server:0|1>\n<slot0>\n<slot1>\n<slot2>`; empty until the
    /// settings are loaded. See `ModSettings::naming_wire`.
    state: String,
    /// Server nicknames the current `authGame` response replaced, keyed by
    /// account id and stored *without* the region tag (the display layer
    /// re-applies it from the live `show_server` flag). Replaced wholesale per
    /// `authGame` and cleared per session, so it can only describe the table
    /// the client is showing.
    originals: Vec<(u32, String)>,
}

static NAMING_VIEW: Mutex<NamingView> = Mutex::new(NamingView {
    state: String::new(),
    originals: Vec::new(),
});

/// A poisoned lock only means an earlier panic while a short string was held;
/// the data is still consistent enough to serve, and refusing to answer would
/// wedge the display for the rest of the session.
fn naming_view() -> std::sync::MutexGuard<'static, NamingView> {
    NAMING_VIEW
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Publishes the current naming choice for the in-game Lua layer.
fn publish_naming_state(state: String) {
    naming_view().state = state;
}

/// Publishes the server names the `authGame` response just replaced.
///
/// Called from the upstream `Modder` (see its `apply_opponent_naming`) for every
/// `authGame`, including the ones that rewrite nothing: that is what clears the
/// cache when the next game starts in `normal` mode.
pub(crate) fn publish_naming_originals(originals: Vec<(u32, String)>) {
    naming_view().originals = originals;
}

/// Server nickname replaced in the current table, if any.
pub(crate) fn naming_original_of(account_id: u32) -> Option<String> {
    naming_view()
        .originals
        .iter()
        .find(|(id, _)| *id == account_id)
        .map(|(_, name)| name.clone())
}

#[cfg(target_os = "android")]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

#[cfg(target_os = "android")]
fn log_message(priority: c_int, message: &str) {
    use std::ffi::CString;
    let Ok(tag) = CString::new("MajsoulHook") else {
        return;
    };
    let Ok(text) = CString::new(message.replace('\0', "")) else {
        return;
    };
    unsafe {
        __android_log_write(priority, tag.as_ptr(), text.as_ptr());
    }
}

#[cfg(not(target_os = "android"))]
fn log_message(_priority: c_int, message: &str) {
    eprintln!("{message}");
}

fn into_owned(bytes: Bytes) -> OwnedBuffer {
    let mut boxed = bytes.to_vec().into_boxed_slice();
    let result = OwnedBuffer {
        data: boxed.as_mut_ptr(),
        len: boxed.len(),
    };
    std::mem::forget(boxed);
    result
}

fn read_varint(data: &[u8], offset: &mut usize) -> Option<u64> {
    let mut value = 0_u64;
    for shift in (0..70).step_by(7) {
        let byte = *data.get(*offset)?;
        *offset += 1;
        if shift == 63 && byte > 1 {
            return None;
        }
        value |= u64::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Some(value);
        }
    }
    None
}

fn skip_field(wire_type: u8, data: &[u8], offset: &mut usize) -> Option<()> {
    match wire_type {
        0 => {
            read_varint(data, offset)?;
        }
        1 => *offset = offset.checked_add(8)?,
        2 => {
            let len = usize::try_from(read_varint(data, offset)?).ok()?;
            *offset = offset.checked_add(len)?;
        }
        5 => *offset = offset.checked_add(4)?,
        _ => return None,
    }
    (*offset <= data.len()).then_some(())
}

fn envelope_method(data: &[u8]) -> Option<String> {
    let mut offset = 0;
    while offset < data.len() {
        let key = read_varint(data, &mut offset)?;
        let field = key >> 3;
        let wire = (key & 7) as u8;
        if field == 1 && wire == 2 {
            let len = usize::try_from(read_varint(data, &mut offset)?).ok()?;
            let end = offset.checked_add(len)?;
            let method = std::str::from_utf8(data.get(offset..end)?).ok()?;
            if method.starts_with(".lq.")
                && method
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_'))
            {
                return Some(method.to_owned());
            }
            return None;
        }
        skip_field(wire, data, &mut offset)?;
    }
    None
}

impl Core {
    fn new(config_dir: PathBuf) -> Result<Self> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .thread_name("majsoul-modder")
            .enable_all()
            .build()
            .context("创建 Modder runtime 失败")?;
        let settings = ModSettings::load(&config_dir)?;
        let active_enabled = settings.enabled;
        publish_naming_state(settings.naming_wire());
        let max_data = MaxData::load(&config_dir)?;
        let modder = runtime.block_on(Modder::new(RwLock::new(settings), max_data))?;
        Ok(Self {
            runtime,
            modder,
            requests: HashMap::new(),
            active_enabled,
        })
    }

    fn process(&mut self, connection: usize, from_client: bool, input: &[u8]) -> ProcessResult {
        if !self.active_enabled {
            return ProcessResult::default();
        }
        let Some(&kind) = input.first() else {
            return ProcessResult::default();
        };
        if (kind == 2) != from_client && (kind == 2 || kind == 3) {
            return ProcessResult::default();
        }

        let method = match kind {
            1 => envelope_method(&input[1..]).unwrap_or_default(),
            2 if input.len() >= 3 => {
                let id = u16::from_le_bytes([input[1], input[2]]);
                let method = envelope_method(&input[3..]).unwrap_or_default();
                if !method.is_empty() {
                    if self.requests.len() >= 4096 {
                        self.requests.clear();
                        log_message(5, "Request map limit reached; stale mappings cleared");
                    }
                    self.requests.insert((connection, id), method.clone());
                }
                method
            }
            3 if input.len() >= 3 => {
                let id = u16::from_le_bytes([input[1], input[2]]);
                self.requests.remove(&(connection, id)).unwrap_or_default()
            }
            _ => return ProcessResult::default(),
        };

        // The Android module exposes its information in the manager UI. Keep
        // announcement traffic byte-for-byte intact instead of adding the
        // upstream Modder's synthetic startup notice. Resolve/remove the RPC
        // mapping first so these responses cannot leave stale request IDs.
        if matches!(
            method.as_str(),
            ".lq.Lobby.fetchAnnouncement"
                | ".lq.Lobby.readAnnouncement"
                | ".lq.NotifyAnnouncementUpdate"
        ) {
            if kind == 3 && method == ".lq.Lobby.fetchAnnouncement" {
                log_message(4, "Official announcements preserved; module notice disabled");
            }
            return ProcessResult::default();
        }

        let outcome = self.runtime.block_on(self.modder.modify(
            Bytes::copy_from_slice(input),
            from_client,
            method,
        ));
        let mut result = ProcessResult::default();
        match outcome.msg {
            None => result.action = DROP,
            Some(message) if message.as_ref() == input => result.action = PASS,
            Some(message) => {
                result.action = REPLACE;
                result.message = into_owned(message);
            }
        }
        if let Some(injection) = outcome.inject_msg {
            result.injection = into_owned(injection);
        }
        result
    }

    fn settings_json(&mut self) -> Result<Vec<u8>> {
        let settings = self.runtime.block_on(self.modder.settings_snapshot());
        let settings_enabled = settings.enabled;
        let value = serde_json::json!({
            "settings": settings,
            "activeEnabled": self.active_enabled,
            "restartRequired": settings_enabled != self.active_enabled,
        });
        serde_json::to_vec(&value).context("序列化设置快照失败")
    }

    fn update_settings(&mut self, patch: SettingsPatch) -> Result<Vec<u8>> {
        if patch.is_empty() {
            bail!("设置补丁不能为空");
        }
        let mut settings = self.runtime.block_on(self.modder.settings_snapshot());
        settings.apply_patch(patch)?;
        settings.write_atomic()?;
        let restart_required = settings.enabled != self.active_enabled;
        self.runtime
            .block_on(self.modder.replace_settings(settings.clone()));
        // The in-game layer redraws the current table when this value changes,
        // so a tap on either surface lands without waiting for the next game.
        publish_naming_state(settings.naming_wire());
        let value = serde_json::json!({
            "settings": settings,
            "activeEnabled": self.active_enabled,
            "restartRequired": restart_required,
        });
        serde_json::to_vec(&value).context("序列化更新后的设置失败")
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn majmax_modder_init(config_dir: *const c_char) -> c_int {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<()> {
        if CORE.get().is_some() {
            return Ok(());
        }
        if config_dir.is_null() {
            bail!("配置目录为空");
        }
        let path = unsafe { CStr::from_ptr(config_dir) }
            .to_str()
            .context("配置目录不是 UTF-8")?;
        CORE.set(Mutex::new(Core::new(PathBuf::from(path))?))
            .map_err(|_| anyhow::anyhow!("Modder 已初始化"))?;
        Ok(())
    }));
    match result {
        Ok(Ok(())) => {
            log_message(4, "Rust Modder initialized");
            0
        }
        Ok(Err(error)) => {
            log_message(6, &format!("Rust Modder initialization failed: {error:#}"));
            -1
        }
        Err(_) => {
            log_message(6, "Rust Modder initialization panicked");
            -2
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn majmax_capture_configure() {
    capture::configure();
}

#[unsafe(no_mangle)]
pub extern "C" fn majmax_capture_is_current(generation: u64, connection: u64, sequence: u64) -> bool {
    capture::is_current(generation, connection, sequence)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn majmax_capture_set_endpoint(
    port: u32,
    token: *const u8,
    token_len: usize,
) {
    if token_len != 32 || token.is_null() || port > u16::MAX as u32 {
        capture::set_endpoint(0, &[]);
        return;
    }
    let token = unsafe { std::slice::from_raw_parts(token, token_len) };
    capture::set_endpoint(port as u16, token);
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn majmax_modder_process(
    connection: usize,
    from_client: bool,
    data: *const u8,
    len: usize,
    output: *mut ProcessResult,
) -> c_int {
    if output.is_null() || (data.is_null() && len != 0) || len > 16 * 1024 * 1024 {
        return -1;
    }
    unsafe { ptr::write(output, ProcessResult::default()) };
    let Some(core) = CORE.get() else {
        return 1;
    };
    let result = catch_unwind(AssertUnwindSafe(|| {
        let input = if len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(data, len) }
        };
        // Copy before modification and before the unlock feature's enabled
        // check. Locally injected cosmetic notifications never reach here.
        capture::publish(connection, u8::from(from_client), input);
        core.lock()
            .map_err(|_| ())
            .map(|mut guard| guard.process(connection, from_client, input))
    }));
    match result {
        Ok(Ok(result)) => {
            unsafe { ptr::write(output, result) };
            0
        }
        Ok(Err(())) => {
            log_message(
                6,
                "Rust Modder state lock is poisoned; passing message through",
            );
            -2
        }
        Err(_) => {
            log_message(
                6,
                "Rust Modder processing panicked; passing message through",
            );
            -3
        }
    }
}

/// Return a UTF-8 JSON snapshot. The caller owns the returned buffer and must
/// release it with `majmax_modder_free`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn majmax_modder_get_settings(
    output: *mut OwnedBuffer,
) -> c_int {
    if output.is_null() {
        return -1;
    }
    unsafe { ptr::write(output, OwnedBuffer::default()) };
    let Some(core) = CORE.get() else {
        return 1;
    };
    let result = catch_unwind(AssertUnwindSafe(|| {
        core.lock()
            .map_err(|_| anyhow::anyhow!("Rust Modder state lock is poisoned"))
            .and_then(|mut guard| guard.settings_json())
    }));
    match result {
        Ok(Ok(bytes)) => {
            let owned = into_owned(Bytes::from(bytes));
            unsafe { ptr::write(output, owned) };
            0
        }
        Ok(Err(error)) => {
            log_message(6, &format!("读取 Mod 设置失败: {error:#}"));
            -2
        }
        Err(_) => {
            log_message(6, "读取 Mod 设置时发生 panic");
            -3
        }
    }
}

/// Apply a camelCase JSON patch and return the resulting snapshot using the
/// same ownership convention as `majmax_modder_get_settings`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn majmax_modder_update_settings(
    patch: *const u8,
    patch_len: usize,
    output: *mut OwnedBuffer,
) -> c_int {
    if output.is_null() || (patch.is_null() && patch_len != 0) || patch_len > 64 * 1024 {
        return -1;
    }
    unsafe { ptr::write(output, OwnedBuffer::default()) };
    let Some(core) = CORE.get() else {
        return 1;
    };
    let input = if patch_len == 0 {
        &[]
    } else {
        unsafe { slice::from_raw_parts(patch, patch_len) }
    };
    let result = catch_unwind(AssertUnwindSafe(|| {
        let parsed: SettingsPatch = serde_json::from_slice(input)
            .context("设置补丁不是有效的 JSON 或包含未知字段")?;
        core.lock()
            .map_err(|_| anyhow::anyhow!("Rust Modder state lock is poisoned"))
            .and_then(|mut guard| guard.update_settings(parsed))
    }));
    match result {
        Ok(Ok(bytes)) => {
            let owned = into_owned(Bytes::from(bytes));
            unsafe { ptr::write(output, owned) };
            0
        }
        Ok(Err(error)) => {
            log_message(6, &format!("更新 Mod 设置失败: {error:#}"));
            -2
        }
        Err(_) => {
            log_message(6, "更新 Mod 设置时发生 panic");
            -3
        }
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn majmax_modder_forget_connection(connection: usize) {
    capture::publish(connection, 2, &[]);
    // A closed connection ends the table it belonged to: the names it replaced
    // must not be restorable from a later, unrelated session. The next
    // `authGame` republishes them.
    naming_view().originals.clear();
    let Some(core) = CORE.get() else {
        return;
    };
    if let Ok(mut guard) = core.lock() {
        guard
            .requests
            .retain(|(socket, _), _| *socket != connection);
    }
}

/// Return the compact naming state for the in-game Lua layer.
///
/// The format is `<mode>\n<show_server:0|1>\n<slot0>\n<slot1>\n<slot2>`; see
/// `ModSettings::naming_wire`. The `show_server` line is what makes the region
/// tag an independent switch: the Lua layer prefixes a name (server or label)
/// exactly when that flag is `1`, and the whole string doubles as the redraw
/// token, so toggling the switch alone repaints the table.
///
/// Status: 0 = the buffer holds the state (possibly empty before the settings
/// are loaded), -1 = the caller passed no output slot. The caller owns the
/// buffer and must release it with `majmax_modder_free`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn majmax_naming_state(output: *mut OwnedBuffer) -> c_int {
    if output.is_null() {
        return -1;
    }
    let state = naming_view().state.clone();
    unsafe { ptr::write(output, into_owned(Bytes::from(state.into_bytes()))) };
    0
}

/// Return the region tag the game shows in front of a name of `account_id`
/// (`[CN]`, `[JP]`, `[EN]`, `[??]`; the Chinese one is the game's own bytes and
/// contains a U+FEFF), or an empty string for account id 0 (a robot, no region).
///
/// This is the C-side half of the same `zone_tag` the protocol rewrite uses, so
/// the in-game layer never has to guess a prefix from a label's text: it asks
/// for the tag of the account and prepends it, or does not, depending on the
/// `show_server` flag of [`majmax_naming_state`].
///
/// Status: 0 = answered, -1 = invalid call. The caller owns the buffer and must
/// release it with `majmax_modder_free`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn majmax_naming_prefix(account_id: u32, output: *mut OwnedBuffer) -> c_int {
    if output.is_null() {
        return -1;
    }
    let prefix = if account_id == 0 {
        ""
    } else {
        upstream_modder::zone_tag(account_id)
    };
    unsafe { ptr::write(output, into_owned(Bytes::from(prefix.as_bytes().to_vec()))) };
    0
}

/// Return the server nickname the current `authGame` response replaced for
/// `account_id`, as a UTF-8 string, *without* the region tag.
///
/// The tag is not part of the cached value on purpose: the display layer
/// composes `tag + name` from the live `show_server` state (see
/// [`majmax_naming_prefix`]), which is what keeps a switch-off from leaving a
/// stale prefix behind and a repeated switch-on from doubling it.
///
/// Status: 0 = answered, where an empty buffer means "nothing to restore"
/// (the seat was not renamed, or the local table changed), -1 = invalid call.
/// Never logged: the value is a player name and belongs to the display only.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn majmax_naming_original(
    account_id: u32,
    output: *mut OwnedBuffer,
) -> c_int {
    if output.is_null() {
        return -1;
    }
    let original = naming_original_of(account_id).unwrap_or_default();
    unsafe { ptr::write(output, into_owned(Bytes::from(original.into_bytes()))) };
    0
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn majmax_modder_free(data: *mut u8, len: usize) {
    if !data.is_null() {
        drop(unsafe { Box::from_raw(ptr::slice_from_raw_parts_mut(data, len)) });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::{base::BaseMessage, lq};
    use crate::settings::NamingMode;
    use prost::Message;

    #[test]
    fn reads_method_after_an_unknown_field() {
        let mut encoded = bytes::BytesMut::from(&b"\x10\x01\x0a\x13.lq.Lobby.fetchInfo"[..]);
        assert_eq!(
            envelope_method(&encoded),
            Some(".lq.Lobby.fetchInfo".to_owned())
        );
        encoded[4] = b'!';
        assert_eq!(envelope_method(&encoded), None);
    }

    #[test]
    fn rejects_truncated_varints() {
        assert_eq!(envelope_method(&[0x0a, 0x80]), None);
    }

    #[test]
    fn announcement_traffic_preserves_official_messages_and_unknown_fields() {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let modder = runtime
            .block_on(Modder::new(
                RwLock::new(ModSettings::default()),
                MaxData::default(),
            ))
            .unwrap();
        let mut core = Core {
            runtime,
            modder,
            requests: HashMap::new(),
            active_enabled: true,
        };
        let frame = |kind, method: &str, payload: Vec<u8>| {
            let mut wire = vec![kind];
            if kind != 1 {
                wire.extend([0x34, 0x12]);
            }
            wire.extend(
                BaseMessage {
                    method_name: method.to_owned(),
                    data: payload,
                }
                .encode_to_vec(),
            );
            // A future envelope field must also survive without re-encoding.
            wire.extend([0xa0, 0x06, 0x01]);
            wire
        };
        let assert_pass = |result: ProcessResult| {
            assert_eq!(result.action, PASS);
            assert!(result.message.data.is_null());
            assert!(result.injection.data.is_null());
        };
        for method in [".lq.Lobby.fetchAnnouncement", ".lq.Lobby.readAnnouncement"] {
            let request = if method.ends_with("readAnnouncement") {
                // Even the old synthetic ID must no longer be converted into
                // an unrelated loginBeat request by the Android adapter.
                lq::ReqReadAnnouncement {
                    announcement_id: 1145141919,
                    ..Default::default()
                }
                .encode_to_vec()
            } else {
                Vec::new()
            };
            assert_pass(core.process(7, true, &frame(2, method, request)));
            assert_eq!(
                core.requests.get(&(7, 0x1234)).map(String::as_str),
                Some(method),
            );
            let mut response = if method.ends_with("readAnnouncement") {
                lq::ResCommon::default().encode_to_vec()
            } else {
                lq::ResAnnouncement {
                    announcements: vec![lq::Announcement {
                        id: 42,
                        title: "Official game update".to_owned(),
                        content: "Keep this announcement".to_owned(),
                        ..Default::default()
                    }],
                    ..Default::default()
                }
                .encode_to_vec()
            };
            response.extend([0xa0, 0x06, 0x01]);
            assert_pass(core.process(7, false, &frame(3, "", response)));
            assert!(core.requests.is_empty());
        }
        assert_pass(core.process(
            7,
            false,
            &frame(1, ".lq.NotifyAnnouncementUpdate", vec![0xa0, 0x06, 0x01]),
        ));
    }

    #[test]
    fn skin_changes_are_saved_immediately_and_restored_after_restart() {
        struct TestDirectory(PathBuf);
        impl Drop for TestDirectory {
            fn drop(&mut self) {
                let _ = std::fs::remove_dir_all(&self.0);
            }
        }
        let directory = TestDirectory(std::env::temp_dir().join(format!(
            "majmax-skin-persistence-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        )));
        std::fs::create_dir(&directory.0).unwrap();
        let mut initial = ModSettings::default();
        initial.main_char = 20000101;
        initial.nickname = "persisted nickname".to_owned();
        initial.title = 700001;
        initial.loading_bg = vec![260901];
        initial.char_skin.insert(20000101, 40010101);
        initial.char_skin.insert(20000126, 40012601);
        std::fs::write(
            directory.0.join("settings.mod.json"),
            serde_json::to_vec(&initial).unwrap(),
        )
        .unwrap();
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let catalog = MaxData {
            character: vec![20000101, 20000126],
            skin: vec![40010101, 40010102, 40012601, 40012602],
            ..Default::default()
        };
        // Check that this host can persist the fixture before testing the RPC.
        ModSettings::load(&directory.0)
            .unwrap()
            .write_atomic()
            .unwrap();
        let modder = runtime
            .block_on(Modder::new(
                RwLock::new(ModSettings::load(&directory.0).unwrap()),
                catalog.clone(),
            ))
            .unwrap();
        // Exercise the current character, another character and a second change
        // without triggering any other request that might incidentally save.
        for (character_id, skin) in [
            (20000101, 40010102),
            (20000126, 40012602),
            (20000101, 40010101),
        ] {
            let mut wire = vec![2, 0x34, 0x12];
            wire.extend(
                BaseMessage {
                    method_name: ".lq.Lobby.changeCharacterSkin".to_owned(),
                    data: lq::ReqChangeCharacterSkin { character_id, skin }.encode_to_vec(),
                }
                .encode_to_vec(),
            );
            let output = runtime.block_on(modder.modify(Bytes::from(wire), true, ""));
            assert!(
                output.inject_msg.is_some(),
                "skin notification must still be delivered"
            );
            let saved = ModSettings::load(&directory.0).unwrap();
            assert_eq!(saved.char_skin.get(&character_id), Some(&skin));
            assert_eq!(saved.main_char, initial.main_char);
            assert_eq!(saved.nickname, initial.nickname);
            assert_eq!(saved.title, initial.title);
            assert_eq!(saved.loading_bg, initial.loading_bg);
        }
        drop(modder);
        let restarted = runtime
            .block_on(Modder::new(
                RwLock::new(ModSettings::load(&directory.0).unwrap()),
                catalog,
            ))
            .unwrap();
        let mut response = vec![3, 0x35, 0x12];
        response.extend(
            BaseMessage {
                method_name: String::new(),
                // Model the server returning its original/default skins again.
                data: lq::ResCharacterInfo {
                    main_character_id: 20000101,
                    characters: vec![lq::Character {
                        charid: 20000126,
                        skin: 40012601,
                        ..Default::default()
                    }],
                    ..Default::default()
                }
                .encode_to_vec(),
            }
            .encode_to_vec(),
        );
        let restored = runtime.block_on(restarted.modify(
            Bytes::from(response),
            false,
            ".lq.Lobby.fetchCharacterInfo",
        ));
        let response = restored.msg.unwrap();
        let envelope = BaseMessage::decode(&response[3..]).unwrap();
        let characters = lq::ResCharacterInfo::decode(envelope.data.as_slice()).unwrap();
        let skins: HashMap<_, _> = characters
            .characters
            .iter()
            .map(|c| (c.charid, c.skin))
            .collect();
        assert_eq!(skins.get(&20000101), Some(&40010101));
        assert_eq!(skins.get(&20000126), Some(&40012602));
    }

    #[test]
    fn skin_change_produces_fake_request_and_local_notification() {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let modder = runtime
            .block_on(Modder::new(
                RwLock::new(ModSettings::default()),
                MaxData::default(),
            ))
            .unwrap();
        let request = lq::ReqChangeCharacterSkin {
            character_id: 200001,
            skin: 400102,
        };
        let envelope = BaseMessage {
            method_name: ".lq.Lobby.changeCharacterSkin".to_owned(),
            data: request.encode_to_vec(),
        };
        let mut wire = vec![2, 0x34, 0x12];
        wire.extend(envelope.encode_to_vec());

        let output = runtime.block_on(modder.modify(Bytes::from(wire), true, ""));
        let replacement = output.msg.expect("the request should be replaced");
        assert_eq!(&replacement[..3], &[2, 0x34, 0x12]);
        assert_eq!(
            BaseMessage::decode(&replacement[3..]).unwrap().method_name,
            ".lq.Lobby.loginBeat"
        );

        let injection = output
            .inject_msg
            .expect("a notification should be injected");
        assert_eq!(injection[0], 1);
        let notification_envelope = BaseMessage::decode(&injection[1..]).unwrap();
        assert_eq!(notification_envelope.method_name, ".lq.NotifyAccountUpdate");
        let notification =
            lq::NotifyAccountUpdate::decode(notification_envelope.data.as_slice()).unwrap();
        let character = &notification.update.unwrap().character.unwrap().characters[0];
        assert_eq!((character.charid, character.skin), (200001, 400102));
    }

    // Opponent naming tests. The fixtures mimic real traffic: a `login`
    // response teaches the modder the local account id, then an `authGame`
    // response carries the player views and the authoritative `seat_list`.
    const CN_PREFIX: &str = "[C\u{feff}N]";
    const SEAT_IDS: [u32; 4] = [1101, 2202, 3303, 4404];

    /// Accounts whose top bits select another region. `SEAT_IDS` are all
    /// Chinese (zone code 0..=6), these are the two other tags a table can show.
    const JP_ACCOUNT: u32 = (7 << 23) | 42;
    const EN_ACCOUNT: u32 = (13 << 23) | 7;

    /// The display cache the Lua layer reads is process-wide (it must not touch
    /// `CORE`, which is held while a packet is being modified), so every test
    /// that produces or inspects it has to run alone.
    static NAMING_TEST_LOCK: Mutex<()> = Mutex::new(());

    fn naming_guard() -> std::sync::MutexGuard<'static, ()> {
        NAMING_TEST_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Takes ownership of an `OwnedBuffer` the C ABI just returned.
    fn take_owned(buffer: OwnedBuffer) -> String {
        if buffer.data.is_null() {
            return String::new();
        }
        let bytes = unsafe { slice::from_raw_parts(buffer.data, buffer.len) }.to_vec();
        unsafe { majmax_modder_free(buffer.data, buffer.len) };
        String::from_utf8(bytes).unwrap()
    }

    /// The naming state the in-game Lua layer polls.
    fn naming_state() -> String {
        let mut buffer = OwnedBuffer::default();
        assert_eq!(unsafe { majmax_naming_state(&mut buffer) }, 0);
        take_owned(buffer)
    }

    /// The server nickname restored for one account, or an empty string.
    fn naming_original(account_id: u32) -> String {
        let mut buffer = OwnedBuffer::default();
        assert_eq!(unsafe { majmax_naming_original(account_id, &mut buffer) }, 0);
        take_owned(buffer)
    }

    /// The region tag the in-game layer prepends for one account.
    fn naming_prefix(account_id: u32) -> String {
        let mut buffer = OwnedBuffer::default();
        assert_eq!(unsafe { majmax_naming_prefix(account_id, &mut buffer) }, 0);
        take_owned(buffer)
    }

    fn core_with(settings: ModSettings) -> Core {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let modder = runtime
            .block_on(Modder::new(RwLock::new(settings), MaxData::default()))
            .unwrap();
        Core {
            runtime,
            modder,
            requests: HashMap::new(),
            active_enabled: true,
        }
    }

    fn frame(kind: u8, method: &str, data: Vec<u8>) -> Vec<u8> {
        let mut wire = vec![kind];
        if kind != 1 {
            wire.extend([0x34, 0x12]);
        }
        wire.extend(
            BaseMessage {
                method_name: method.to_owned(),
                data,
            }
            .encode_to_vec(),
        );
        wire
    }

    /// Send a request and its response on one connection, exactly like
    /// `majmax_modder_process` does. Returns the replacement body, or `None`
    /// when the modder decided the response needs no rewrite.
    fn exchange(
        core: &mut Core,
        method: &str,
        request: Vec<u8>,
        response: Vec<u8>,
    ) -> Option<Vec<u8>> {
        assert_eq!(
            core.process(1, true, &frame(2, method, request)).action,
            PASS
        );
        let result = core.process(1, false, &frame(3, "", response));
        match result.action {
            REPLACE => {
                assert!(!result.message.data.is_null());
                let bytes = unsafe {
                    slice::from_raw_parts(result.message.data, result.message.len)
                }
                .to_vec();
                unsafe { majmax_modder_free(result.message.data, result.message.len) };
                Some(bytes)
            }
            PASS => {
                assert!(result.message.data.is_null());
                None
            }
            other => panic!("unexpected action {other}"),
        }
    }

    fn login(core: &mut Core, account_id: u32) {
        let response = lq::ResLogin {
            account_id,
            ..Default::default()
        }
        .encode_to_vec();
        exchange(core, ".lq.Lobby.login", Vec::new(), response);
    }

    fn player(account_id: u32, nickname: &str) -> lq::PlayerGameView {
        lq::PlayerGameView {
            account_id,
            nickname: nickname.to_owned(),
            ..Default::default()
        }
    }

    /// Real payloads always carry a character view for the local player, and
    /// the upstream self-cosmetics path only rewrites the local nickname when
    /// that view is present.
    fn self_player(account_id: u32, nickname: &str) -> lq::PlayerGameView {
        lq::PlayerGameView {
            character: Some(lq::Character::default()),
            ..player(account_id, nickname)
        }
    }

    /// Seat list `[1101, 2202, 3303, 4404]`: the local account 2202 sits on
    /// seat 1, so the visual order is left = seat 0, top = seat 3, right =
    /// seat 2.
    fn auth_game() -> lq::ResAuthGame {
        lq::ResAuthGame {
            players: vec![
                player(1101, "东家"),
                self_player(2202, "我"),
                player(3303, "西家"),
                player(4404, "北家"),
            ],
            seat_list: SEAT_IDS.to_vec(),
            is_game_start: true,
            ..Default::default()
        }
    }

    /// A four-player table whose opponents are Japanese accounts, so the zone
    /// tag of a rewritten name is visibly not the Chinese one.
    fn auth_game_jp() -> lq::ResAuthGame {
        lq::ResAuthGame {
            players: vec![
                player(JP_ACCOUNT, "东家"),
                self_player(2202, "我"),
                player(JP_ACCOUNT + 1, "西家"),
                player(JP_ACCOUNT + 2, "北家"),
            ],
            seat_list: vec![JP_ACCOUNT, 2202, JP_ACCOUNT + 1, JP_ACCOUNT + 2],
            is_game_start: true,
            ..Default::default()
        }
    }

    /// Same as [`auth_game`] except that the right-hand opponent is a robot
    /// and therefore only present in `robots`.
    fn auth_game_with_robot() -> lq::ResAuthGame {
        lq::ResAuthGame {
            players: vec![
                player(1101, "东家"),
                self_player(2202, "我"),
                player(4404, "北家"),
            ],
            robots: vec![player(3303, "机器人")],
            seat_list: SEAT_IDS.to_vec(),
            is_game_start: true,
            ..Default::default()
        }
    }

    fn display_names(core: &mut Core, response: lq::ResAuthGame) -> Vec<(u32, String)> {
        let bytes = exchange(
            core,
            ".lq.FastTest.authGame",
            Vec::new(),
            response.encode_to_vec(),
        )
        .expect("authGame is always re-encoded by the modder");
        let envelope = BaseMessage::decode(&bytes[3..]).unwrap();
        let decoded = lq::ResAuthGame::decode(envelope.data.as_slice()).unwrap();
        decoded
            .players
            .iter()
            .chain(decoded.robots.iter())
            .map(|view| (view.account_id, view.nickname.clone()))
            .collect()
    }

    fn name_of(names: &[(u32, String)], account_id: u32) -> String {
        names
            .iter()
            .find(|(id, _)| *id == account_id)
            .map(|(_, name)| name.clone())
            .unwrap_or_else(|| panic!("account {account_id} missing from payload"))
    }

    #[test]
    fn auth_game_normal_mode_keeps_server_names_and_zone_prefix() {
        let _guard = naming_guard();
        let mut settings = ModSettings::default();
        settings.nickname = "自己".to_owned();
        let mut core = core_with(settings);
        login(&mut core, 2202);
        let names = display_names(&mut core, auth_game());
        // show_server is enabled by default: every seat keeps the current
        // zone-prefixed rendering, opponents untouched.
        assert_eq!(name_of(&names, 2202), format!("{CN_PREFIX}自己"));
        assert_eq!(name_of(&names, 1101), format!("{CN_PREFIX}东家"));
        assert_eq!(name_of(&names, 3303), format!("{CN_PREFIX}西家"));
        assert_eq!(name_of(&names, 4404), format!("{CN_PREFIX}北家"));
    }

    #[test]
    fn normal_mode_without_show_server_keeps_the_names_unprefixed() {
        let _guard = naming_guard();
        let mut settings = ModSettings::default();
        settings.nickname = "自己".to_owned();
        settings
            .apply_patch(SettingsPatch {
                show_server: Some(false),
                ..Default::default()
            })
            .unwrap();
        let mut core = core_with(settings);
        login(&mut core, 2202);
        let names = display_names(&mut core, auth_game());
        // The switch is the sole authority for the tag, in normal mode too.
        assert_eq!(name_of(&names, 2202), "自己");
        assert_eq!(name_of(&names, 1101), "东家");
        assert_eq!(name_of(&names, 3303), "西家");
        assert_eq!(name_of(&names, 4404), "北家");
        // Nothing was replaced, so there is nothing to restore either.
        assert_eq!(naming_original(1101), "");
    }

    #[test]
    fn auth_game_anonymizes_opponents_left_to_right_after_settings_change() {
        let _guard = naming_guard();
        let mut core = core_with(ModSettings::default());
        login(&mut core, 2202);
        let before = display_names(&mut core, auth_game());
        assert_eq!(name_of(&before, 1101), format!("{CN_PREFIX}东家"));

        // Change the mode at runtime through the same snapshot/patch path the
        // settings RPC uses; the next authGame payload must pick it up.
        let mut settings = core.runtime.block_on(core.modder.settings_snapshot());
        settings
            .apply_patch(SettingsPatch {
                naming_mode: Some(NamingMode::Abc),
                ..Default::default()
            })
            .unwrap();
        core.runtime
            .block_on(core.modder.replace_settings(settings));

        let after = display_names(&mut core, auth_game());
        // Left = kamicha (seat 0 = 1101), top = toimen (seat 3 = 4404),
        // right = shimocha (seat 2 = 3303), each carrying the region tag of the
        // seat because show_server is on. The local seat keeps its name.
        assert_eq!(name_of(&after, 1101), format!("{CN_PREFIX}领头的羊"));
        assert_eq!(name_of(&after, 4404), format!("{CN_PREFIX}镇山的虎"));
        assert_eq!(name_of(&after, 3303), format!("{CN_PREFIX}远见的鹰"));
        assert_eq!(name_of(&after, 2202), format!("{CN_PREFIX}我"));
    }

    #[test]
    fn preset_labels_keep_the_region_tag_of_their_own_seat() {
        let _guard = naming_guard();
        let mut settings = ModSettings::default();
        settings.naming_mode = NamingMode::Abc;
        let mut core = core_with(settings);
        login(&mut core, 2202);
        let names = display_names(&mut core, auth_game_jp());
        // Japanese opponents keep [JP], the Chinese local seat keeps [CN]: the
        // prefix belongs to the seat, not to the label or the mode.
        assert_eq!(name_of(&names, JP_ACCOUNT), "[JP]领头的羊");
        assert_eq!(name_of(&names, JP_ACCOUNT + 2), "[JP]镇山的虎");
        assert_eq!(name_of(&names, JP_ACCOUNT + 1), "[JP]远见的鹰");
        assert_eq!(name_of(&names, 2202), format!("{CN_PREFIX}我"));
    }

    #[test]
    fn turning_show_server_off_drops_the_tag_of_a_rewritten_seat() {
        let _guard = naming_guard();
        let mut settings = ModSettings::default();
        settings.naming_mode = NamingMode::Abc;
        let mut core = core_with(settings);
        login(&mut core, 2202);
        assert_eq!(
            name_of(&display_names(&mut core, auth_game_jp()), JP_ACCOUNT),
            "[JP]领头的羊"
        );

        // Only the switch changes: the label must now be bare, on the same
        // account, with no leftover [JP].
        let mut settings = core.runtime.block_on(core.modder.settings_snapshot());
        settings
            .apply_patch(SettingsPatch {
                show_server: Some(false),
                ..Default::default()
            })
            .unwrap();
        core.runtime
            .block_on(core.modder.replace_settings(settings));
        let names = display_names(&mut core, auth_game_jp());
        assert_eq!(name_of(&names, JP_ACCOUNT), "领头的羊");
        assert_eq!(name_of(&names, 2202), "我");

        // And back on: exactly one tag again, never two.
        let mut settings = core.runtime.block_on(core.modder.settings_snapshot());
        settings
            .apply_patch(SettingsPatch {
                show_server: Some(true),
                ..Default::default()
            })
            .unwrap();
        core.runtime
            .block_on(core.modder.replace_settings(settings));
        let names = display_names(&mut core, auth_game_jp());
        assert_eq!(name_of(&names, JP_ACCOUNT), "[JP]领头的羊");
    }

    #[test]
    fn naming_prefix_abi_matches_the_protocol_zone_tag() {
        assert_eq!(naming_prefix(2202), CN_PREFIX);
        assert_eq!(naming_prefix(6 << 23), CN_PREFIX);
        assert_eq!(naming_prefix(JP_ACCOUNT), "[JP]");
        assert_eq!(naming_prefix(EN_ACCOUNT), "[EN]");
        assert_eq!(naming_prefix(16 << 23), "[??]");
        // A robot has no account and therefore no region.
        assert_eq!(naming_prefix(0), "");
    }

    #[test]
    fn auth_game_custom_names_skip_empty_slots_and_cover_robots() {
        let _guard = naming_guard();
        let mut settings = ModSettings::default();
        settings
            .apply_patch(SettingsPatch {
                naming_mode: Some(NamingMode::Custom),
                custom_names: Some(vec!["上家".to_owned(), String::new(), "下家".to_owned()]),
                ..Default::default()
            })
            .unwrap();
        let mut core = core_with(settings);
        login(&mut core, 2202);
        let names = display_names(&mut core, auth_game_with_robot());
        // Custom labels follow the same independent switch as preset ones.
        assert_eq!(name_of(&names, 1101), format!("{CN_PREFIX}上家"));
        // The empty second entry keeps the server name for the top opponent.
        assert_eq!(name_of(&names, 4404), format!("{CN_PREFIX}北家"));
        // A robot occupying the right-hand seat is renamed as well. Its account
        // id is a real one here, so it carries the tag of that account.
        assert_eq!(name_of(&names, 3303), format!("{CN_PREFIX}下家"));
        assert_eq!(name_of(&names, 2202), format!("{CN_PREFIX}我"));
    }

    #[test]
    fn a_custom_label_is_taken_verbatim_and_never_matched_against_a_tag() {
        let _guard = naming_guard();
        // A label that *looks* like another region's tag is not stripped or
        // reinterpreted: only the seat's own account decides the prefix.
        let mut settings = ModSettings::default();
        settings
            .apply_patch(SettingsPatch {
                naming_mode: Some(NamingMode::Custom),
                custom_names: Some(vec!["[JP]假前缀".to_owned(), String::new(), String::new()]),
                ..Default::default()
            })
            .unwrap();
        let mut core = core_with(settings);
        login(&mut core, 2202);
        let names = display_names(&mut core, auth_game());
        assert_eq!(name_of(&names, 1101), format!("{CN_PREFIX}[JP]假前缀"));
        // The cache of that seat keeps the real server name, not the label.
        assert_eq!(naming_original(1101), "东家");
    }

    #[test]
    fn auth_game_naming_requires_the_local_seat_in_seat_list() {
        let _guard = naming_guard();
        let mut settings = ModSettings::default();
        settings
            .apply_patch(SettingsPatch {
                naming_mode: Some(NamingMode::Abc),
                ..Default::default()
            })
            .unwrap();
        let mut core = core_with(settings);

        // No login response yet: the local account id is still unknown.
        let unknown = display_names(&mut core, auth_game());
        assert_eq!(name_of(&unknown, 1101), format!("{CN_PREFIX}东家"));

        // Account known, but the payload has no seat for it (e.g. a spectator
        // or a sync payload), so the visual order cannot be anchored.
        login(&mut core, 999_999);
        let names = display_names(&mut core, auth_game());
        for account in SEAT_IDS {
            assert_ne!(name_of(&names, account), "");
        }
        assert_eq!(name_of(&names, 1101), format!("{CN_PREFIX}东家"));
        assert_eq!(name_of(&names, 4404), format!("{CN_PREFIX}北家"));
    }

    #[test]
    fn auth_game_request_payload_is_never_rewritten() {
        let mut settings = ModSettings::default();
        settings
            .apply_patch(SettingsPatch {
                naming_mode: Some(NamingMode::Abc),
                ..Default::default()
            })
            .unwrap();
        let mut core = core_with(settings);
        login(&mut core, 2202);
        let request = lq::ReqAuthGame {
            account_id: 2202,
            token: "token".to_owned(),
            game_uuid: "game".to_owned(),
            ..Default::default()
        }
        .encode_to_vec();
        let wire = frame(2, ".lq.FastTest.authGame", request);
        let result = core.process(1, true, &wire);
        assert_eq!(result.action, PASS);
        assert!(result.message.data.is_null());
        assert!(result.injection.data.is_null());
    }

    #[test]
    fn auth_game_publishes_the_names_it_replaced() {
        let _guard = naming_guard();
        let mut settings = ModSettings::default();
        settings
            .apply_patch(SettingsPatch {
                naming_mode: Some(NamingMode::Abc),
                ..Default::default()
            })
            .unwrap();
        let mut core = core_with(settings);
        login(&mut core, 2202);
        display_names(&mut core, auth_game());

        // The display layer can put every renamed seat back to its server name.
        // The cached value is the name the server sent, *without* the tag the
        // rewrite added: the display layer re-applies the tag from the live
        // show_server state, which is what keeps it correct after a switch.
        assert_eq!(naming_original(1101), "东家");
        assert_eq!(naming_original(4404), "北家");
        assert_eq!(naming_original(3303), "西家");
        // The local seat is never rewritten, so it has nothing to restore.
        assert_eq!(naming_original(2202), "");
        // An unknown account is answered with an empty string, not an error.
        assert_eq!(naming_original(999), "");
    }

    #[test]
    fn every_auth_game_replaces_the_restore_cache() {
        let _guard = naming_guard();
        let mut settings = ModSettings::default();
        settings
            .apply_patch(SettingsPatch {
                naming_mode: Some(NamingMode::Abc),
                ..Default::default()
            })
            .unwrap();
        let mut core = core_with(settings);
        login(&mut core, 2202);
        display_names(&mut core, auth_game());
        assert_eq!(naming_original(1101), "东家");

        // A second table that keeps the server names must clear the cache: the
        // names of the previous table can never leak into the new one.
        let mut plain = ModSettings::default();
        plain.naming_mode = NamingMode::Normal;
        core.runtime
            .block_on(core.modder.replace_settings(plain));
        display_names(&mut core, auth_game());
        assert_eq!(naming_original(1101), "");
        assert_eq!(naming_original(4404), "");

        // Losing the connection ends the table as well.
        core.runtime
            .block_on(core.modder.replace_settings(ModSettings::default()));
        unsafe { majmax_modder_forget_connection(1) };
        assert_eq!(naming_original(1101), "");
    }

    #[test]
    fn settings_patch_publishes_the_lua_state() {
        let _guard = naming_guard();
        struct TestDirectory(PathBuf);
        impl Drop for TestDirectory {
            fn drop(&mut self) {
                let _ = std::fs::remove_dir_all(&self.0);
            }
        }
        let directory = TestDirectory(std::env::temp_dir().join(format!(
            "majmax-naming-state-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        )));
        std::fs::create_dir(&directory.0).unwrap();
        std::fs::write(
            directory.0.join("settings.mod.json"),
            serde_json::to_vec(&ModSettings::default()).unwrap(),
        )
        .unwrap();
        std::fs::copy(
            PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                .join("../../external/MajsoulMax-rs/liqi_config/max_data.yaml"),
            directory.0.join("max_data.yaml"),
        )
        .unwrap();
        let mut core = Core::new(directory.0.clone()).unwrap();
        // The state is published when the settings are loaded, before any user
        // interaction, otherwise the in-game layer could not tell "normal" from
        // "not loaded yet". The second line is the independent showServer flag.
        assert_eq!(naming_state(), "normal\n1\n\n\n");

        // Applying a patch through the same entry point the two UI surfaces use
        // republishes the state; that value is the Lua refresh trigger.
        core.update_settings(SettingsPatch {
            naming_mode: Some(NamingMode::Custom),
            custom_names: Some(vec!["上家".to_owned(), String::new(), "下家".to_owned()]),
            ..Default::default()
        })
        .unwrap();
        assert_eq!(naming_state(), "custom\n1\n上家\n\n下家");

        // Flipping only the switch is its own redraw token: the labels are
        // untouched and the in-game layer repaints the current table from it.
        core.update_settings(SettingsPatch {
            show_server: Some(false),
            ..Default::default()
        })
        .unwrap();
        assert_eq!(naming_state(), "custom\n0\n上家\n\n下家");

        core.update_settings(SettingsPatch {
            naming_mode: Some(NamingMode::Normal),
            show_server: Some(true),
            ..Default::default()
        })
        .unwrap();
        assert_eq!(naming_state(), "normal\n1\n\n\n");
    }
}
