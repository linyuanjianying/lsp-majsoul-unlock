package com.yefeng.majmax.hookprobe.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.yefeng.majmax.hookprobe.helper.InGameLocalizer
import org.json.JSONObject
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class AiServiceStatus(val running: Boolean = false, val connected: Boolean = false,
    val message: String = "助手未开启")
internal data class CaptureEndpoint(val port: Int, val token: ByteArray)

object AiStatus {
    internal val mutable = MutableStateFlow(AiServiceStatus())
    val state = mutable.asStateFlow()

    /**
     * Set when the platform refuses a background foreground-service start
     * (`ForegroundServiceStartNotAllowedException` thrown inside the service's own
     * `onCreate -> startForeground`), i.e. after `startForegroundService` has already
     * returned without error. `AssistLaunch` reads this to tell the game that the
     * start was refused so it can open the visible trampoline activity instead of
     * waiting on a service that will never appear. Reset once a legal start succeeds.
     */
    @Volatile internal var startBlocked: String? = null
}

class AiOverlayService : Service() {
    companion object {
        const val GAME = "com.soulgamechst.majsoul"
        private const val LIVE = "local-ai.LIVE"
        private const val DEMO = "local-ai.DEMO"
        private const val STOP = "local-ai.STOP"
        private const val MODE_PREFS = "unattended"
        private const val MODE_ENABLED = "enabled"
        private val ENDPOINT_URI = Uri.parse("content://com.yefeng.majmax.hookprobe.ai/capture")
        @Volatile private var advertisedEndpoint: CaptureEndpoint? = null
        @Volatile private var instance: AiOverlayService? = null
        internal fun autoPoll(acks: String?): String? = instance?.pollAuto(acks)
        @Volatile private var hintResult = ""
        @Volatile private var fullResult = ""
        @Volatile private var hintCoords = ""
        @Volatile private var hintSourceW = 0
        @Volatile private var hintSourceH = 0
        @Volatile private var hintButtons = ""
        @Volatile private var hintButtonSourceW = 0
        @Volatile private var hintButtonSourceH = 0
        internal fun hintSnapshot(): android.os.Bundle = android.os.Bundle().apply {
            val service = instance
            // Hints are owned by exactly one surface, decided by the service's own
            // onCreate snapshot. In overlay mode the system tile-badge overlay renders
            // them, so this in-game snapshot reports "not running" and the in-game layer
            // (when a game process built one) clears itself. A display error never
            // switches this to hook: the overlay selection stands and reports the error.
            val run = AssistantDisplayRun.current
            val overlayMode = run?.mode == AssistantDisplayMode.OVERLAY
            val running = service?.let { svc ->
                svc.alive.get() && !svc.demoMode.get() && !overlayMode
            } == true
            putString("runMode", run?.mode?.wire ?: "")
            if (run?.overlayError != null) putString("displayError", run.overlayError)
            putBoolean("overlayMode", overlayMode)
            putBoolean("running", running)
            putBoolean("recommendationPanel", service?.let { RecommendationPanelSettings.read(it) } ?: false)
            if (running) {
                putString("result", hintResult)
                putString("coords", hintCoords)
                putInt("sourceW", hintSourceW)
                putInt("sourceH", hintSourceH)
                putString("buttons", hintButtons)
                putInt("buttonSourceW", hintButtonSourceW)
                putInt("buttonSourceH", hintButtonSourceH)
            }
        }
        /**
         * Reduces a full engine result to the fields the in-game hint layer and the
         * tile-badge overlay actually render. The floating window needs the *full*
         * result (`analysis`, `round`, `players`, `message`) but those are large and
         * useless to the badge overlay, so this trim is what [hintResult] stores while
         * [fullResult] keeps the untouched object. Pure and testable: a trim must drop
         * `analysis`/`message`/`round`/`players` and keep `status`/`canAct`/
         * `inputPending`/`hand`/`recommendations`/`legalOperations`.
         */
        internal fun trimHint(result: JSONObject): JSONObject = JSONObject()
            .put("status", result.optString("status"))
            .put("canAct", result.optBoolean("canAct"))
            .put("inputPending", result.optBoolean("inputPending"))
            .put("hand", result.optJSONArray("hand"))
            .put("recommendations", result.optJSONArray("recommendations"))
            .put("legalOperations", result.optJSONArray("legalOperations"))
            .put("recommendationPanel", RecommendationPanelOverlay.dataFor(result))

        fun toggleAuto() { instance?.auto?.toggle() }

        /**
         * The auto-rematch opt-in as the user can see it right now: the persisted value
         * plus whether this session may actually act on it. The stored flag always
         * survives (it is a setting, not a session state); only *active* is gated by
         * demo mode, exactly like the heartbeat in [autoRematchWanted].
         */
        private fun rematchState(): RematchState {
            val service = instance
            val live = service != null && service.alive.get()
            val demo = service != null && service.demoMode.get()
            val enabled = service?.let { AutoRematchSettings.read(it) } ?: AutoRematchState.mutable.value
            val unattended = service != null && live && !demo && service.auto.isEnabled()
            return RematchState(enabled = enabled, unattended = unattended, demo = demo)
        }

        /**
         * Persists the auto-rematch opt-in without touching unattended mode, the service
         * lifetime or the display mode. The next heartbeat carries the new value, so a
         * revocation also cancels the adapter's pending settlement action.
         */
        internal fun setRematchEnabled(enabled: Boolean): Boolean? {
            val service = instance
            val context = service ?: return null
            AutoRematchSettings.save(context, enabled)
            DiagnosticsStore.appendAssistant(context, "INFO", "assistant.rematch", "OPT_IN",
                JSONObject().put("enabled", enabled))
            return enabled
        }

        /**
         * Settings-page entry point. Works whether or not the service runs: the opt-in is
         * only a preference, and the game reads it through the heartbeat (or through the
         * status reply while the service is down).
         */
        internal fun publishRematch(context: Context, enabled: Boolean) {
            AutoRematchSettings.save(context, enabled)
            DiagnosticsStore.appendAssistant(context, "INFO", "assistant.rematch", "OPT_IN",
                JSONObject().put("enabled", enabled))
        }

        internal fun setAutoEnabled(enabled: Boolean): Boolean? {
            val service = instance ?: return null
            if (!service.alive.get() || service.demoMode.get()) return null
            service.auto.setEnabled(enabled)
            return service.auto.isEnabled()
        }
        internal fun autoEnabled(): Boolean? = instance?.takeIf { it.alive.get() && !it.demoMode.get() }
            ?.auto?.isEnabled()

        /**
         * True only once the service reached onCreate AND its startForeground call
         * completed without the platform refusing the background start. This is the
         * real "start succeeded" signal; `alive` alone is true from the first line of
         * onCreate, before startForeground has even run.
         */
        internal fun isAlive(): Boolean = instance?.foregroundReady?.get() == true

        /** True once this process holds a live service instance (even before foreground). */
        internal fun hasInstance(): Boolean = instance != null

        internal fun currentEndpoint(): CaptureEndpoint? = advertisedEndpoint?.let {
            CaptureEndpoint(it.port, it.token.copyOf())
        }

        fun start(context: Context, demo: Boolean = false) {
            ContextCompat.startForegroundService(context,
                Intent(context, AiOverlayService::class.java).setAction(if (demo) DEMO else LIVE))
        }
        fun stop(context: Context) {
            context.getSharedPreferences(MODE_PREFS, MODE_PRIVATE).edit().putBoolean(MODE_ENABLED, false).commit()
            instance?.auto?.pause("助手已停止，无人值守模式已关闭")
            context.stopService(Intent(context, AiOverlayService::class.java))
        }

        /**
         * Tear the service down because the host game process fully stopped, without
         * erasing the user's persisted unattended opt-in. Keeping the saved flag lets
         * the next host launch restore the mode it was left in (the "already saved
         * enable" case); only [stop] — an explicit user action — clears that flag.
         */
        fun stopFollowHost(context: Context) {
            context.stopService(Intent(context, AiOverlayService::class.java))
        }
    }

    /**
     * One queued unit of work. There is deliberately no display-mode stamp: the mode cannot
     * change while this service instance runs, so a result computed here is always for the
     * surface this run owns.
     */
    private data class Packet(val epoch: Long, val revision: Long, val connection: Long = 0,
        val kind: Int, val bytes: ByteArray = byteArrayOf(), val message: String = "",
        val source: Long = 0, val sequence: Long = 0)
    private val auto by lazy { AutoDiscardController(log = { message ->
        val parts = message.split(' ', limit = 2)
        DiagnosticsStore.appendAssistant(this, "INFO", "assistant.autoplay", parts[0].removeSuffix(":"),
            JSONObject().put("reason", parts.getOrNull(1) ?: ""))
    }, delayMs = { AutoDelaySettings.read(this).randomDelayMs() },
        initialEnabled = getSharedPreferences(MODE_PREFS, MODE_PRIVATE).getBoolean(MODE_ENABLED, false),
        modeChanged = { enabled ->
            getSharedPreferences(MODE_PREFS, MODE_PRIVATE).edit().putBoolean(MODE_ENABLED, enabled).commit()
        },
        // Structured path: the record carries the ACK-chain stage next to the bounded
        // rendering. The controller sends these events *only* here, so they are written
        // once; `reason` above stays the single writer for non-structured lines
        // (ENABLED / PAUSED / WAITING / HANDOVER / CLAIM_PROBE / ...).
        event = { item ->
            DiagnosticsStore.appendAutoplay(this, item.code, item.stage, item.detail, item.ackId)
        }) }
    private val recovery = UnattendedRecovery()
    private val alive = AtomicBoolean(true)
    private val foregroundReady = AtomicBoolean(false)
    private val demoMode = AtomicBoolean(false)
    private val epoch = AtomicLong(0)
    private val revision = AtomicLong(0)
    /** Identifies this service instance in the display-mode diagnostics. */
    private val runNonce = AtomicLong(0)
    /** Elapsed-realtime stamp of this instance's onCreate; guards [AssistantDisplayRun]. */
    @Volatile private var runStartedAt = 0L
    private val queue = ArrayBlockingQueue<Packet>(64)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var connected = false
    private var reader: Thread? = null
    private var worker: Thread? = null
    private var overlay: AiFloatingWindow? = null
    private var badges: TileBadgeOverlay? = null
    private var recommendationPanel: RecommendationPanelOverlay? = null
    private var previousPanelResult: String? = null
    private var previousPanelEnabled = true

    private fun updateRecommendationPanel() {
        runCatching { renderRecommendationPanel() }
            .onFailure { DiagnosticsStore.appendAssistant(this, "WARN", "assistant.service",
                "RECOMMENDATION_PANEL_UPDATE_FAILED",
                JSONObject().put("error", it.javaClass.simpleName)) }
    }

    /**
     * Same work as [updateRecommendationPanel] but throws: used where a failure is a real
     * error (the first render after the panel window is created) rather than a repaint
     * hiccup on an already working overlay.
     */
    private fun renderRecommendationPanel() {
        val panel = recommendationPanel ?: return
        val value = hintResult
        val enabled = RecommendationPanelSettings.read(this)
        // The system overlay never receives a `uiLanguage` tag, so it is told the manager's
        // current language here on every render: a switch on the manager page then reaches
        // an already-open panel (its own repaint is skipped below when the result is
        // unchanged, so telling it *before* the early return is what makes the switch
        // visible). Untagged — the in-game panel takes its language from the hint snapshot
        // and ignores this source.
        InGameLocalizer.currentTag(this)?.let { panel.setLanguageTag(it) }
        if (value == previousPanelResult && enabled == previousPanelEnabled) return
        panel.update(value.takeIf { it.isNotBlank() }?.let { JSONObject(it) }, enabled)
        previousPanelResult = value
        previousPanelEnabled = enabled
    }
    /**
     * Re-derives the manager's effective language from the stored choice and the device.
     *
     * The service is the only component that is running when no manager page is open, so it
     * is what keeps "follow system" honest after a device-language change. It only reloads
     * the shared selection (`UiLanguage.refresh` never writes preferences); every surface
     * here decides what to redraw on its own, and the Compose page recomposes from the same
     * state if it happens to be visible.
     */
    private fun refreshLanguage() {
        runCatching { UiLanguage.refresh(this) }
            .onFailure { DiagnosticsStore.appendAssistant(this, "WARN", "assistant.service",
                "UI_LANGUAGE_REFRESH_FAILED", JSONObject().put("error", it.javaClass.simpleName)) }
    }

    @Volatile private var fallbackLogged = false
    /** One diagnostics record per rematch ack state, for the lifetime of the service. */
    private val autoUiTick = object : Runnable {
        override fun run() {
            if (!alive.get()) return
            applyOverlaySurface()
            updateRecommendationPanel()
            overlay?.updateAuto(AutoDiscardState.mutable.value)
            // Pushed on the same 200 ms tick as the mode: the switch follows a change made
            // in the manager UI or in-game without waiting for the overlay to be rebuilt.
            overlay?.updateRematch(rematchState())
            if (recovery.shouldLaunch(auto.isEnabled() && !demoMode.get(), connected, SystemClock.elapsedRealtime())) {
                runCatching {
                    val launch = checkNotNull(packageManager.getLaunchIntentForPackage(GAME))
                    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    DiagnosticsStore.appendAssistant(this@AiOverlayService, "INFO", "assistant.autoplay", "GAME_RELAUNCHED")
                }.onFailure {
                    DiagnosticsStore.appendAssistant(this@AiOverlayService, "WARN", "assistant.autoplay", "GAME_RELAUNCH_FAILED")
                }
            }
            main.postDelayed(this, 200)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        auto.isEnabled() // Restore persisted opt-in only after the Service context is attached.
        // Publish the stored auto-rematch opt-in before the first overlay is built, so the
        // floating window's switch starts from the persisted value, not from "off".
        AutoRematchSettings.publish(this)
        OnnxModelStore.initialize(this)
        DiagnosticsStore.appendAssistant(this, "INFO", "assistant.service", "SERVICE_STARTED")
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("local-ai", "本地牌局助手",
            NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AiOverlayService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            startForeground(105, NotificationCompat.Builder(this, "local-ai")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("雀魂本地助手已开启")
                .setContentText("手机本地分析 · 点击返回管理 · 可随时停止")
                .setContentIntent(open).setOngoing(true).addAction(0, "停止助手", stop).build())
        } catch (error: RuntimeException) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException here when
            // the start came from the in-game control while the manager is in the
            // background. `startForegroundService` already returned normally, so this
            // is the only place the refusal becomes observable. Record it and exit
            // cleanly instead of crashing the process, so the provider stays alive to
            // hand the game a trampoline token.
            instance = null
            foregroundReady.set(false)
            AiStatus.startBlocked = error.javaClass.simpleName
            DiagnosticsStore.appendAssistant(this, "WARN", "assistant.service", "FOREGROUND_START_REFUSED",
                JSONObject().put("reason", error.javaClass.simpleName))
            stopSelf()
            return
        }
        AiStatus.startBlocked = null
        foregroundReady.set(true)
        // The badge-geometry diagnostics must be available even when the user has not
        // granted the (optional) overlay permission: the in-game badge pipeline that
        // runs in the game process reports its geometry faults through this trace, and
        // gating it behind `canDrawOverlays` left those failures invisible exactly when
        // overlay permission is off. Binding here is harmless when no overlay window is
        // ever shown.
        GeometryTrace.bind(this)
        // The single decision of this run. Everything below (which windows are created, which
        // hints are exported, what the game process is told) reads this snapshot and never the
        // stored preference again, so a settings change made later cannot rebuild a surface.
        val selection = AssistantDisplayModeSettings.read(this)
        val startedAt = SystemClock.elapsedRealtime()
        runStartedAt = startedAt
        val run = AssistantDisplayRun.start(selection, startedAt, runNonce.incrementAndGet().toString())
        DiagnosticsStore.appendAssistant(this, "INFO", "assistant.display", "RUN_MODE",
            JSONObject().put("mode", run.mode.wire)
                .put("overlayPermission", android.provider.Settings.canDrawOverlays(this))
                .put("selected", selection.wire))
        applyOverlaySurface()
        main.post(autoUiTick)
        worker = Thread({ consume() }, "majmax-ai-engine").also { it.start() }
        reader = Thread({ listen() }, "majmax-ai-reader").also { it.start() }
    }

    /**
     * Maintains the system overlay windows **of this run only**.
     *
     * The mode is the snapshot taken in [onCreate]; the stored selection is never read here,
     * so changing the setting while the service runs cannot rebuild anything. That is what
     * makes the two surfaces strictly one-or-the-other:
     *
     *  - `HOOK`: no overlay window is ever created. The service still measures the permission
     *    for its diagnostics, but the in-game control and hint layer are the single surface.
     *  - `OVERLAY`: the floating window, the tile badge and the recommendation panel are
     *    created, and the in-game surface is not (see [hintSnapshot]). When the permission is
     *    missing or any one of the three windows cannot be built, **every** window of this run
     *    is closed, the OVERLAY selection is kept, and the reason is recorded
     *    ([AssistantDisplayRun.noteOverlayError]) and logged. An error is only ever cleared on
     *    a tick where all three windows are actually up. Nothing falls back to Hook: an
     *    invisible overlay is reported, not replaced.
     *
     * Idempotent and cheap, so it can run on the 200 ms tick: it only records and clears the
     * error and replays the cached hints onto windows it created itself.
     *
     * Runs on the main thread from [onCreate], [autoUiTick] and the settings UI.
     */
    private fun applyOverlaySurface() {
        val run = AssistantDisplayRun.current ?: return
        if (run.mode != AssistantDisplayMode.OVERLAY) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            // Keep the selection, drop any window that may still exist (a revoked app-op),
            // and report the reason exactly once per run.
            releaseOverlayWindows()
            recordOverlayError("overlay_permission_missing")
            return
        }
        val hadFloating = overlay != null
        val hadBadges = badges != null
        val hadPanel = recommendationPanel != null
        // Every build below follows the same shape: construct into a local, show it, and only
        // then publish it to the field. A throw (or a window manager refusal) closes that
        // candidate, so a half-built window is never left attached and never stored.
        if (recommendationPanel == null) {
            val candidate = runCatching { RecommendationPanelOverlay(this) }.getOrNull()
            if (candidate == null) {
                failOverlayWindows("recommendation_panel_failed")
                return
            }
            recommendationPanel = candidate
            previousPanelResult = null
            previousPanelEnabled = true
        }
        if (overlay == null) {
            val candidate = runCatching {
                AiFloatingWindow(this, { stop(this) }, { auto.toggle() },
                    { enabled -> setRematchEnabled(enabled) }, { rematchState() })
            }.getOrNull()
            if (candidate == null) {
                failOverlayWindows("floating_window_failed", "LEGACY_OVERLAY_UNAVAILABLE")
                return
            }
            try {
                candidate.show()
            } catch (error: Throwable) {
                runCatching { candidate.close() }
                failOverlayWindows("floating_window_failed", "LEGACY_OVERLAY_UNAVAILABLE", error)
                return
            }
            overlay = candidate
        }
        if (badges == null) {
            val candidate = runCatching { TileBadgeOverlay(this) }.getOrNull()
            if (candidate == null) {
                failOverlayWindows("badge_overlay_failed", "BADGE_OVERLAY_UNAVAILABLE")
                return
            }
            try {
                candidate.show()
            } catch (error: Throwable) {
                runCatching { candidate.close() }
                failOverlayWindows("badge_overlay_failed", "BADGE_OVERLAY_UNAVAILABLE", error)
                return
            }
            badges = candidate
        }
        // Re-read the permission after the build: a revoked app-op makes the window add
        // throw even though `canDrawOverlays` said yes a moment earlier. Every window is
        // required — a mode whose badge or panel is missing is reported, not half-shown.
        if (!android.provider.Settings.canDrawOverlays(this) ||
            overlay == null || badges == null || recommendationPanel == null) {
            failOverlayWindows(if (!android.provider.Settings.canDrawOverlays(this))
                "overlay_permission_missing" else "badge_overlay_failed")
            return
        }
        // The panel's first render is the only place that can still throw on this run (the
        // panel adds its window on the first active result); a failure there is a real error
        // and must not be reported as a successful overlay.
        val panelFailure = runCatching { renderRecommendationPanel() }.exceptionOrNull()
        if (panelFailure != null) {
            failOverlayWindows("recommendation_panel_failed",
                "RECOMMENDATION_PANEL_UNAVAILABLE", panelFailure)
            return
        }
        // Only here is the overlay genuinely on screen: clear a previous error.
        if (AssistantDisplayRun.current?.overlayError != null) {
            AssistantDisplayRun.clearOverlayError()
            DiagnosticsStore.appendAssistant(this, "INFO", "assistant.display", "OVERLAY_RECOVERED",
                JSONObject().put("mode", run.mode.wire))
        }
        if (!hadFloating || !hadBadges || !hadPanel) {
            DiagnosticsStore.appendAssistant(this, "INFO", "assistant.display", "OVERLAY_SHOWN",
                JSONObject().put("mode", run.mode.wire).put("floating", overlay != null)
                    .put("badge", badges != null).put("panel", recommendationPanel != null)
                    .put("nonce", run.nonce))
        }
        // Push every cached hint surface onto the windows that exist, so the first overlay
        // tick restores the last live state instead of an empty window. Idempotent.
        if (!hadFloating || !hadBadges) replayHintSurface()
    }

    /**
     * Tears down every window of this run and records [reason]. Used for any failure of any
     * of the three windows: the surface is either complete or completely absent, and the
     * error is never cleared while a window is missing.
     */
    private fun failOverlayWindows(reason: String, diagnostic: String? = null, cause: Throwable? = null) {
        releaseOverlayWindows()
        recordOverlayError(reason)
        if (diagnostic != null) {
            DiagnosticsStore.appendAssistant(this, "WARN", "assistant.service", diagnostic,
                JSONObject().put("reason", reason)
                    .put("error", cause?.javaClass?.simpleName ?: ""))
        }
    }

    /** Closes every system overlay window; the display selection is untouched. */
    private fun releaseOverlayWindows() {
        recommendationPanel?.close(); recommendationPanel = null
        previousPanelResult = null
        previousPanelEnabled = true
        overlay?.close(); overlay = null
        badges?.close(); badges = null
    }

    /**
     * Records why the selected overlay is not on screen for this run. The mode stays
     * OVERLAY; only the reason is published so the status reply and the diagnostics can
     * explain the empty screen. Logged once per distinct reason.
     */
    private fun recordOverlayError(reason: String) {
        val run = AssistantDisplayRun.current ?: return
        if (run.overlayError == reason) return
        AssistantDisplayRun.noteOverlayError(reason)
        DiagnosticsStore.appendAssistant(this, "WARN", "assistant.display", "OVERLAY_UNAVAILABLE",
            JSONObject().put("mode", run.mode.wire).put("reason", reason).put("nonce", run.nonce))
    }


    /**
     * Replays the cached hint state onto whichever overlays currently exist. The full
     * engine result (with `analysis`) goes to the floating window, while the trimmed
     * hint plus the captured hand/button coordinates go to the tile-badge overlay.
     * Used both when a surface is first created ([applyOverlaySurface]) and when a
     * configuration change rebuilds the surfaces ([onConfigurationChanged]), so a
     * rotation or resize that clears the badge anchors does not leave them empty
     * waiting for a Lua re-send that its own dedup signature will never emit.
     */
    private fun replayHintSurface() {
        updateRecommendationPanel()
        val ov = overlay
        if (ov != null && fullResult.isNotBlank()) {
            runCatching { ov.update(JSONObject(fullResult)) }
                .onFailure { DiagnosticsStore.appendAssistant(this, "WARN", "assistant.service", "REPLAY_FLOATING_FAILED") }
        }
        val bd = badges
        if (bd != null) {
            if (hintCoords.isNotBlank()) runCatching {
                bd.updateHookedCoords(org.json.JSONArray(hintCoords), hintSourceW, hintSourceH)
            }.onFailure { GeometryTrace.note("REPLAY_COORDS", "reason=apply_failed") }
            // `hintButtons` may be the empty array `[]` (buttons disappeared): it is
            // not blank, so it still replays and clears any stale button label.
            if (hintButtons.isNotBlank()) runCatching {
                bd.updateHookedButtons(org.json.JSONArray(hintButtons), hintButtonSourceW, hintButtonSourceH)
            }.onFailure { GeometryTrace.note("REPLAY_BUTTONS", "reason=apply_failed") }
            if (hintResult.isNotBlank()) runCatching {
                bd.update(JSONObject(hintResult))
            }.onFailure { GeometryTrace.note("REPLAY_RESULT", "reason=apply_failed") }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stop(this); return START_NOT_STICKY }
        demoMode.set(intent?.action == DEMO)
        if (demoMode.get()) auto.pause("模型自检中，无人值守模式已关闭")
        runCatching {
            grantUriPermission(GAME, ENDPOINT_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            DiagnosticsAccess.grantToGame(this)
        }
        // A mode change has its own generation. Previously queued results may
        // never overwrite the self-test label or a new live session.
        reset(if (demoMode.get()) "正在加载本地模型自检…" else "等待游戏连接，请先开启助手再进入牌局")
        if (demoMode.get()) {
            queue.offer(Packet(epoch.get(), revision.incrementAndGet(), kind = 5))
        }
        runCatching { client?.close() }
        return START_STICKY
    }

    private fun pollAuto(acks: String?): String {
        if (acks != null && acks.contains("\"hand_coords\"")) parseHandCoords(acks)
        if (acks != null && acks.contains("\"hand_geometry\"")) parseHandGeometry(acks)
        if (acks != null && acks.contains("\"button_coords\"")) parseButtonCoords(acks)
        if (acks != null && acks.contains("rematch_")) noteRematchAcks(acks)
        val command = auto.poll(acks)
        val delay = AutoDelaySettings.read(this)
        val response = if (command != null && !demoMode.get()) JSONObject(command).put("unattended", true)
        else JSONObject().put("control", "mode").put("enabled", auto.isEnabled() && !demoMode.get())
            .put("resync", auto.needsSync()).put("nonce", SystemClock.elapsedRealtime())
        // Experimental auto-rematch opt-in. Sent on every heartbeat (action frame and
        // mode frame alike) so the game observes a revocation on the next poll. Gated
        // independently from unattended mode. A self-test session never drives
        // the live game.
        val rematch = autoRematchWanted()
        return response.put("delayMinMs", delay.minTenths * 100).put("delayMaxMs", delay.maxTenths * 100)
            .put("autoRematch", rematch).toString()
    }

    /** Opt-in AND not in demo: the only state where rematch may be true. */
    private fun autoRematchWanted(): Boolean =
        AutoRematchSettings.active(this, demoMode.get())

    /**
     * Bounded diagnostics for the experimental rematch acks. Only the adapter's fixed
     * `rematch_*` vocabulary is recorded (state plus a shortened reason) and emitted only on transitions by the Lua adapter.
     */
    private fun noteRematchAcks(acks: String) {
        runCatching {
            val rows = org.json.JSONArray(acks)
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val state = row.optString("state")
                if (!state.startsWith("rematch_")) continue
                DiagnosticsStore.appendAssistant(this, "INFO", "assistant.rematch", state,
                    JSONObject().put("reason", row.optString("reason").take(120)))
            }
        }.onFailure { GeometryTrace.note("REMATCH_ACK", "reason=decode_failed") }
    }

    private fun parseHandCoords(acks: String) {
        var sourceW = 0
        var sourceH = 0
        val coords = try {
            val rows = org.json.JSONArray(acks)
            var found: org.json.JSONArray? = null
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.optString("state") != "hand_coords") continue
                val raw = row.optString("reason")
                if (raw.isBlank()) continue
                // Wrapper object carries the source viewport; bare array is legacy.
                val obj = runCatching { org.json.JSONObject(raw) }.getOrNull()
                if (obj != null) {
                    sourceW = obj.optInt("screenW", 0)
                    sourceH = obj.optInt("screenH", 0)
                    found = obj.optJSONArray("coords")
                } else {
                    found = org.json.JSONArray(raw)
                }
            }
            found
        } catch (t: Throwable) {
            GeometryTrace.note("HOOK_COORDS", "reason=decode_failed")
            return
        }
        if (coords == null) return
        hintCoords = coords.toString().take(16_384)
        hintSourceW = sourceW
        hintSourceH = sourceH
        val sw = sourceW
        val sh = sourceH
        main.post {
            runCatching { badges?.updateHookedCoords(coords, sw, sh) }
                .onFailure { GeometryTrace.note("HOOK_COORDS", "reason=apply_failed") }
        }
    }

    private fun parseHandGeometry(acks: String) {
        try {
            val rows = org.json.JSONArray(acks)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.optString("state") != "hand_geometry") continue
                val detail = row.optString("reason")
                if (detail.isBlank()) continue
                val geometry = org.json.JSONObject(detail)
                // Record the game's screen size next to the overlay's measured size,
                // plus the captured normalized-coordinate range, so an out-of-viewport
                // capture is visible without guessing. Equal size values mean the two
                // share one viewport; unequal values mean the normalized coordinates
                // are scaled differently. This is a measurement, not an assumption.
                badges?.describeGeometry()?.let { (ow, oh) ->
                    val cams = geometry.optString("cams")
                    GeometryTrace.note(
                        "GAME_GEOMETRY",
                        "screenW=${geometry.optInt("screenW")};screenH=${geometry.optInt("screenH")};" +
                            "overlayW=$ow;overlayH=$oh;" +
                            "hand=${geometry.optInt("handpai")};views=${geometry.optInt("concealed")};" +
                            "open=${geometry.optInt("open")};baida=${geometry.optInt("baida")};" +
                            "behind=${geometry.optInt("behind")};" +
                            "minNx=${geometry.optDouble("minNx", Double.NaN)};" +
                            "maxNx=${geometry.optDouble("maxNx", Double.NaN)};" +
                            "minNy=${geometry.optDouble("minNy", Double.NaN)};" +
                            "maxNy=${geometry.optDouble("maxNy", Double.NaN)};" +
                            "ortho=${if (geometry.optBoolean("ortho")) 1 else 0};" +
                            "cam=${geometry.optString("cam")}" +
                            (if (cams.isNotBlank()) ";cams=$cams" else "")
                    )
                }
            }
        } catch (_: Throwable) {
            // A geometry record must never break command polling; it is observational.
        }
    }

    private fun parseButtonCoords(acks: String) {
        var sourceW = 0
        var sourceH = 0
        val buttons = try {
            val rows = org.json.JSONArray(acks)
            var found: org.json.JSONArray? = null
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.optString("state") != "button_coords") continue
                val raw = row.optString("reason")
                if (raw.isBlank()) continue
                val obj = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: continue
                sourceW = obj.optInt("screenW", 0)
                sourceH = obj.optInt("screenH", 0)
                found = obj.optJSONArray("buttons")
                    ?: obj.optJSONObject("buttons")?.takeIf { it.length() == 0 }?.let { org.json.JSONArray() }
            }
            found
        } catch (t: Throwable) {
            GeometryTrace.note("BUTTON_COORDS", "reason=decode_failed")
            return
        }
        if (buttons == null) return
        hintButtons = buttons.toString().take(16_384)
        hintButtonSourceW = sourceW
        hintButtonSourceH = sourceH
        val sw = sourceW
        val sh = sourceH
        main.post {
            runCatching { badges?.updateHookedButtons(buttons, sw, sh) }
                .onFailure { GeometryTrace.note("BUTTON_COORDS", "reason=apply_failed") }
        }
    }

    private fun reset(message: String) {
        auto.invalidate("连接或同步已变化，等待恢复 · 无人值守模式保持原开关状态")
        val generation = epoch.incrementAndGet()
        queue.clear()
        val ticket = revision.incrementAndGet()
        queue.offer(Packet(generation, ticket, kind = 3, message = message))
        post(JSONObject().put("status", "waiting").put("message", message), generation, ticket)
    }

    private fun listen() {
        try {
            val listener = ServerSocket()
            listener.reuseAddress = false
            listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
            server = listener
            val token = ByteArray(32).also(SecureRandom()::nextBytes)
            advertisedEndpoint = CaptureEndpoint(listener.localPort, token)
            while (alive.get()) {
                val socket = listener.accept()
                var authenticated = false
                try {
                    socket.soTimeout = 2_000
                    val input = DataInputStream(socket.inputStream)
                    val suppliedToken = ByteArray(32)
                    input.readFully(suppliedToken)
                    if (!socket.inetAddress.isLoopbackAddress ||
                        !MessageDigest.isEqual(token, suppliedToken)) {
                        continue
                    }
                    authenticated = true
                    client = socket
                    connected = true
                    DiagnosticsStore.appendAssistant(this, "INFO", "assistant.service", "GAME_HOOK_CONNECTED")
                    if (!demoMode.get()) reset("Hook 已连接，等待进入牌局或恢复完整牌局同步")
                    socket.soTimeout = 8_000
                    var sourceGeneration: Long? = null
                    while (alive.get()) {
                        val size = input.readInt()
                        require(size in 0..1_048_576)
                        val source = input.readLong()
                        val connection = input.readLong()
                        val sequence = input.readLong()
                        val kind = input.readUnsignedByte()
                        require(kind in listOf(0, 1, 2, 4))
                        val data = ByteArray(size)
                        input.readFully(data)
                        if (demoMode.get()) continue
                        if (sourceGeneration != null && sourceGeneration != source) {
                            reset("检测到消息丢失，等待恢复完整牌局同步")
                        }
                        sourceGeneration = source
                        if (kind == 4) continue
                        val packet = Packet(epoch.get(), revision.incrementAndGet(), connection, kind, data,
                            source = source, sequence = sequence)
                        if (!queue.offer(packet)) {
                            reset("分析未跟上牌局，等待恢复完整牌局同步")
                            break
                        }
                    }
                } catch (_: Exception) {
                    // No raw frames, credentials or account identifiers are logged.
                } finally {
                    runCatching { socket.close() }
                    if (authenticated) {
                        client = null
                        connected = false
                        DiagnosticsStore.appendAssistant(this, "WARN", "assistant.service", "GAME_HOOK_DISCONNECTED")
                        if (alive.get() && !demoMode.get()) reset("游戏连接已断开，等待重新同步")
                    }
                }
            }
        } catch (_: Exception) {
            if (alive.get()) reset("采集服务未启动，请确认游戏已安装并重新开启助手")
        } finally {
            advertisedEndpoint = null
        }
    }

    private fun consume() {
        var last = JSONObject().put("status", "waiting").put("message", "等待牌局")
        val sources = HashMap<Long, Pair<Long, Long>>()
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            AiNative.reset()
            OnnxModelStore.prepareSelected(this)
            AiNative.configurePolicy(OnnxModelStore.enabledMask(this))
            while (alive.get()) {
                val packet = queue.take()
                if (packet.epoch != epoch.get()) continue
                if (packet.kind == 3) sources.clear()
                else if (packet.kind in 0..2) sources[packet.connection] = packet.source to packet.sequence
                val result = when (packet.kind) {
                    3 -> { AiNative.reset(); JSONObject().put("status", "waiting").put("message", packet.message).toString() }
                    5 -> AiNative.selfTest()
                    else -> AiNative.frame(packet.connection, packet.kind, packet.bytes)
                }
                if (packet.epoch != epoch.get()) continue
                if (result != null) {
                    last = JSONObject(result)
                    val active = last.optString("connection").toLongOrNull()
                    sources[active]?.let { (generation, sequence) ->
                        last.put("sourceGeneration", generation).put("sourceSequence", sequence)
                    }
                    auto.observe(last)
                    if (last.optBoolean("modelFallback") && !fallbackLogged) {
                        fallbackLogged = true
                        DiagnosticsStore.appendAssistant(this, "ERROR", "assistant.model", "MODEL_RUNTIME_FALLBACK")
                    }
                }
                // Process every event, but present only the latest state after
                // a burst; stale inference is never posted over newer input.
                if (queue.isEmpty()) post(last, packet.epoch, packet.revision)
            }
        } catch (_: InterruptedException) {
            // Normal service shutdown.
        } catch (_: Throwable) {
            auto.invalidate("本地引擎异常，等待恢复")
            val failed = JSONObject().put("status", "error").put("message", "本地引擎加载失败，请停止助手并重启管理应用")
            post(failed, epoch.get(), revision.get())
        }
    }

    private fun post(result: JSONObject, generation: Long, ticket: Long) {
        main.post {
            if (!alive.get() || epoch.get() != generation || revision.get() != ticket) return@post
            // Cache both the trimmed hint (badges / in-game layer) and the full result
            // (floating window) so a configuration change or a reconnect can replay them
            // without waiting for the next poll. Only this run's own surfaces are fed.
            hintResult = trimHint(result).toString()
            fullResult = result.toString()
            overlay?.update(result)
            badges?.update(result)
            updateRecommendationPanel()
            AiStatus.mutable.value = AiServiceStatus(true, connected, result.optString("message"))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A new device language (or a restored explicit choice) must be re-derived here:
        // this runs only while no manager page is open, so nothing else would notice, and
        // the overlay windows re-read the language when they are rebuilt just below.
        refreshLanguage()
        overlay?.configurationChanged()
        badges?.configurationChanged()
        recommendationPanel?.close()
        previousPanelResult = null
        // A rotation/resize clears every captured badge anchor; the Lua side will not
        // re-send the same coordinates (its dedup signature is unchanged), so replay
        // the cached coords/buttons/result here to restore the hints immediately.
        replayHintSurface()
    }

    override fun onDestroy() {
        alive.set(false)
        auto.invalidate("助手服务中断，等待恢复")
        instance = null
        epoch.incrementAndGet()
        advertisedEndpoint = null
        hintResult = ""
        fullResult = ""
        hintCoords = ""
        hintSourceW = 0
        hintSourceH = 0
        hintButtons = ""
        hintButtonSourceW = 0
        hintButtonSourceH = 0
        runCatching { client?.close() }
        runCatching { server?.close() }
        reader?.interrupt()
        worker?.interrupt()
        queue.clear()
        main.removeCallbacksAndMessages(null)
        // The run ends with the service: the snapshot is dropped (the stored selection is
        // untouched), and with it every window this run created. A game process that is
        // still alive keeps the mode it was started with; it only re-reads on its own
        // restart, and the next service start snapshots the (possibly new) selection.
        AssistantDisplayRun.finish(runStartedAt)
        badges?.close()
        recommendationPanel?.close(); recommendationPanel = null
        overlay?.close()
        runCatching { revokeUriPermission(ENDPOINT_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        AiStatus.mutable.value = AiServiceStatus()
        DiagnosticsStore.appendAssistant(this, "INFO", "assistant.service", "SERVICE_STOPPED")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
