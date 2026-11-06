package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live projection of the persisted auto-rematch opt-in.
 *
 * [AutoRematchSettings] is the single source of truth (one boolean in the
 * `auto-rematch` preferences file). This state mirror exists so observers that
 * must repaint *immediately* — the system floating window (refreshed every 200 ms)
 * and the in-game status reply — never wait for the next tick, and so a change made
 * from the manager UI or the in-game control reaches the other surface without a
 * service restart.
 */
internal object AutoRematchState {
    internal val mutable = MutableStateFlow(false)
    val state = mutable.asStateFlow()
}

/**
 * What one surface shows for the auto-rematch switch.
 *
 * [enabled] is the persisted opt-in (what the user tapped). [unattended] and [demo]
 * say whether this session can act on it; the footer text uses them to explain why a
 * switched-on opt-in is not doing anything yet, instead of silently showing "off".
 */
internal data class RematchState(val enabled: Boolean = false, val unattended: Boolean = false,
    val demo: Boolean = false) {
    /** Opt-in AND unattended AND not a self-test: the only state that arms the game. */
    val active: Boolean get() = enabled && unattended && !demo
}

/**
 * Persisted opt-in for the experimental auto-rematch helper.
 *
 * The flag only ever travels to the game inside a `pollAuto` heartbeat, and only
 * while unattended mode is enabled and the service is not in demo (self-test) mode,
 * so a stale `true` in preferences can never drive the game on its own. It lives in
 * its own preferences file so it cannot collide with the unrelated `unattended`
 * opt-in, the delay range, the model slots or the display mode.
 *
 * The default is off: unlike the unattended mode, this keeps a *game session* going
 * across matches, so it must be an explicit opt-in the user can revoke at any time.
 *
 * Flipping it never touches the unattended opt-in, the service lifetime or the
 * display mode: revoking only removes the `autoRematch` lease from the next
 * heartbeat, and the Lua adapter drops its pending settlement action on that poll.
 */
internal object AutoRematchSettings {
    private const val PREFS = "auto-rematch"
    private const val KEY_ENABLED = "enabled"

    fun read(context: Context): Boolean =
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false) }
            .getOrDefault(false)

    /** Persists the opt-in and publishes it to [AutoRematchState] in one step. */
    fun save(context: Context, enabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
        AutoRematchState.mutable.value = read(context)
    }

    /** Re-reads the stored value and republishes it (service start, first overlay build). */
    fun publish(context: Context): Boolean = read(context).also { AutoRematchState.mutable.value = it }

    /**
     * The only value that may arm the game, as reported by the shared status bundle:
     * opt-in AND unattended AND not a self-test session. Identical to the heartbeat
     * gating in [AiOverlayService], so both surfaces always show the same effective
     * state; the stored opt-in alone never arms anything.
     */
    fun active(context: Context, unattendedEnabled: Boolean, demo: Boolean): Boolean =
        !demo && unattendedEnabled && read(context)
}
