package com.yefeng.majmax.hookprobe.manager

import android.content.Context

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
 */
internal object AutoRematchSettings {
    private const val PREFS = "auto-rematch"
    private const val KEY_ENABLED = "enabled"

    fun read(context: Context): Boolean =
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false) }
            .getOrDefault(false)

    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
