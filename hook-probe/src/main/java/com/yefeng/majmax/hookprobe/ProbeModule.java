package com.yefeng.majmax.hookprobe;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;
import com.yefeng.majmax.hookprobe.catalog.UnlockCatalogData;
import com.yefeng.majmax.hookprobe.catalog.UnlockCatalogInstaller;
import com.yefeng.majmax.hookprobe.manager.OpponentNaming;

import io.github.libxposed.api.XposedModule;

/** Modern API 102 entry for the in-process Rust Modder. */
public final class ProbeModule extends XposedModule {
    private static final String GAME = "com.soulgamechst.majsoul";
    private static final String TAG = "MajsoulProbe";
    private static final Uri AI_ENDPOINT_URI = Uri.parse(
            "content://com.yefeng.majmax.hookprobe.ai/capture");
    private static final String[] VERSIONED_ASSETS = {
            "ui/MajsoulMaxSettings.lua", "ui/MajsoulMaxAutoDiscard.lua",
            "ui/MajsoulMaxNaming.lua"
    };

    /** Read shipped bytes from the APK, not LSPosed's asset-blind class loader. */
    private static String assetVersion(ZipFile apk) throws Exception {
        StringBuilder hashes = new StringBuilder();
        for (String name : VERSIONED_ASSETS) {
            ZipEntry entry = apk.getEntry("assets/liqi_config/" + name);
            if (entry == null) throw new IOException("Module asset is missing: " + name);
            try (InputStream input = apk.getInputStream(entry)) {
                hashes.append(UnlockCatalogData.sha256(
                        UnlockCatalogData.readBounded(input, 1024 * 1024)));
            }
        }
        return "lua7-" + UnlockCatalogData.sha256(
                hashes.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final String[] USER_ASSETS = {"settings.mod.json"};
    private static final AtomicBoolean CONFIGURED = new AtomicBoolean();
    private static final AtomicBoolean AI_ENDPOINT_POLLING = new AtomicBoolean();
    private static final java.util.concurrent.ScheduledExecutorService AI_ENDPOINT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "majmax-ai-endpoint");
                thread.setDaemon(true);
                return thread;
            });
    private static volatile int aiEndpointPort;
    private static volatile byte[] aiEndpointToken = new byte[0];
    /**
     * Minimum spacing of the opponent-naming poll. The value only changes when a user taps a
     * control, so one read per second is enough to hand an edit to the modder (the in-game Lua
     * layer then redraws the table on screen), and it keeps the 250 ms endpoint loop's binder
     * traffic unchanged.
     */
    private static final long NAMING_POLL_MS = 1_000L;
    private static long namingCheckedAt;
    /**
     * The last choice successfully handed to the modder, as the provider's wire string. Only a
     * value that actually differs is applied: `majmax_modder_update_settings` rewrites
     * `settings.mod.json`, and the poll (or a second surface) must not rewrite it once per
     * second. Volatile because the endpoint executor is the only writer and diagnostics read it.
     */
    private static volatile String appliedNaming = "";
    private boolean targetProcess;
    private static final AtomicBoolean TOP_CONTROL_REGISTERED = new AtomicBoolean();

    private static native int nativeConfigure(String configDir);
    private static native void nativeCaptureEndpoint(int port, byte[] token);
    private static native void nativeAutoCommand(String command, long generation, long connection, long sequence);
    private static native String nativeAutoResults();
    /**
     * Applies one camelCase `SettingsPatch` (only the opponent-naming fields are ever sent) to
     * the Rust modder and returns its status code: 0 = applied and persisted, 1 = the modder is
     * not configured yet, negative = rejected. The modder keeps the value in memory and writes
     * `settings.mod.json` atomically, so the next `authGame` response already uses it and the
     * in-game display refresh (which polls the same snapshot) sees the new choice.
     */
    private static native int nativeApplyNaming(byte[] patch);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        targetProcess = !param.isSystemServer() && GAME.equals(param.getProcessName());
        if (!targetProcess) return;
        HookDiagnostics.event("INFO", "hook.entry", "MODULE_LOADED",
                HookDiagnostics.fields("api", getApiVersion(), "moduleVersion", BuildConfig.VERSION_NAME));
        log(Log.INFO, TAG, "API=" + getApiVersion() + " framework="
                + getFrameworkName() + " " + getFrameworkVersion());
        try {
            System.loadLibrary("majsoulmodder");
            System.loadLibrary("majsoulprobe");
            HookDiagnostics.event("INFO", "hook.native", "NATIVE_LOADED", new JSONObject());
            log(Log.INFO, TAG, "Native Hook and Rust Modder loaded");
        } catch (Throwable error) {
            HookDiagnostics.event("ERROR", "hook.native", "NATIVE_LOAD_FAILED", new JSONObject());
            log(Log.ERROR, TAG, "Cannot load native Hook libraries", error);
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!targetProcess || !GAME.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        LspatchUnityCompat.install(this, GAME);
        try {
            Class<?> activity = param.getClassLoader().loadClass(
                    "com.soulgamechst.mahjongsoulsdk.MainActivity");
            Method onCreate = activity.getDeclaredMethod("onCreate", Bundle.class);
            hook(onCreate).intercept(chain -> {
                Object receiver = chain.getThisObject();
                if (receiver instanceof Context context) {
                    configure(context);
                    if (context.getApplicationContext() instanceof android.app.Application app
                            && TOP_CONTROL_REGISTERED.compareAndSet(false, true)) {
                        new GameTopControl().register(app);
                        HookDiagnostics.event("INFO", "hook.entry", "TOP_CONTROL_READY", new JSONObject());
                    }
                }
                return chain.proceed();
            });
            HookDiagnostics.event("INFO", "hook.entry", "ACTIVITY_HOOK_INSTALLED", new JSONObject());
            log(Log.INFO, TAG, "Game activity API 102 initialization hook installed");
        } catch (ReflectiveOperationException error) {
            HookDiagnostics.event("ERROR", "hook.entry", "ACTIVITY_HOOK_FAILED", new JSONObject());
            log(Log.ERROR, TAG, "Activity entry changed; Rust Modder cannot be configured", error);
        }
    }

    private void configure(Context gameContext) {
        if (!CONFIGURED.compareAndSet(false, true)) return;
        HookDiagnostics.start(gameContext);
        HookDiagnostics.event("INFO", "hook.entry", "CONFIG_STARTED", new JSONObject());
        try {
            File configDir = new File(gameContext.getFilesDir(), "majsoulmax-hook");
            if (!configDir.isDirectory() && !configDir.mkdirs()) {
                throw new IOException("Cannot create " + configDir);
            }
            UnlockCatalogData.Snapshot catalog = null;
            String shippedAssetVersion;
            try (ZipFile moduleApk = new ZipFile(getModuleApplicationInfo().sourceDir)) {
                shippedAssetVersion = assetVersion(moduleApk);
                File stamp = new File(configDir, ".asset-version");
                String installed = stamp.isFile()
                        ? readUtf8(stamp).trim() : "";
                if (!shippedAssetVersion.equals(installed)) {
                    for (String name : VERSIONED_ASSETS) copyAsset(moduleApk, name, configDir, true);
                    writeUtf8(stamp, shippedAssetVersion);
                }
                for (String name : USER_ASSETS) copyAsset(moduleApk, name, configDir, false);
                // A UI asset upgrade must never replace a newer downloaded unlock list.
                copyAsset(moduleApk, "max_data.yaml", configDir, false);
                try { catalog = syncUnlockCatalog(gameContext, moduleApk, configDir); }
                catch (Exception error) {
                    HookDiagnostics.event("WARN", "hook.catalog", "CATALOG_UNAVAILABLE", new JSONObject());
                }
            }
            int result = nativeConfigure(configDir.getAbsolutePath());
            if (result != 0) throw new IOException("nativeConfigure returned " + result);
            if (catalog != null) reportCatalogApplied(gameContext, configDir, catalog);
            startAiEndpointDiscovery(gameContext.getApplicationContext());
            followHostQuietly(gameContext.getApplicationContext());
            HookDiagnostics.event("INFO", "hook.entry", "CONFIGURED",
                    HookDiagnostics.fields("assetVersion", shippedAssetVersion));
            log(Log.INFO, TAG, "Rust Modder configured at " + configDir);
        } catch (Throwable error) {
            CONFIGURED.set(false);
            HookDiagnostics.event("ERROR", "hook.entry", "CONFIG_FAILED", new JSONObject());
            log(Log.ERROR, TAG, "Cannot configure Rust Modder; traffic remains unmodified", error);
        }
    }

    private static UnlockCatalogData.Snapshot syncUnlockCatalog(Context context, ZipFile apk, File directory) throws Exception {
        String manifest = catalogAsset(apk, "manifest.json", UnlockCatalogData.MAX_MANIFEST_BYTES);
        String payload = catalogAsset(apk, "unlock-data.json", UnlockCatalogData.MAX_DATA_BYTES);
        UnlockCatalogData.Snapshot chosen = UnlockCatalogData.parse(manifest, payload, BuildConfig.VERSION_CODE);
        try {
            Bundle reply = context.getContentResolver().call(AI_ENDPOINT_URI, "unlockCatalog", null, null);
            if (reply != null) {
                byte[] bytes = reply.getByteArray("payload");
                if (bytes == null || bytes.length > UnlockCatalogData.MAX_DATA_BYTES) throw new IOException("Invalid catalog payload");
                UnlockCatalogData.Snapshot downloaded = UnlockCatalogData.parse(
                        reply.getString("manifest", ""), UnlockCatalogData.utf8(bytes), BuildConfig.VERSION_CODE);
                if (downloaded.version > chosen.version || (downloaded.version == chosen.version && downloaded.sha256.equals(chosen.sha256))) {
                    chosen = downloaded;
                }
            }
        } catch (Exception ignored) {
            // Embedded modules and an unavailable manager retain their last good data.
        }
        boolean installed = UnlockCatalogInstaller.install(directory, chosen);
        HookDiagnostics.event("INFO", "hook.catalog", installed ? "CATALOG_INSTALLED" : "CATALOG_RETAINED",
                HookDiagnostics.fields("count", chosen.skinCount, "reason", "version_" + chosen.version));
        return chosen;
    }

    private static String catalogAsset(ZipFile apk, String name, int max) throws Exception {
        ZipEntry entry = apk.getEntry("assets/unlock-catalog/" + name);
        if (entry == null) throw new IOException("Missing bundled catalog");
        try (InputStream input = apk.getInputStream(entry)) {
            return UnlockCatalogData.utf8(UnlockCatalogData.readBounded(input, max));
        }
    }

    private static void reportCatalogApplied(Context context, File directory, UnlockCatalogData.Snapshot catalog) {
        try (InputStream input = new FileInputStream(new File(directory, "max_data.yaml"))) {
            String hash = UnlockCatalogData.sha256(UnlockCatalogData.readBounded(input, UnlockCatalogData.MAX_DATA_BYTES));
            if (!hash.equals(catalog.yamlSha256)) return;
            Bundle report = new Bundle();
            report.putLong("version", catalog.version); report.putString("sha256", hash);
            context.getContentResolver().call(AI_ENDPOINT_URI, "catalogApplied", null, report);
        } catch (Exception ignored) {}
    }

    /**
     * Quiet host-follow hand-off. Fired exactly once, right after the game's own
     * MainActivity is created, so the manager can bring its assistant service up in
     * step with the host. This is intentionally fire-and-forget and silent: it never
     * opens the manager UI and never blocks the game's main thread. The reply is only
     * used for diagnostics, so a refused/blocked start (Android 12+ background FGS
     * restriction, manager not installed, etc.) is logged and otherwise ignored.
     */
    private static void followHostQuietly(Context context) {
        AI_ENDPOINT_EXECUTOR.execute(() -> {
            try {
                Bundle reply = context.getContentResolver().call(
                        AI_ENDPOINT_URI, "assistFollowHost", null, null);
                String state = reply == null ? "unreachable" : reply.getString("state", "");
                HookDiagnostics.event("INFO", "hook.entry", "HOST_FOLLOW_ATTEMPTED",
                        HookDiagnostics.fields("state", state == null ? "unreachable" : state));
            } catch (Throwable ignored) {
                // Manager missing, disabled, or revoked its URI grant: stay silent.
            }
        });
    }

    private static void startAiEndpointDiscovery(Context gameContext) {
        if (!AI_ENDPOINT_POLLING.compareAndSet(false, true)) return;
        AI_ENDPOINT_EXECUTOR.scheduleWithFixedDelay(() -> {
            try {
                // The opponent-naming choice is independent of capture, so it is synced
                // first: a game with no AI endpoint still honours the shared setting.
                syncOpponentNaming(gameContext);
                Bundle endpoint = gameContext.getContentResolver().call(
                        AI_ENDPOINT_URI, "endpoint", null, null);
                int port = endpoint == null ? 0 : endpoint.getInt("port", 0);
                byte[] token = endpoint == null ? null : endpoint.getByteArray("token");
                if (port > 0 && port <= 65535 && token != null && token.length == 32) {
                    if (port != aiEndpointPort || !Arrays.equals(token, aiEndpointToken)) {
                        nativeCaptureEndpoint(port, token);
                        HookDiagnostics.event("INFO", "hook.capture", "CAPTURE_ENDPOINT_READY", new JSONObject());
                        Arrays.fill(aiEndpointToken, (byte) 0);
                        aiEndpointPort = port;
                        aiEndpointToken = token.clone();
                    }
                } else if (aiEndpointPort != 0) {
                    nativeCaptureEndpoint(0, new byte[0]);
                    HookDiagnostics.event("INFO", "hook.capture", "CAPTURE_ENDPOINT_LOST", new JSONObject());
                    Arrays.fill(aiEndpointToken, (byte) 0);
                    aiEndpointPort = 0;
                    aiEndpointToken = new byte[0];
                }
                if (aiEndpointPort != 0) {
                    try {
                        Bundle report = new Bundle();
                        report.putString("acks", nativeAutoResults());
                        Bundle reply = gameContext.getContentResolver().call(AI_ENDPOINT_URI, "autoPoll", null, report);
                        String command = reply == null ? null : reply.getString("command");
                        JSONObject json = command == null ? null : new JSONObject(command);
                        nativeAutoCommand(command, json == null ? 0 : json.optLong("sourceGeneration"),
                                json == null ? 0 : Long.parseLong(json.optString("connection", "0")),
                                json == null ? 0 : json.optLong("sourceSequence"));
                    } catch (Throwable ignored) {
                        // Command polling is independent of passive capture. A bad
                        // command must never tear down the healthy game connection.
                        nativeAutoCommand(null, 0, 0, 0);
                    }
                } else {
                    nativeAutoCommand(null, 0, 0, 0);
                }
            } catch (Throwable ignored) {
                nativeAutoCommand(null, 0, 0, 0);
                if (aiEndpointPort != 0) {
                    nativeCaptureEndpoint(0, new byte[0]);
                    HookDiagnostics.event("WARN", "hook.capture", "CAPTURE_ENDPOINT_LOST", new JSONObject());
                    Arrays.fill(aiEndpointToken, (byte) 0);
                    aiEndpointPort = 0;
                    aiEndpointToken = new byte[0];
                }
            }
        }, 0, 250, TimeUnit.MILLISECONDS);
    }

    /**
     * Hands the shared opponent-naming choice to the Rust modder.
     *
     * The choice is owned by the manager process ([com.yefeng.majmax.hookprobe.manager
     * .OpponentNamingSettings]) because both surfaces have to agree on one value: the in-game
     * control writes it through the provider, and the floating window — which runs in the
     * manager process — writes it directly. This process is the only one that holds the
     * modder, so it is also the only one that can apply the value, and it does so from the
     * endpoint loop that already runs for the whole life of the game.
     *
     * Semantics that matter here:
     *
     *  - A missing manager (no app installed, revoked URI grant, manager killed between two
     *    polls) is *not* an error: the modder keeps whatever it already has, exactly like the
     *    unlock catalog keeps its last good copy.
     *  - Only a changed value is written, so the file is not rewritten once per second.
     *  - The very first successful read is always applied, even when it says `normal`: that is
     *    what makes the manager-side store the single source of truth instead of letting a
     *    value written by another build survive forever in `settings.mod.json`.
     *  - A rejected patch (status != 0) is not remembered, so the next poll retries once the
     *    modder is configured; a rejected value can never be mistaken for an applied one.
     */
    private static void syncOpponentNaming(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (now - namingCheckedAt < NAMING_POLL_MS) return;
        namingCheckedAt = now;
        String wire;
        try {
            Bundle reply = context.getContentResolver().call(AI_ENDPOINT_URI, "namingGet", null, null);
            wire = reply == null ? null : reply.getString("naming");
        } catch (Throwable missing) {
            return;
        }
        String patch = OpponentNaming.patchForWire(wire);
        if (patch == null || wire == null || wire.equals(appliedNaming)) return;
        int status;
        try {
            status = nativeApplyNaming(patch.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable unavailable) {
            // The native library is not loaded (module disabled or wrong process); nothing to do.
            return;
        }
        if (status != 0) {
            Log.w(TAG, "Opponent naming patch rejected: status=" + status);
            return;
        }
        appliedNaming = wire;
        HookDiagnostics.event("INFO", "hook.naming", "NAMING_APPLIED",
                HookDiagnostics.fields("mode", OpponentNaming.modeOf(wire), "slots", OpponentNaming.slotsOf(wire)));
    }

    private static void copyAsset(ZipFile moduleApk, String name, File directory,
            boolean replace) throws IOException {
        File target = new File(directory, name);
        if (!replace && target.isFile()) return;
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create asset directory " + parent);
        }
        File temporary = new File(directory, name + ".tmp");
        ZipEntry entry = moduleApk.getEntry("assets/liqi_config/" + name);
        if (entry == null) throw new IOException("Module asset is missing: " + name);
        try (InputStream input = moduleApk.getInputStream(entry);
             FileOutputStream output = new FileOutputStream(temporary, false)) {
            copy(input, output);
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target);
        if (!temporary.renameTo(target)) throw new IOException("Cannot install " + target);
    }

    private static String readUtf8(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeUtf8(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static void copy(InputStream input, java.io.OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1; ) {
            output.write(buffer, 0, count);
        }
    }
}
