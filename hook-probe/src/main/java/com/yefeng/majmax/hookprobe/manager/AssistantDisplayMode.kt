package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONObject

/**
 * How the assistant's hint surface and unattended control are presented.
 *
 * The two surfaces — the system overlays ([AiFloatingWindow] + the system-window
 * [TileBadgeOverlay]) and the in-game controls ([GameTopControl] + [GameHintLayer],
 * which render as children of the game's own decor view and therefore never need the
 * overlay permission) — are mutually exclusive. Showing both at once would duplicate
 * every hint and control, so exactly one is active at a time.
 *
 * [HOOK] is the default and needs no permission; [OVERLAY] additionally requires
 * `Settings.canDrawOverlays` before anything is shown. The preference is read in the
 * manager process (service + UI) and broadcast to the game process through
 * [AssistLaunch.status], so the in-game control can hide itself and keep polling.
 *
 * The preference alone cannot keep the surfaces apart: they live in two processes driven
 * by two independent polling loops (the service's 200 ms tick, the game control's 1 s/3 s
 * status poll), and each loop only applies a switch on its own next pass. Every switch
 * therefore also bumps a *generation* and revokes the current *owner*; a surface that
 * paints has to hold the lease for the generation it painted for (see
 * [DisplayModeOwner] / [DisplayModeLeaseLocal] / [DisplayModeLease]).
 */
internal enum class AssistantDisplayMode(val wire: String) {
    /** In-game decor-view controls and hints; no overlay permission needed (default). */
    HOOK("hook"),

    /** System floating window plus system tile-badge overlay; needs overlay permission. */
    OVERLAY("overlay");

    companion object {
        fun fromWire(value: String?): AssistantDisplayMode = when (value) {
            OVERLAY.wire -> OVERLAY
            else -> HOOK
        }
    }
}

/**
 * Owner of the single presentation lease. The values are part of the provider wire
 * (`owner` field of every status reply), so they are stable integers, not enum ordinals.
 */
internal object DisplayModeOwner {
    const val NONE = 0
    const val OVERLAY = 1
    const val HOOK = 2
}

/**
 * Action of the local broadcast sent whenever the selection changes. The two processes
 * cannot call each other's in-memory state; the broadcast is what tells the game's lease
 * mirror to re-read the mode instead of waiting out its idle poll, and what tells the
 * service to rebuild its overlay immediately.
 */
const val DISPLAY_MODE_CHANGE_ACTION = "com.yefeng.majmax.hookprobe.DISPLAY_MODE_CHANGED"

/**
 * Single source of truth for the presentation mode, shared by the manager UI, the
 * foreground service and (via the provider) the game process. The preference lives in
 * its own file so it never collides with the unrelated `unattended` opt-in, model or
 * delay settings.
 */
internal object AssistantDisplayModeSettings {
    private const val PREFS = "assistant-display"
    private const val KEY_MODE = "mode"

    /**
     * How long an owner keeps the surface without renewing it. Both loops renew well
     * inside this window (200 ms tick / 3 s idle poll), so a lapsed lease means real
     * silence: a frozen process, a failing provider call, or a crash.
     */
    internal const val LEASE_MS = 10_000L

    private const val KEY_GENERATION = "displayGeneration"

    private val lock = Any()
    /**
     * Process-independent generation: one persisted counter, monotonically increasing.
     *
     * A manager restart (process death, app update, service restart) resumes the stored
     * value, so the game — which drops replies stamped below the newest generation it
     * applied — keeps accepting them. `commit()` is used on every write: an `apply()`
     * lost to an immediate process death would hand out a generation the game already
     * rejected, and the game would then sit behind a stale claim.
     */
    private var generation = 0
    /** Generation whose overlay surface failed to build, or -1. */
    private var failedGeneration = -1

    /**
     * Loads the persisted counter (0 on a first run). Called before the first reply is
     * built, by [save] and by [noteOverlayFailed].
     */
    internal fun ensureLoaded(context: Context) {
        if (synchronized(lock) { generation > 0 }) return
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_GENERATION, 0)
        synchronized(lock) {
            if (generation == 0) generation = resumeGeneration(stored)
        }
    }

    /** The counter to continue from; never negative, so ids stay usable as "newer than". */
    internal fun resumeGeneration(stored: Int): Int = stored.coerceAtLeast(0)

    /**
     * Advances the generation and persists it **before** the value is used anywhere.
     * `commit()` on purpose: the game's monotonic rule depends on the stored value
     * outliving this process. The counter saturates instead of wrapping into negative ids.
     */
    private fun nextGenerationLocked(context: Context): Int {
        if (generation < Int.MAX_VALUE) generation++
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_GENERATION, generation).commit()
        return generation
    }

    fun read(context: Context): AssistantDisplayMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, AssistantDisplayMode.HOOK.wire)
        return AssistantDisplayMode.fromWire(raw)
    }

    /**
     * Persists the choice, starts a new mode generation (in-flight replies for the previous
     * mode are refused from now on), hands the surface back to no one, tells the other
     * process and, last, lets the caller rebuild its own surface. [applyLocally] runs after
     * the broadcast is queued so the local rebuild and the remote wake-up are ordered the
     * same way as two independent polls would be.
     */
    fun save(context: Context, mode: AssistantDisplayMode, applyLocally: (() -> Unit)? = null) {
        ensureLoaded(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.wire).apply()
        val current = synchronized(lock) {
            failedGeneration = -1
            nextGenerationLocked(context)
        }
        // Overlay selected: the game hides first and acknowledges, and only then may the
        // overlay be shown (see [DisplayModeLeaseLocal.mayShowOverlay]). Selecting hook
        // instead publishes "not selected" right away, which is what lets the game paint.
        DisplayModeLeaseLocal.setOverlaySelected(
            mode == AssistantDisplayMode.OVERLAY, current)
        DiagnosticsStore.appendAssistant(context, "INFO", "assistant.display", "MODE_CHANGED",
            JSONObject().put("mode", mode.wire).put("generation", current)
                .put("overlayPermission", android.provider.Settings.canDrawOverlays(context)))
        // Best effort: a background process may refuse the broadcast, in which case the
        // other side simply notices the change on its next poll, as it always did.
        runCatching {
            context.applicationContext.sendBroadcast(
                Intent(DISPLAY_MODE_CHANGE_ACTION).setPackage(context.packageName))
        }
        applyLocally?.invoke()
    }

    /**
     * Pure decision: which surface actually renders, given the persisted mode and
     * whether the overlay permission is currently held. The overlay surface can only
     * be shown with the permission, so a selected-but-unpermitted overlay mode
     * resolves back to [AssistantDisplayMode.HOOK] (never "neither surface").
     */
    fun resolve(selected: AssistantDisplayMode, hasOverlayPermission: Boolean): AssistantDisplayMode =
        if (selected == AssistantDisplayMode.OVERLAY && hasOverlayPermission) AssistantDisplayMode.OVERLAY
        else AssistantDisplayMode.HOOK

    /**
     * True only when the overlay mode is selected *and* the app actually holds the
     * overlay permission. Without the permission the system overlay can never be
     * drawn, so this must fall back to [AssistantDisplayMode.HOOK] rather than leave
     * the user with neither surface. Never called to force an overlay to appear.
     */
    fun overlayActive(context: Context): Boolean =
        resolve(read(context), android.provider.Settings.canDrawOverlays(context)) == AssistantDisplayMode.OVERLAY

    /** Mode generation; changes on every [save] and every failed overlay build. */
    internal fun generation(): Int = synchronized(lock) { generation }

    /**
     * True while the overlay surface for the current generation is known to be missing
     * even though overlay mode is selected. Lets the status reply tell the game to take
     * the surface back instead of leaving the user with neither.
     */
    internal fun overlayUnavailable(): Boolean = synchronized(lock) {
        failedGeneration >= 0 && failedGeneration == generation
    }

    /** Whether the overlay failed to build for [stamp]; used when publishing the lease. */
    internal fun overlayUnavailableFor(stamp: Int): Boolean = synchronized(lock) {
        failedGeneration == stamp
    }

    /**
     * Records that the overlay surface could not be created for the current mode (the
     * permission was revoked while the mode stayed OVERLAY, another app exhausted the
     * overlay budget, the window manager refused the type). The lease is released and a
     * new generation starts, so both surfaces re-evaluate: the game takes the surface and
     * the service retries the overlay on a later tick. Idempotent for one generation.
     */
    internal fun noteOverlayFailed(context: Context): Boolean {
        ensureLoaded(context)
        val current = synchronized(lock) {
            // Idempotent for the live generation: the service calls this on every 200 ms
            // tick while the window cannot be built, and bumping each time would make the
            // game reject the replies as an endless stream of switches.
            if (failedGeneration >= 0 && failedGeneration == generation) return true
            // The marker belongs to the generation that starts *now*. Stamping the old one
            // before incrementing left `overlayUnavailable()` permanently false, so the game
            // was never told that overlay mode is selected but invisible.
            failedGeneration = nextGenerationLocked(context)
            failedGeneration
        }
        DisplayModeLeaseLocal.drop(current)
        return true
    }

    /**
     * The mode a surface should apply right now: the permission-checked preference,
     * further reduced to [AssistantDisplayMode.HOOK] while the overlay is known to be
     * unusable. This is what the service rebuilds its windows from, so a revoked
     * permission does not leave a "selected but invisible" overlay mode behind.
     */
    internal fun effective(context: Context): AssistantDisplayMode {
        val resolved = resolve(read(context), android.provider.Settings.canDrawOverlays(context))
        return if (resolved == AssistantDisplayMode.OVERLAY && overlayUnavailable()) AssistantDisplayMode.HOOK
        else resolved
    }

    /**
     * True for a reply that was computed before the last generation change. Callers
     * capture [generation] when they start the work that will be painted and pass it
     * back here; a stale reply is dropped instead of painted over the current surface.
     */
    internal fun stale(stamp: Int): Boolean = synchronized(lock) {
        // Only an *older* stamp is stale. A stamp newer than this process knows can only
        // come from the persisted generation and must not be dropped, which is the other
        // half of the "restart must not invalidate every reply" fix.
        stamp > 0 && stamp < generation
    }

    /**
     * Whether the overlay may be shown for the current generation: overlay is selected, the
     * window is usable, and the game acknowledged that it hid for this generation.
     */
    internal fun overlayMayPaint(context: Context): Boolean =
        DisplayModeLeaseLocal.mayShowOverlay(generation())

    /** True when this process wants the system overlay on screen right now. */
    internal fun wantsOverlay(context: Context): Boolean = effective(context) == AssistantDisplayMode.OVERLAY

    /**
     * Hands the surface back to nobody when the service goes away. The generation is kept so
     * a reply captured before the restart is still recognised. Nothing is published to the
     * game: the provider reports "manager down" from [AiOverlayService.isAlive] instead, and
     * the game's lease paints on that. Nothing may outlive the surface.
     */
    internal fun resetLease() {
        // The failed-generation marker is deliberately kept: the service restart that calls
        // this does not fix a revoked permission, and clearing it would let the next tick
        // report "overlay owns the surface" for a window that never appeared.
        val current = synchronized(lock) { generation }
        // The service is gone, so no window is on screen: the game is told it may paint
        // again. This is the only place that hands the surface back without an overlay
        // teardown, and it runs in the manager's onDestroy, after the windows were closed.
        DisplayModeLeaseLocal.setOverlaySelected(false, current)
    }
}
