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
            // The in-game hint layer is only meaningful in Hook mode. In overlay mode
            // the system tile-badge overlay renders the same hints, so report
            // "not running" here to make the in-game layer clear itself and stop
            // painting, without tearing down the service or its polling.
            val running = service?.let { svc ->
                svc.alive.get() && !svc.demoMode.get() &&
                    !AssistantDisplayModeSettings.overlayActive(svc)
            } == true
            putBoolean("running", running)
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

        fun toggleAuto() { instance?.auto?.toggle() }
        internal fun setAutoEnabled(enabled: Boolean): Boolean? {
            val service = instance ?: return null
            if (!service.alive.get() || service.demoMode.get()) return null
            service.auto.setEnabled(enabled)
            return service.auto.isEnabled()
        }
        internal fun autoEnabled(): Boolean? = instance?.takeIf { it.alive.get() && !it.demoMode.get() }
            ?.auto?.isEnabled()

        /**
         * Re-reads the persisted display mode and (re)builds or tears down the two
         * system overlays accordingly. Called by the settings UI right after the user
         * changes the mode while the service is running, so the switch is immediate
         * instead of waiting for a service restart. Safe to call when not running.
         */
        internal fun refreshDisplayMode() {
            instance?.let { service -> service.main.post { service.applyDisplayMode() } }
        }

        /**
         * True only once the service reached onCreate AND its startForeground call
         * completed without the platform refusing the background start. This is the
         * real "start succeeded" signal; `alive` alone is true from the first line of
         * onCreate, before startForeground has even run.
         */
        internal fun isAlive(): Boolean = instance?.foregroundReady?.get() == true

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
        }) }
    private val recovery = UnattendedRecovery()
    private val alive = AtomicBoolean(true)
    private val foregroundReady = AtomicBoolean(false)
    private val demoMode = AtomicBoolean(false)
    private val epoch = AtomicLong(0)
    private val revision = AtomicLong(0)
    private val queue = ArrayBlockingQueue<Packet>(64)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var connected = false
    private var reader: Thread? = null
    private var worker: Thread? = null
    private var overlay: AiFloatingWindow? = null
    private var badges: TileBadgeOverlay? = null
    @Volatile private var fallbackLogged = false
    /** One diagnostics record per rematch ack state, for the lifetime of the service. */
    private val autoUiTick = object : Runnable {
        override fun run() {
            if (!alive.get()) return
            applyDisplayMode()
            overlay?.updateAuto(AutoDiscardState.mutable.value)
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
        applyDisplayMode()
        main.post(autoUiTick)
        worker = Thread({ consume() }, "majmax-ai-engine").also { it.start() }
        reader = Thread({ listen() }, "majmax-ai-reader").also { it.start() }
    }

    /**
     * Builds or tears down the system overlay surfaces to match the persisted display
     * mode. In overlay mode both the floating window and the system tile-badge overlay
     * are shown (only when the app actually holds the overlay permission); in Hook mode
     * they are both removed, leaving the in-game controls and hints as the single
     * surface. This is idempotent and never force-opens the permission page.
     */
    private fun applyDisplayMode() {
        val overlayActive = AssistantDisplayModeSettings.overlayActive(this)
        if (overlayActive) {
            val needsReplay = overlay == null || badges == null
            if (overlay == null) {
                runCatching {
                    overlay = AiFloatingWindow(this, { stop(this) }, { auto.toggle() }).also { it.show() }
                }.onFailure {
                    overlay?.close(); overlay = null
                    DiagnosticsStore.appendAssistant(this, "WARN", "assistant.service", "LEGACY_OVERLAY_UNAVAILABLE")
                }
            }
            if (badges == null) {
                runCatching {
                    badges = TileBadgeOverlay(this).also { it.show() }
                }.onFailure {
                    badges?.close(); badges = null
                    DiagnosticsStore.appendAssistant(this, "WARN", "assistant.service", "BADGE_OVERLAY_UNAVAILABLE")
                }
            }
            // Push every cached hint surface onto the freshly created overlays so a
            // mode switch (or the first overlay-mode tick) restores the last live
            // state immediately, instead of showing an empty window until the next
            // poll. Idempotent when the overlays already carried the same state.
            if (needsReplay) replayHintSurface()
        } else {
            overlay?.close(); overlay = null
            badges?.close(); badges = null
        }
    }

    /**
     * Replays the cached hint state onto whichever overlays currently exist. The full
     * engine result (with `analysis`) goes to the floating window, while the trimmed
     * hint plus the captured hand/button coordinates go to the tile-badge overlay.
     * Used both when a surface is first created ([applyDisplayMode]) and when a
     * configuration change rebuilds the surfaces ([onConfigurationChanged]), so a
     * rotation or resize that clears the badge anchors does not leave them empty
     * waiting for a Lua re-send that its own dedup signature will never emit.
     */
    private fun replayHintSurface() {
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
        // by the same switches as unattended mode: the stored opt-in alone never arms
        // anything, and a self-test session must never drive the live game.
        val rematch = autoRematchWanted()
        return response.put("delayMinMs", delay.minTenths * 100).put("delayMaxMs", delay.maxTenths * 100)
            .put("autoRematch", rematch).toString()
    }

    /** Opt-in AND unattended AND not in demo: the only state where rematch may be true. */
    private fun autoRematchWanted(): Boolean =
        !demoMode.get() && auto.isEnabled() && AutoRematchSettings.read(this)

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
            // (floating window) so a later mode switch or configuration change can
            // replay them without waiting for the next poll.
            hintResult = trimHint(result).toString()
            fullResult = result.toString()
            overlay?.update(result)
            badges?.update(result)
            AiStatus.mutable.value = AiServiceStatus(true, connected, result.optString("message"))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.configurationChanged()
        badges?.configurationChanged()
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
        badges?.close()
        overlay?.close()
        runCatching { revokeUriPermission(ENDPOINT_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        AiStatus.mutable.value = AiServiceStatus()
        DiagnosticsStore.appendAssistant(this, "INFO", "assistant.service", "SERVICE_STOPPED")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
