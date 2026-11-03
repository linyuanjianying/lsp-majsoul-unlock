package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle

/**
 * Regression checks for the assistant display-mode switch (system overlay vs in-game
 * Hook). These exercise the pure decision logic and the preference round-trip without
 * touching the real overlay permission, a window, or the game process.
 */
internal object DisplayModeChecks {
    fun run(target: Context) {
        // Isolated prefs so the real user selection is never read or written.
        val isolated = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("display-mode-checks-$name", mode)
        }

        // Default is Hook (no overlay permission needed).
        check(AssistantDisplayModeSettings.read(isolated) == AssistantDisplayMode.HOOK)
        check(AssistantDisplayMode.fromWire(null) == AssistantDisplayMode.HOOK)
        check(AssistantDisplayMode.fromWire("") == AssistantDisplayMode.HOOK)
        check(AssistantDisplayMode.fromWire("bogus") == AssistantDisplayMode.HOOK)
        check(AssistantDisplayMode.fromWire("overlay") == AssistantDisplayMode.OVERLAY)
        check(AssistantDisplayMode.fromWire("hook") == AssistantDisplayMode.HOOK)

        // Core decision: overlay only when selected AND permission held.
        check(AssistantDisplayModeSettings.resolve(AssistantDisplayMode.OVERLAY, true) == AssistantDisplayMode.OVERLAY)
        // Selected but no permission -> falls back to Hook, never "neither".
        check(AssistantDisplayModeSettings.resolve(AssistantDisplayMode.OVERLAY, false) == AssistantDisplayMode.HOOK)
        check(AssistantDisplayModeSettings.resolve(AssistantDisplayMode.HOOK, true) == AssistantDisplayMode.HOOK)
        check(AssistantDisplayModeSettings.resolve(AssistantDisplayMode.HOOK, false) == AssistantDisplayMode.HOOK)

        // Persistence round-trip survives a fresh read.
        AssistantDisplayModeSettings.save(isolated, AssistantDisplayMode.OVERLAY)
        check(AssistantDisplayModeSettings.read(isolated) == AssistantDisplayMode.OVERLAY)
        AssistantDisplayModeSettings.save(isolated, AssistantDisplayMode.HOOK)
        check(AssistantDisplayModeSettings.read(isolated) == AssistantDisplayMode.HOOK)

        // The wire string must be stable: it is what crosses the UID-checked provider.
        check(AssistantDisplayMode.HOOK.wire == "hook")
        check(AssistantDisplayMode.OVERLAY.wire == "overlay")

        // The status bundle carries the mode to the in-game control; a missing key
        // (older manager) must read back as "not overlay" so the control stays visible.
        val status = AssistLaunch.status()
        check(!status.getBoolean("overlayMode", false))
        check(status.getString("displayMode", "hook") == "hook")

        // withDisplayMode injects the persisted mode into any status bundle; the
        // in-game control keys off `overlayMode` to hide itself.
        val hooked = AssistLaunch.withDisplayMode(isolated, Bundle())
        check(!hooked.getBoolean("overlayMode", true))
        AssistantDisplayModeSettings.save(isolated, AssistantDisplayMode.OVERLAY)
        val overlay = AssistLaunch.withDisplayMode(isolated, Bundle())
        check(overlay.getBoolean("overlayMode", false) == android.provider.Settings.canDrawOverlays(isolated))
        check(overlay.getString("displayMode") == "overlay")
        AssistantDisplayModeSettings.save(isolated, AssistantDisplayMode.HOOK)
    }
}
