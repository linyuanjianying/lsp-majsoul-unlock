// Reduced from MajsoulMax-rs src/settings.rs at the pinned submodule commit.
// It keeps only the data and methods consumed by the upstream Modder.
use crate::proto::lq::ViewSlot;
use anyhow::{Context, Result, bail, ensure};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    fs::OpenOptions,
    io::Write,
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};
use tracing::error;

/// Number of opponent display-name slots a game can assign. A four-player
/// table has three opponents; a three-player table uses the first two.
pub const OPPONENT_LABEL_SLOTS: usize = 3;

/// Fixed preset labels used by [`NamingMode::Abc`], ordered left to right.
///
/// The three seats a four-player table shows are kamicha (left), toimen (top)
/// and shimocha (right), which is also the order
/// `ModSettings::opponent_label` hands them out in. The persisted wire value of
/// the mode stays `abc`, so a document written by an older build (or the
/// manager app's `anonymize` spelling) keeps meaning the same thing.
pub const ANONYMOUS_OPPONENT_LABELS: [&str; OPPONENT_LABEL_SLOTS] = ["领头的羊", "镇山的虎", "远见的鹰"];

/// Longest accepted user-defined opponent name. Matches the nickname limit
/// enforced for the local player below.
pub const MAX_OPPONENT_NAME_CHARS: usize = 64;

/// How the display names of the opponents are rewritten.
///
/// The mode only affects received client display data and is applied at two
/// places: the in-game Lua layer renames the seats of the table on screen as soon
/// as the value changes (`naming_wire` is its trigger), and an `authGame` response
/// is parsed with the same choice for the next game. An outgoing request is never
/// changed, and the choice is not a network or protocol setting.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum NamingMode {
    /// Keep the server-provided names, i.e. the pre-existing behavior.
    #[default]
    Normal,
    /// Replace the opponents with the fixed preset labels (see
    /// [`ANONYMOUS_OPPONENT_LABELS`]); the user-facing name of this mode is
    /// "预设昵称" / "Preset names".
    Abc,
    /// Replace the opponents with the user-defined `custom_names` entries.
    Custom,
}

impl NamingMode {
    /// Canonical value persisted in `settings.mod.json`.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Normal => "normal",
            Self::Abc => "abc",
            Self::Custom => "custom",
        }
    }

    /// Parse a persisted or patched value.
    ///
    /// The accepted spellings cover both the canonical names and the wording
    /// used while the feature was described ("anonymize" for the `A`/`B`/`C`
    /// mode), and are case and separator insensitive. An unknown value degrades
    /// to [`NamingMode::Normal`] instead of failing the whole settings load, so
    /// a document written by a newer build can never stop the mod from starting.
    pub fn parse(value: &str) -> Self {
        let normalized: String = value
            .chars()
            .filter(char::is_ascii_alphanumeric)
            .map(|character| character.to_ascii_lowercase())
            .collect();
        match normalized.as_str() {
            "abc" | "anon" | "anonymize" | "anonymized" | "anonymizeabc" | "abcnames" => Self::Abc,
            "custom" | "customnames" => Self::Custom,
            _ => Self::Normal,
        }
    }
}

impl Serialize for NamingMode {
    fn serialize<S: serde::Serializer>(
        &self,
        serializer: S,
    ) -> std::result::Result<S::Ok, S::Error> {
        serializer.serialize_str(self.as_str())
    }
}

impl<'de> Deserialize<'de> for NamingMode {
    fn deserialize<D: serde::Deserializer<'de>>(
        deserializer: D,
    ) -> std::result::Result<Self, D::Error> {
        Ok(Self::parse(&String::deserialize(deserializer)?))
    }
}

/// Validate user-defined opponent labels.
///
/// Empty entries are allowed on purpose: they mean "keep the server name for
/// this seat" and are only checked for length and control characters.
pub fn validate_opponent_names(names: &[String]) -> Result<()> {
    ensure!(
        names.len() <= OPPONENT_LABEL_SLOTS,
        "自定义对手名称最多 {OPPONENT_LABEL_SLOTS} 项"
    );
    for name in names {
        ensure!(
            name.chars().count() <= MAX_OPPONENT_NAME_CHARS,
            "自定义对手名称不能超过 {MAX_OPPONENT_NAME_CHARS} 个字符"
        );
        ensure!(
            name.chars().all(|character| !character.is_control()),
            "自定义对手名称不能包含控制字符"
        );
    }
    Ok(())
}

#[derive(Serialize, Deserialize, Debug, Default, Clone)]
pub struct MaxData {
    pub character: Vec<u32>,
    pub skin: Vec<u32>,
    pub title: Vec<u32>,
    pub item: Vec<u32>,
    pub loading_image: Vec<u32>,
    pub emoji: HashMap<u32, Vec<u32>>,
    pub endings: Vec<u32>,
}

impl MaxData {
    pub fn load(dir: &Path) -> Result<Self> {
        let content =
            std::fs::read_to_string(dir.join("max_data.yaml")).context("无法读取 max_data.yaml")?;
        parse_max_data(&content)
    }
}

fn parse_max_data(content: &str) -> Result<MaxData> {
    let mut result = MaxData::default();
    let mut section = String::new();
    let mut emoji_character = None;
    for (line_number, raw_line) in content.lines().enumerate() {
        let line = raw_line.trim_end_matches('\r');
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        if !line.starts_with(' ') && !trimmed.starts_with("- ") {
            ensure!(
                trimmed.ends_with(':'),
                "invalid max_data line {}",
                line_number + 1
            );
            section = trimmed.trim_end_matches(':').to_owned();
            emoji_character = None;
            ensure!(
                matches!(
                    section.as_str(),
                    "character" | "skin" | "title" | "item" | "loading_image" | "emoji" | "endings"
                ),
                "unknown max_data section on line {}",
                line_number + 1
            );
            continue;
        }
        if section == "emoji" && line.starts_with("  ") && trimmed.ends_with(':') {
            let id = trimmed
                .trim_end_matches(':')
                .parse::<u32>()
                .with_context(|| format!("invalid emoji character on line {}", line_number + 1))?;
            result.emoji.entry(id).or_default();
            emoji_character = Some(id);
            continue;
        }
        ensure!(
            trimmed.starts_with("- "),
            "invalid max_data line {}",
            line_number + 1
        );
        let id = trimmed[2..]
            .parse::<u32>()
            .with_context(|| format!("invalid data ID on line {}", line_number + 1))?;
        match section.as_str() {
            "character" => result.character.push(id),
            "skin" => result.skin.push(id),
            "title" => result.title.push(id),
            "item" => result.item.push(id),
            "loading_image" => result.loading_image.push(id),
            "endings" => result.endings.push(id),
            "emoji" => result
                .emoji
                .get_mut(&emoji_character.context("emoji item without character")?)
                .expect("emoji character inserted above")
                .push(id),
            _ => bail!(
                "list item outside a max_data section on line {}",
                line_number + 1
            ),
        }
    }
    ensure!(!result.character.is_empty(), "max_data has no characters");
    ensure!(!result.skin.is_empty(), "max_data has no skins");
    ensure!(!result.title.is_empty(), "max_data has no titles");
    ensure!(!result.item.is_empty(), "max_data has no items");
    ensure!(
        !result.loading_image.is_empty(),
        "max_data has no loading images"
    );
    ensure!(!result.emoji.is_empty(), "max_data has no emoji data");
    ensure!(
        result.emoji.values().all(|items| !items.is_empty()),
        "empty emoji list"
    );
    ensure!(!result.endings.is_empty(), "max_data has no endings");
    Ok(result)
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase", default)]
pub struct ModSettings {
    /// Whether the message modifier should be enabled after the next process start.
    /// Missing in older settings files, where serde's `default` value keeps the
    /// existing behavior enabled.
    pub enabled: bool,
    pub main_char: u32,
    pub char_skin: HashMap<u32, u32>,
    pub nickname: String,
    /// Opponent display-name mode; see [`NamingMode`].
    pub naming_mode: NamingMode,
    /// User-defined labels for [`NamingMode::Custom`], in left-to-right
    /// visual seat order. Missing or empty entries keep the server name.
    pub custom_names: Vec<String>,
    pub star_character: Vec<u32>,
    pub hidden_characters: Vec<u32>,
    hint_switch: bool,
    pub title: u32,
    pub loading_bg: Vec<u32>,
    emoji_switch: bool,
    pub views_presets: [Vec<ViewSlot>; 10],
    pub preset_index: u32,
    show_server: bool,
    anti_nickname_censorship: bool,
    pub random_char_switch: bool,
    pub random_char_pool: Vec<(u32, u32)>,
    pub verified: u32,
    #[serde(skip)]
    dir: PathBuf,
}

impl Default for ModSettings {
    fn default() -> Self {
        Self {
            enabled: true,
            main_char: 200001,
            char_skin: HashMap::new(),
            nickname: String::new(),
            naming_mode: NamingMode::Normal,
            custom_names: Vec::new(),
            star_character: Vec::new(),
            hidden_characters: Vec::new(),
            hint_switch: true,
            title: 0,
            loading_bg: Vec::new(),
            emoji_switch: false,
            views_presets: Default::default(),
            preset_index: 0,
            show_server: true,
            anti_nickname_censorship: true,
            random_char_switch: false,
            random_char_pool: Vec::new(),
            verified: 0,
            dir: PathBuf::new(),
        }
    }
}

/// Fields exposed by the in-game settings page. Keeping this as a patch rather
/// than accepting a whole replacement document prevents a UI update from
/// accidentally deleting settings that are still managed by the original game
/// pages (skins, views and titles).
#[derive(Serialize, Deserialize, Debug, Default, Clone)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SettingsPatch {
    pub enabled: Option<bool>,
    pub hint_switch: Option<bool>,
    pub emoji_switch: Option<bool>,
    pub show_server: Option<bool>,
    pub anti_nickname_censorship: Option<bool>,
    pub random_char_switch: Option<bool>,
    pub random_char_pool: Option<Vec<(u32, u32)>>,
    pub nickname: Option<String>,
    pub naming_mode: Option<NamingMode>,
    pub custom_names: Option<Vec<String>>,
}

impl SettingsPatch {
    pub fn is_empty(&self) -> bool {
        self.enabled.is_none()
            && self.hint_switch.is_none()
            && self.emoji_switch.is_none()
            && self.show_server.is_none()
            && self.anti_nickname_censorship.is_none()
            && self.random_char_switch.is_none()
            && self.random_char_pool.is_none()
            && self.nickname.is_none()
            && self.naming_mode.is_none()
            && self.custom_names.is_none()
    }
}

impl ModSettings {
    pub fn load(dir: &Path) -> Result<Self> {
        let file = dir.join("settings.mod.json");
        let mut settings: Self = serde_json::from_str(
            &std::fs::read_to_string(&file).context("无法读取 settings.mod.json")?,
        )
        .context("无法解析 settings.mod.json")?;
        settings.dir = dir.to_path_buf();
        Ok(settings)
    }

    pub fn default_avatar_id(char_id: u32) -> Result<u32> {
        let id = char_id.to_string();
        let suffix = id
            .get(4..)
            .with_context(|| format!("角色 ID {char_id} 过短"))?;
        format!("40{suffix}01")
            .parse()
            .with_context(|| format!("无法解析角色 {char_id} 的默认装扮 ID"))
    }

    pub fn avatar_id_of(&self, char_id: u32) -> Result<u32> {
        self.char_skin
            .get(&char_id)
            .copied()
            .map(Ok)
            .unwrap_or_else(|| Self::default_avatar_id(char_id))
    }

    pub fn main_avatar_id(&self) -> Result<u32> {
        self.avatar_id_of(self.main_char)
    }

    pub fn current_preset(&self) -> &[ViewSlot] {
        self.views_presets
            .get(self.preset_index as usize)
            .unwrap_or(&self.views_presets[0])
    }

    pub fn avatar_frame(&self) -> u32 {
        self.current_preset()
            .iter()
            .find(|view| view.slot == 5)
            .map(|view| view.item_id)
            .unwrap_or_default()
    }

    pub fn preset_count(&self) -> usize {
        self.views_presets.len()
    }

    pub fn hint_on(&self) -> bool {
        self.hint_switch
    }

    pub fn emoji_on(&self) -> bool {
        self.emoji_switch
    }

    pub fn show_server(&self) -> bool {
        self.show_server
    }

    pub fn anti_nickname_censorship(&self) -> bool {
        self.anti_nickname_censorship
    }

    pub fn naming_mode(&self) -> NamingMode {
        self.naming_mode
    }

    /// Label for one opponent slot in left-to-right visual seat order.
    ///
    /// `None` means "leave this seat's server name alone", which is what
    /// [`NamingMode::Normal`] and empty custom entries produce.
    pub fn opponent_label(&self, slot: usize) -> Option<&str> {
        match self.naming_mode() {
            NamingMode::Normal => None,
            NamingMode::Abc => ANONYMOUS_OPPONENT_LABELS.get(slot).copied(),
            NamingMode::Custom => self
                .custom_names
                .get(slot)
                .filter(|name| !name.trim().is_empty())
                .map(String::as_str),
        }
    }

    /// Compact description of the naming choice for the in-game Lua layer.
    ///
    /// The wire is `<mode>\n<show_server:0|1>\n<slot0>\n<slot1>\n<slot2>`:
    /// `mode` is the canonical [`NamingMode`] spelling, the second line is the
    /// independent `showServer` switch and each slot is the label that should
    /// replace the server name, or an empty line when the seat keeps it. A
    /// newline separator needs no escaping because
    /// [`validate_opponent_names`] rejects control characters, and the compact
    /// form keeps the whole settings document out of the Lua bridge (only the
    /// switch and the three labels ever cross it).
    ///
    /// The `showServer` switch is published separately from `mode` on purpose:
    /// it is the sole authority for the `[CN]`/`[JP]`/`[EN]`/`[??]` zone tag, so
    /// it has to apply to all three modes (server names, preset labels and
    /// custom labels alike) and the display layer must be able to compose a
    /// name without knowing which mode produced the label.
    ///
    /// The same string is used as the change token: the Lua side redraws the
    /// current table exactly when this value differs from the previous one, so
    /// flipping `showServer` alone repaints the table too.
    pub fn naming_wire(&self) -> String {
        let mut wire = String::with_capacity(32 + OPPONENT_LABEL_SLOTS * 8);
        wire.push_str(self.naming_mode().as_str());
        wire.push('\n');
        wire.push(if self.show_server { '1' } else { '0' });
        for slot in 0..OPPONENT_LABEL_SLOTS {
            wire.push('\n');
            wire.push_str(self.opponent_label(slot).unwrap_or(""));
        }
        wire
    }

    pub fn apply_patch(&mut self, patch: SettingsPatch) -> Result<()> {
        if let Some(nickname) = patch.nickname {
            ensure!(nickname.chars().count() <= 64, "昵称不能超过 64 个字符");
            ensure!(
                nickname.chars().all(|character| !character.is_control()),
                "昵称不能包含控制字符"
            );
            self.nickname = nickname;
        }
        if let Some(names) = patch.custom_names {
            validate_opponent_names(&names)?;
            self.custom_names = names;
        }
        if let Some(mode) = patch.naming_mode {
            self.naming_mode = mode;
        }
        if let Some(pool) = patch.random_char_pool {
            ensure!(pool.len() <= 256, "随机角色池不能超过 256 项");
            ensure!(
                pool.iter().all(|(character, skin)| *character != 0 && *skin != 0),
                "随机角色池包含无效角色"
            );
            self.random_char_pool = pool;
        }
        if let Some(value) = patch.enabled {
            self.enabled = value;
        }
        if let Some(value) = patch.hint_switch {
            self.hint_switch = value;
        }
        if let Some(value) = patch.emoji_switch {
            self.emoji_switch = value;
        }
        if let Some(value) = patch.show_server {
            self.show_server = value;
        }
        if let Some(value) = patch.anti_nickname_censorship {
            self.anti_nickname_censorship = value;
        }
        if let Some(value) = patch.random_char_switch {
            self.random_char_switch = value;
        }
        Ok(())
    }

    pub fn write(&self) {
        if let Err(error) = self.write_atomic() {
            error!("Failed to write settings.mod.json: {error}");
        }
    }

    /// Persist the complete settings document without exposing a partially
    /// written JSON file to the next game process. The temporary file lives
    /// beside the target so rename remains atomic on Android's filesystem.
    pub fn write_atomic(&self) -> Result<()> {
        static NEXT_WRITE: AtomicU64 = AtomicU64::new(0);
        ensure!(
            !self.dir.as_os_str().is_empty(),
            "设置目录未初始化"
        );
        let path = self.dir.join("settings.mod.json");
        let content = serde_json::to_vec_pretty(self).context("序列化 settings.mod.json 失败")?;
        let temporary = path.with_extension(format!(
            "json.tmp.{}.{}",
            std::process::id(),
            NEXT_WRITE.fetch_add(1, Ordering::Relaxed)
        ));
        let _ = std::fs::remove_file(&temporary);
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&temporary)
            .with_context(|| format!("创建临时设置文件失败: {}", temporary.display()))?;
        let write_result = (|| -> Result<()> {
            file.write_all(&content).context("写入临时设置文件失败")?;
            file.sync_all().context("同步临时设置文件失败")?;
            std::fs::rename(&temporary, &path).context("替换 settings.mod.json 失败")?;
            Ok(())
        })();
        if write_result.is_err() {
            let _ = std::fs::remove_file(&temporary);
        }
        write_result
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_upstream_max_data() {
        let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../external/MajsoulMax-rs/liqi_config");
        let data = MaxData::load(&path).unwrap();
        assert!(!data.character.is_empty());
        assert!(!data.endings.is_empty());
    }

    #[test]
    fn parses_the_pinned_upstream_settings_document() {
        // The shipped document predates the naming modes, so loading it must
        // keep the previous behavior and only fill in the new defaults.
        let directory = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../external/MajsoulMax-rs/liqi_config");
        let settings = ModSettings::load(&directory).unwrap();
        assert_eq!(settings.naming_mode(), NamingMode::Normal);
        assert!(settings.custom_names.is_empty());
        assert!(!settings.char_skin.is_empty());
    }

    #[test]
    fn missing_enabled_field_keeps_mod_enabled() {
        let settings: ModSettings = serde_json::from_str(
            r#"{"mainChar":200001,"nickname":"旧配置"}"#,
        )
        .unwrap();
        assert!(settings.enabled);
        assert_eq!(settings.nickname, "旧配置");
    }

    #[test]
    fn patch_changes_only_exposed_fields() {
        let mut settings = ModSettings::default();
        settings.main_char = 200123;
        settings.title = 700001;
        settings
            .apply_patch(SettingsPatch {
                hint_switch: Some(false),
                nickname: Some("测试名".to_owned()),
                ..Default::default()
            })
            .unwrap();
        assert!(!settings.hint_on());
        assert_eq!(settings.nickname, "测试名");
        assert_eq!(settings.main_char, 200123);
        assert_eq!(settings.title, 700001);
    }

    #[test]
    fn patch_rejects_control_characters_and_bad_pool() {
        let mut settings = ModSettings::default();
        assert!(settings
            .apply_patch(SettingsPatch {
                nickname: Some("bad\nname".to_owned()),
                ..Default::default()
            })
            .is_err());
        assert!(settings
            .apply_patch(SettingsPatch {
                random_char_pool: Some(vec![(0, 400101)]),
                ..Default::default()
            })
            .is_err());
    }

    #[test]
    fn legacy_settings_keep_normal_naming() {
        // Files written before the naming modes existed must load and behave
        // exactly like the previous build.
        let settings: ModSettings =
            serde_json::from_str(r#"{"mainChar":200001,"nickname":"旧配置"}"#).unwrap();
        assert_eq!(settings.naming_mode(), NamingMode::Normal);
        assert!(settings.custom_names.is_empty());
        assert_eq!(settings.opponent_label(0), None);
    }

    #[test]
    fn naming_mode_is_tolerant_on_load_and_canonical_on_save() {
        assert_eq!(NamingMode::parse("Anonymize"), NamingMode::Abc);
        assert_eq!(NamingMode::parse("a_b-c"), NamingMode::Abc);
        assert_eq!(NamingMode::parse("Custom Names"), NamingMode::Custom);
        assert_eq!(NamingMode::parse("future-mode"), NamingMode::Normal);

        let settings: ModSettings = serde_json::from_str(
            r#"{"namingMode":"anonymize","customNames":["甲","","丙"]}"#,
        )
        .unwrap();
        assert_eq!(settings.naming_mode(), NamingMode::Abc);
        assert_eq!(settings.custom_names.len(), 3);

        let saved = serde_json::to_value(&settings).unwrap();
        assert_eq!(saved["namingMode"], serde_json::json!("abc"));
        assert_eq!(saved["customNames"], serde_json::json!(["甲", "", "丙"]));
    }

    #[test]
    fn opponent_labels_follow_the_mode_and_keep_original_when_empty() {
        let mut settings = ModSettings::default();
        assert_eq!(settings.opponent_label(0), None);

        settings
            .apply_patch(SettingsPatch {
                naming_mode: Some(NamingMode::Abc),
                ..Default::default()
            })
            .unwrap();
        // Preset labels, in the visual left-to-right order the rewrite uses.
        assert_eq!(settings.opponent_label(0), Some("领头的羊"));
        assert_eq!(settings.opponent_label(1), Some("镇山的虎"));
        assert_eq!(settings.opponent_label(2), Some("远见的鹰"));
        assert_eq!(settings.opponent_label(3), None);

        settings
            .apply_patch(SettingsPatch {
                naming_mode: Some(NamingMode::Custom),
                custom_names: Some(vec![" 上家 ".to_owned(), String::new(), "  ".to_owned()]),
                ..Default::default()
            })
            .unwrap();
        assert_eq!(settings.opponent_label(0), Some(" 上家 "));
        assert_eq!(settings.opponent_label(1), None);
        assert_eq!(settings.opponent_label(2), None);
    }

    #[test]
    fn naming_wire_is_the_compact_lua_state() {
        let mut settings = ModSettings::default();
        // Normal mode: no label on any line, so the Lua layer leaves the
        // received (server) names alone. `showServer` keeps its default (on),
        // which is the field the display layer reads to rebuild the zone tag.
        assert_eq!(settings.naming_wire(), "normal\n1\n\n\n");

        settings
            .apply_patch(SettingsPatch {
                naming_mode: Some(NamingMode::Abc),
                ..Default::default()
            })
            .unwrap();
        assert_eq!(settings.naming_wire(), "abc\n1\n领头的羊\n镇山的虎\n远见的鹰");

        settings
            .apply_patch(SettingsPatch {
                naming_mode: Some(NamingMode::Custom),
                custom_names: Some(vec!["上家".to_owned(), String::new(), "下家".to_owned()]),
                ..Default::default()
            })
            .unwrap();
        // An empty slot stays an empty line: "keep the server name here".
        assert_eq!(settings.naming_wire(), "custom\n1\n上家\n\n下家");

        // `showServer` is independent of the mode: turning it off only changes
        // the flag line, the labels stay identical.
        settings
            .apply_patch(SettingsPatch {
                show_server: Some(false),
                ..Default::default()
            })
            .unwrap();
        assert_eq!(settings.naming_wire(), "custom\n0\n上家\n\n下家");
        // And it is part of the change token, so flipping it alone is one edit.
        assert_ne!(
            settings.naming_wire(),
            ModSettings::default().naming_wire()
        );
    }

    #[test]
    fn patch_rejects_bad_opponent_names() {
        let mut settings = ModSettings::default();
        assert!(
            settings
                .apply_patch(SettingsPatch {
                    custom_names: Some(vec!["A".to_owned(); OPPONENT_LABEL_SLOTS + 1]),
                    ..Default::default()
                })
                .is_err()
        );
        assert!(
            settings
                .apply_patch(SettingsPatch {
                    custom_names: Some(vec!["bad\nname".to_owned()]),
                    ..Default::default()
                })
                .is_err()
        );
        assert!(
            settings
                .apply_patch(SettingsPatch {
                    custom_names: Some(vec!["字".repeat(MAX_OPPONENT_NAME_CHARS + 1)]),
                    ..Default::default()
                })
                .is_err()
        );
        // A rejected patch must not partially apply.
        assert!(settings.custom_names.is_empty());
        assert!(
            settings
                .apply_patch(SettingsPatch {
                    custom_names: Some(vec!["字".repeat(MAX_OPPONENT_NAME_CHARS)]),
                    ..Default::default()
                })
                .is_ok()
        );
    }
}
