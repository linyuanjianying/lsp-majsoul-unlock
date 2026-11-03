package com.yefeng.majmax.hookprobe.manager

import android.content.Context

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
 * Single source of truth for the presentation mode, shared by the manager UI, the
 * foreground service and (via the provider) the game process. The preference lives in
 * its own file so it never collides with the unrelated `unattended` opt-in, model or
 * delay settings.
 */
internal object AssistantDisplayModeSettings {
    private const val PREFS = "assistant-display"
    private const val KEY_MODE = "mode"

    fun read(context: Context): AssistantDisplayMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, AssistantDisplayMode.HOOK.wire)
        return AssistantDisplayMode.fromWire(raw)
    }

    fun save(context: Context, mode: AssistantDisplayMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.wire).apply()
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
}
