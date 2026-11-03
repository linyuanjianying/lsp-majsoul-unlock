package com.yefeng.majmax.hookprobe.manager

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Delivers a temporary loopback capability only to the game UID. */
class AiEndpointProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val app = checkNotNull(context)
        val gameUid = try {
            app.packageManager.getApplicationInfo(AiOverlayService.GAME, 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            throw SecurityException("Target game is not installed")
        }
        if (Binder.getCallingUid() != gameUid) {
            throw SecurityException("Only the target game may request the local AI endpoint")
        }
        // Any successful UID-checked call proves the game process is alive; refresh the
        // heartbeat that the host-lifecycle watchdog uses to tell "process death" apart
        // from a mere Activity pause/background (the trampoline briefly pauses the game
        // Activity while the process stays alive, so lifecycle alone is not enough).
        AssistLaunch.noteGameHeartbeat()
        if (method == "unlockCatalog") {
            val snapshot = UnlockCatalogStore.repo(app).current()
            return Bundle().apply {
                putString("manifest", snapshot.manifest)
                putByteArray("payload", snapshot.payload.toByteArray(Charsets.UTF_8))
            }
        }
        if (method == "catalogApplied") {
            CatalogUpdates.applied(app, extras?.getLong("version", 0) ?: 0, extras?.getString("sha256") ?: "")
            return Bundle.EMPTY
        }
        if (method == "hookDiagnostics") {
            val payload = extras?.getString("records") ?: throw IllegalArgumentException("Missing diagnostics batch")
            val ack = DiagnosticsStore.acceptHookBatch(app, payload)
            return Bundle().apply { putLong("ackSeq", ack) }
        }
        if (method == "autoPoll") return Bundle().apply {
            putString("command", AiOverlayService.autoPoll(extras?.getString("acks")))
        }
        if (method == "autoStatus" || method == "autoSet") {
            if (method == "autoSet") {
                // Mode only: the service keeps running, so a later enable is instant and
                // the foreground notification still explains what is running.
                val requested = extras?.takeIf { it.containsKey("enabled") }?.getBoolean("enabled")
                    ?: throw IllegalArgumentException("Missing enabled")
                AiOverlayService.setAutoEnabled(requested)
            }
            return AssistLaunch.withDisplayMode(app, AssistLaunch.status())
        }
        if (method == "displayMode") {
            // Read-only projection of the persisted presentation mode. The game process
            // reads this so its in-game control can hide itself (while still polling)
            // when the user switched to the system-overlay surface.
            val mode = AssistantDisplayModeSettings.read(app)
            return Bundle().apply {
                putString("mode", mode.wire)
                putBoolean("overlay", AssistantDisplayModeSettings.overlayActive(app))
            }
        }
        if (method == "hintSnapshot") return AiOverlayService.hintSnapshot()
        if (method == "assistFollowHost") {
            // Host-process lifecycle hand-off. The game process reports "I am starting"
            // right after its MainActivity is created; the manager answers whether it
            // could (quietly) bring up the assistant service. This never opens a
            // trampoline activity and never flips the unattended opt-in on its own.
            val state = AssistLaunch.followHost(app)
            return AssistLaunch.withDisplayMode(app, AssistLaunch.status().apply { putString("state", state) })
        }
        if (method == "assistLaunch") return AssistLaunch.withDisplayMode(app, AssistLaunch.status().apply {
            putString("state", AssistLaunch.RESTRICTED)
            putString("token", AssistLaunch.issueToken())
        })
        if (method == "assistStart") {
            // Runs in the manager process with the manager's identity, so this is the
            // only legal place to touch the manager's foreground service.
            val state = AssistLaunch.start(app)
            return AssistLaunch.withDisplayMode(app, AssistLaunch.status().apply {
                putString("state", state)
                if (state == AssistLaunch.RESTRICTED || state == AssistLaunch.FORBIDDEN) {
                    // The platform refused a background foreground-service start. Hand
                    // back a single-use token so the game can open the translucent
                    // trampoline activity, where a visible activity makes the start legal.
                    putString("token", AssistLaunch.issueToken())
                }
            })
        }
        if (method != "endpoint") return null
        val endpoint = AiOverlayService.currentEndpoint()
        return Bundle().apply {
            putInt("port", endpoint?.port ?: 0)
            endpoint?.let { putByteArray("token", it.token) }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = 0
}

/**
 * Starts, observes and switches the manager's foreground analysis service for the
 * in-game control surface.
 *
 * Every call arrives from the hooked game, which lives in another UID and process,
 * through [AiEndpointProvider]; the code here therefore always runs in the manager
 * process with the manager's own identity.
 *
 * Android 12+ forbids a background app from starting a foreground service, and a
 * cross-UID ContentProvider call does not make this app visible. Two documented,
 * non-bypassing paths are used in order:
 *
 *  1. Start directly. This is the quiet path and succeeds when the manager is user
 *     visible, holds a `SYSTEM_ALERT_WINDOW` app-op, or is temporarily allow-listed.
 *  2. If the platform refuses (`ForegroundServiceStartNotAllowedException`, which is
 *     an `IllegalStateException`), a single-use token is issued and the game opens
 *     `GameTopControl.AssistLaunchActivity` — a translucent activity of this app.
 *     A visible activity of the app is an allowed exemption, so the start from there
 *     is legal. No platform restriction is disabled or routed around.
 *
 * All methods are idempotent, rate limited and safe to call from concurrent binder
 * threads: the game polls for status while the user taps the menu.
 */
object AssistLaunch {
    /** Result codes shared with the in-game control (see GameTopControl.java). */
    const val RUNNING = "running"
    const val STARTED = "started"
    const val RESTRICTED = "restricted"
    const val FORBIDDEN = "forbidden"
    const val UNAVAILABLE = "unavailable"

    /** Mirrors AiOverlayService.MODE_PREFS / MODE_ENABLED, which stay private there. */
    private const val PREFS = "unattended"
    private const val KEY_ENABLED = "enabled"

    /** A trampoline token is short lived and single use; it only guards the activity. */
    private const val TOKEN_TTL_MS = 20_000L
    private const val START_COOLDOWN_MS = 2_000L
    private const val START_CONFIRM_TIMEOUT_MS = 1_500L
    private val CONFIRM_DELAYS_MS = longArrayOf(250L, 700L, 1_500L, 3_000L)

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var token: String? = null
    private var tokenAt = 0L
    private var lastStartAt = 0L

    /**
     * Host-process liveness signal. The game process calls the provider on a fixed
     * cadence (endpoint discovery, command polling, status polling, hint snapshots and
     * the diagnostics flusher all hit this provider), so the timestamp here advances
     * as long as the game process is alive — and stops the moment it dies. A transient
     * Activity pause (including the trampoline hand-off) never stops this cadence
     * because the process keeps running, which is exactly the distinction the
     * lifecycle requirement depends on.
     */
    @Volatile private var lastGameHeartbeatAt = 0L

    /** Weak-ish app context captured on the first host-follow; used only by the watchdog. */
    @Volatile private var appContext: Context? = null

    /** A game that stops beating is considered dead after this idle window. */
    private const val GAME_DEATH_TIMEOUT_MS = 30_000L
    private const val WATCHDOG_INTERVAL_MS = 5_000L
    private val watchdogStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val watchdogTick = object : Runnable {
        override fun run() {
            val ctx = appContext
            val beat = lastGameHeartbeatAt
            if (ctx != null && beat != 0L && AiOverlayService.isAlive()) {
                val idle = SystemClock.elapsedRealtime() - beat
                if (idle > GAME_DEATH_TIMEOUT_MS) {
                    // The game process no longer calls in: treat it as fully stopped and
                    // tear the assistant service down with it. This never fires on an
                    // Activity pause/background because those keep the process alive.
                    runCatching {
                        DiagnosticsStore.appendAssistant(ctx, "INFO", "assistant.service",
                            "HOST_PROCESS_GONE",
                            JSONObject().put("idleMs", idle))
                    }
                    AiOverlayService.stopFollowHost(ctx)
                }
            }
            main.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    @JvmStatic
    fun noteGameHeartbeat() {
        lastGameHeartbeatAt = SystemClock.elapsedRealtime()
    }

    private fun ensureWatchdog(context: Context) {
        appContext = context.applicationContext
        if (watchdogStarted.compareAndSet(false, true)) {
            main.post(watchdogTick)
        }
    }

    /**
     * Quiet host-follow entry point: called by the game process right after its own
     * MainActivity is created. It brings the assistant service up *without* a trampoline
     * and *without* flipping the unattended opt-in:
     *
     *  - When the user has previously persisted the unattended opt-in (KEY_ENABLED),
     *    the full [start] path runs so the saved mode is restored together with the
     *    service — the one case the requirement explicitly allows.
     *  - Otherwise the service is started in its default (analysis-only / hint) state;
     *    the unattended switch stays exactly where the user left it (off).
     *
     * Android 12+ forbids a background app from starting a foreground service, and a
     * cross-UID provider call does not make this app visible. This method therefore
     * performs a best-effort quiet start and reports RESTRICTED/FORBIDDEN when the
     * platform refuses; it never opens the trampoline activity, so the hand-off stays
     * silent. The start will succeed only while this app already holds a documented
     * exemption (visible activity, overlay app-op, temporary allow-list, etc.).
     */
    @JvmStatic
    fun followHost(context: Context): String {
        val app = context.applicationContext
        ensureWatchdog(app)
        noteGameHeartbeat()
        val saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
        if (saved) return start(app)
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (now - lastStartAt < START_COOLDOWN_MS) return STARTED
            lastStartAt = now
        }
        var started = false
        try {
            AiStatus.startBlocked = null
            AiOverlayService.start(app)
            started = true
            return STARTED
        } catch (error: RuntimeException) {
            return when (error) {
                is SecurityException -> FORBIDDEN
                is IllegalStateException -> RESTRICTED
                else -> UNAVAILABLE
            }
        } finally {
            if (!started) synchronized(lock) { lastStartAt = 0L }
        }
    }

    /**
     * Makes sure the service is running and unattended mode will be on.
     *
     * `startForegroundService` does NOT throw the Android 12+ background-start
     * exception; that refusal is raised inside the service's own `startForeground`
     * and was previously invisible here, so this method used to report `STARTED`
     * while the service actually crashed. It now waits a bounded time for the service
     * to actually reach the foreground, and reports `RESTRICTED` (with a trampoline
     * token) when the platform refused the start, instead of pretending it succeeded.
     */
    @JvmStatic
    fun start(context: Context): String {
        val app = context.applicationContext
        val current = AiOverlayService.autoEnabled()
        if (current != null) {
            if (!current) AiOverlayService.setAutoEnabled(true)
            return RUNNING
        }
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            // Repeated taps must not queue several service starts.
            if (now - lastStartAt < START_COOLDOWN_MS) return STARTED
            lastStartAt = now
        }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getBoolean(KEY_ENABLED, false)
        // The service reads this opt-in while it is being created, which removes the
        // race between "start the service" and "enable the mode".
        if (!previous) prefs.edit().putBoolean(KEY_ENABLED, true).commit()
        var started = false
        try {
            AiStatus.startBlocked = null
            AiOverlayService.start(app)
            started = true
            // Only wait for the real outcome when we are off the main thread (the
            // provider's binder thread). On the main thread — the trampoline
            // activity's onResume, where the manager is already visible and the start
            // is legal — blocking here would deadlock the service's own onCreate,
            // which also needs the main thread to run startForeground.
            if (Looper.myLooper() != Looper.getMainLooper()) {
                val outcome = awaitStart()
                if (outcome != RUNNING) {
                    // A refused start must not leave the persisted opt-in flipped on,
                    // or a later legal start would silently skip the explicit enable.
                    if (!previous) prefs.edit().putBoolean(KEY_ENABLED, false).commit()
                    synchronized(lock) { lastStartAt = 0L }
                    return outcome
                }
            }
            confirmMode(0)
            return STARTED
        } catch (error: RuntimeException) {
            return when (error) {
                // Only synchronous failures from startForegroundService itself land
                // here (e.g. a SecurityException when the app cannot hold a foreground
                // service at all). The background-start refusal is observed via
                // AiStatus.startBlocked inside awaitStart, not via this catch.
                is SecurityException -> FORBIDDEN
                is IllegalStateException -> RESTRICTED
                else -> UNAVAILABLE
            }
        } finally {
            if (!started) {
                synchronized(lock) { lastStartAt = 0L }
                if (!previous) prefs.edit().putBoolean(KEY_ENABLED, false).commit()
            }
        }
    }

    /**
     * Bounded wait for a just-started service to actually reach the foreground.
     * Returns [RUNNING] when it did, [RESTRICTED] when the platform refused the
     * background foreground-service start (observable via [AiStatus.startBlocked]),
     * or [UNAVAILABLE] when the service never appeared within the window.
     */
    private fun awaitStart(timeoutMs: Long = START_CONFIRM_TIMEOUT_MS): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (AiOverlayService.isAlive()) return RUNNING
            if (AiStatus.startBlocked != null) return RESTRICTED
            Thread.sleep(40L)
        }
        return UNAVAILABLE
    }

    /** Read-only projection of the service and unattended state; no side effects. */
    @JvmStatic
    fun status(): Bundle {
        val enabled = AiOverlayService.autoEnabled()
        val state = AiStatus.mutable.value
        return Bundle().apply {
            putBoolean("available", enabled != null)
            putBoolean("enabled", enabled == true)
            // A live service that refuses mode changes is running a model self-test.
            putBoolean("demo", enabled == null && state.running)
            putBoolean("connected", enabled != null && state.connected)
            putString("message", state.message)
        }
    }

    /**
     * Appends the persisted presentation mode to a status bundle for the game process.
     * Kept separate from [status] because the mode read needs an app [Context], which
     * the provider always has but [status] itself does not hold a stable reference to.
     */
    @JvmStatic
    fun withDisplayMode(context: Context, bundle: Bundle): Bundle {
        val mode = AssistantDisplayModeSettings.read(context)
        bundle.putString("displayMode", mode.wire)
        bundle.putBoolean("overlayMode", AssistantDisplayModeSettings.overlayActive(context))
        return bundle
    }

    /**
     * Entry point used only by the token-guarded trampoline activity's onResume. The
     * manager is visibly foregrounded there, so the foreground-service start is legal;
     * unlike [start] this does not block (it runs on the main thread, where blocking
     * would deadlock the service's own onCreate), and it records the actual result so
     * the in-game control can observe it through [status].
     */
    @JvmStatic fun isForegroundReady(): Boolean = AiOverlayService.isAlive()
    @JvmStatic fun isStartBlocked(): Boolean = AiStatus.startBlocked != null

    @JvmStatic
    fun startFromForeground(context: Context): String {
        val result = start(context.applicationContext)
        runCatching {
            DiagnosticsStore.appendAssistant(context.applicationContext, "INFO",
                "assistant.service", "TRAMPOLINE_START",
                org.json.JSONObject().put("result", result))
        }
        return result
    }

    @JvmStatic
    fun issueToken(): String = synchronized(lock) {
        val fresh = UUID.randomUUID().toString()
        token = fresh
        tokenAt = SystemClock.elapsedRealtime()
        fresh
    }

    /** Consumes the token exactly once; a missing, stale or reused token is refused. */
    @JvmStatic
    fun consumeToken(candidate: String?): Boolean = synchronized(lock) {
        val expected = token
        token = null
        if (expected == null || candidate == null) return false
        if (SystemClock.elapsedRealtime() - tokenAt > TOKEN_TTL_MS) return false
        MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))
    }

    /**
     * The persisted opt-in is applied while the service is created, but a service that
     * was already alive with the mode off (or restarted by the system) needs one
     * explicit switch. Retries are bounded and stop as soon as the mode is on.
     */
    private fun confirmMode(attempt: Int) {
        if (attempt >= CONFIRM_DELAYS_MS.size) return
        main.postDelayed({
            when (AiOverlayService.autoEnabled()) {
                true -> Unit
                false -> AiOverlayService.setAutoEnabled(true)
                null -> confirmMode(attempt + 1)
            }
        }, CONFIRM_DELAYS_MS[attempt])
    }
}

internal object DiagnosticsAccess {
    private val uri = Uri.parse("content://com.yefeng.majmax.hookprobe.ai/capture")
    fun grantToGame(context: android.content.Context) {
        runCatching { context.grantUriPermission(AiOverlayService.GAME, uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
