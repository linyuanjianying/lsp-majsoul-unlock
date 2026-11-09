package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.provider.Settings

/**
 * Regression checks for the assistant display-mode switch (system overlay vs in-game
 * Hook). These exercise the pure decision logic, the preference round-trip and the
 * single-surface lease without touching the real overlay permission, a window, or the
 * game process.
 *
 * The reported bug these guard against: "the floating window and the in-game drawing were
 * occasionally active at the same time". The root cause was that each surface decided for
 * itself from the persisted mode, and each polling loop observes a switch a little later
 * than the other. The lease ([DisplayModeLease] / [DisplayModeLeaseLocal]) is the fix; the
 * cases below replay the orderings that used to overlap. The same cases run on a plain JVM
 * through `tools/tests/run-display-mode-lease.sh`, so they also catch a regression without
 * a device.
 */
internal object DisplayModeChecks {
    fun run(target: Context) {
        // Isolated prefs so the real user selection is never read or written.
        val isolated = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("display-mode-checks-$name", mode)
        }
        isolated.getSharedPreferences("assistant-display", Context.MODE_PRIVATE).edit().clear().commit()
        val overlayPermission = Settings.canDrawOverlays(isolated)

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

        // The wire strings and owner ids are part of the provider protocol.
        check(AssistantDisplayMode.HOOK.wire == "hook")
        check(AssistantDisplayMode.OVERLAY.wire == "overlay")
        check(DisplayModeOwner.NONE == 0)
        check(DisplayModeOwner.OVERLAY == 1)
        check(DisplayModeOwner.HOOK == 2)

        // A switch starts a new generation; a reply stamped with the old one is stale.
        val beforeSwitch = AssistantDisplayModeSettings.generation()
        AssistantDisplayModeSettings.save(isolated, AssistantDisplayMode.OVERLAY)
        check(AssistantDisplayModeSettings.generation() != beforeSwitch)
        check(AssistantDisplayModeSettings.stale(beforeSwitch))
        check(!AssistantDisplayModeSettings.stale(AssistantDisplayModeSettings.generation()))

        // A failed overlay build hands the surface back instead of leaving neither.
        // Regression: the marker used to be stamped on the *old* generation and then the
        // counter was bumped, so overlayUnavailable() was permanently false.
        check(!AssistantDisplayModeSettings.overlayUnavailable())
        val failedAt = AssistantDisplayModeSettings.generation()
        AssistantDisplayModeSettings.noteOverlayFailed(isolated)
        check(AssistantDisplayModeSettings.generation() != failedAt) {
            "noteOverlayFailed must start a new generation"
        }
        check(AssistantDisplayModeSettings.overlayUnavailable()) {
            "overlayUnavailable must report the failure for the live generation"
        }
        check(AssistantDisplayModeSettings.effective(isolated) == AssistantDisplayMode.HOOK)
        // A second report for the same failure is idempotent, not a generation churn.
        val afterFirst = AssistantDisplayModeSettings.generation()
        AssistantDisplayModeSettings.noteOverlayFailed(isolated)
        check(AssistantDisplayModeSettings.generation() == afterFirst) {
            "repeated failure reports must not advance the live generation"
        }
        check(AssistantDisplayModeSettings.overlayUnavailable())
        // The next selection clears it (the user may have granted the permission).
        AssistantDisplayModeSettings.save(isolated, AssistantDisplayMode.HOOK)
        check(!AssistantDisplayModeSettings.overlayUnavailable())

        // The status bundle carries the mode and the lease to the in-game control; a
        // missing key (older manager) reads back as "not overlay" so the control stays.
        val status = AssistLaunch.status()
        check(!status.getBoolean("overlayMode", false))
        check(status.getString("displayMode", "hook") == "hook")

        val hooked = AssistLaunch.withDisplayMode(isolated, Bundle())
        check(!hooked.getBoolean("overlayMode", true))
        check(hooked.containsKey("displayOwner"))
        check(hooked.containsKey("displayGeneration"))
        check(hooked.containsKey("displayOverlayFailed"))
        // A reply computed before the last switch reports "nobody owns the surface" for the
        // new generation, which is what keeps the two surfaces from overlapping.
        check(hooked.getInt("displayOwner", -1) == DisplayModeOwner.NONE)
        check(hooked.getInt("displayGeneration", -1) == AssistantDisplayModeSettings.generation())

        AssistantDisplayModeSettings.save(isolated, AssistantDisplayMode.OVERLAY)
        val overlay = AssistLaunch.withDisplayMode(isolated, Bundle())
        check(overlay.getString("displayMode") == "overlay")
        // `overlayMode` is the permission-checked persisted selection; whether the in-game
        // control may paint is decided by the lease fields of the same bundle.
        check(!overlay.getBoolean("overlayMode", false)) // no system window owns the lease yet
        AssistantDisplayModeSettings.save(isolated, AssistantDisplayMode.HOOK)
        DisplayModeLeaseLocal.setOverlaySelected(false, AssistantDisplayModeSettings.generation())

        persistedGenerationChecks(isolated)
        leaseChecks()
        managerLeaseChecks()
    }

    /**
     * Regression: the generation must survive a manager restart.
     *
     * It used to live only in the manager process, so a restarted manager handed out
     * generation 1 again. The game drops every reply stamped below the newest generation it
     * applied, so it would have rejected all of them and kept painting next to the overlay.
     */
    private fun persistedGenerationChecks(target: Context) {
        val first = AssistantDisplayModeSettings.generation()
        check(first > 0) { "a generation must exist after the first read" }
        AssistantDisplayModeSettings.ensureLoaded(target)
        check(AssistantDisplayModeSettings.generation() == first) {
            "loading twice must not change the generation"
        }
        // The stored value is what a fresh process resumes; it is committed, not applied.
        val stored = target.getSharedPreferences("assistant-display", Context.MODE_PRIVATE)
        val persisted = stored.getInt("displayGeneration", -1)
        check(persisted == first) { "persisted generation $persisted != live $first" }
        // Selecting another mode advances it and commits again.
        AssistantDisplayModeSettings.save(target, AssistantDisplayMode.HOOK)
        val after = AssistantDisplayModeSettings.generation()
        check(after > first) { "a switch must advance the generation" }
        check(stored.getInt("displayGeneration", -1) == after) {
            "the new generation must be persisted immediately"
        }
        // A stamp newer than this process knows is not stale (the restart case, other side).
        check(!AssistantDisplayModeSettings.stale(after + 1))
        check(AssistantDisplayModeSettings.stale(first))
    }

    /**
     * The lease as the **game** process decides it. Every case is a state of one status
     * reply; the interleavings of the two real state machines live in
     * `tools/tests/DisplayModeLeaseProbe.kt`, which runs on a plain JVM.
     */
    private fun leaseChecks() {
        // Overlay selected and owned: hide, and acknowledge that generation.
        run {
            val lease = DisplayModeLease({ 1_000L })
            lease.apply(reply(true, DisplayModeOwner.OVERLAY, 3))
            expect("an owned overlay hides the in-game control", lease.mustHide())
            expect("an owned overlay is acknowledged", lease.generationToAcknowledge() == 3)
            expect("the overlay holds the surface", lease.overlayOwnsSurface())
        }

        // The switch window: overlay selected, nobody owns it yet. The game hides first and
        // acknowledges — this is what the manager waits for before it shows anything.
        run {
            val lease = DisplayModeLease({ 1_000L })
            lease.apply(reply(true, DisplayModeOwner.NONE, 4))
            expect("the switch window hides first", lease.mustHide())
            expect("the switch window acknowledges its generation",
                lease.generationToAcknowledge() == 4)
            expect("nobody owns the surface yet", !lease.overlayOwnsSurface())
        }

        // Renewal: a live claim keeps the control down; only the manager dropping it (or
        // going silent past the lease) releases the surface again.
        run {
            var now = 1_000L
            val lease = DisplayModeLease({ now })
            lease.apply(reply(true, DisplayModeOwner.OVERLAY, 5))
            var hidden = true
            repeat(30) {
                now += 1_000L
                lease.apply(reply(true, DisplayModeOwner.OVERLAY, 5))
                hidden = hidden && lease.mustHide()
            }
            expect("a renewed claim never lapses", hidden)
        }

        // Hook mode after the overlay is gone: paint, acknowledge nothing new.
        run {
            val lease = DisplayModeLease({ 1_000L })
            lease.apply(reply(false, DisplayModeOwner.NONE, 6))
            expect("hook mode paints", !lease.mustHide())
            expect("hook mode acknowledges nothing", lease.generationToAcknowledge() == 0)
        }

        // Returning to hook mode while the windows are still attached: the manager keeps
        // publishing "overlay selected" until they are removed, so the game stays hidden.
        run {
            val lease = DisplayModeLease({ 1_000L })
            lease.apply(reply(true, DisplayModeOwner.OVERLAY, 7))
            lease.apply(reply(true, DisplayModeOwner.OVERLAY, 7))
            expect("hidden while the window is still attached", lease.mustHide())
            lease.apply(reply(false, DisplayModeOwner.NONE, 8))
            expect("paints only after the window is gone", !lease.mustHide())
        }

        // Overlay failed (permission revoked): the manager reports it, the game paints.
        run {
            val lease = DisplayModeLease({ 1_000L })
            lease.apply(reply(true, DisplayModeOwner.NONE, 9, unavailable = true))
            expect("a failed overlay paints", !lease.mustHide())
            expect("a failed overlay acknowledges nothing", lease.generationToAcknowledge() == 0)
        }

        // Manager unreachable: the in-game control is the only surface left.
        run {
            val lease = DisplayModeLease({ 1_000L })
            lease.apply(reply(true, DisplayModeOwner.OVERLAY, 10))
            lease.apply(DisplayModeLease.Reply(
                overlaySelected = false,
                owner = DisplayModeOwner.NONE,
                generation = 10,
                overlayUnavailable = false,
                unreachable = true))
            expect("a lost manager makes the control reachable", !lease.mustHide())
        }

        // A late reply for a superseded generation is dropped.
        run {
            val lease = DisplayModeLease({ 1_000L })
            lease.apply(reply(false, DisplayModeOwner.NONE, 12))
            lease.apply(reply(true, DisplayModeOwner.OVERLAY, 11))
            expect("an older reply is dropped", !lease.mustHide() && lease.generation == 12)
        }
    }

    /**
     * The manager half: the overlay may only be shown after the game acknowledged that it
     * hid for the current generation, and the selection flag is what tells the game to hide.
     */
    private fun managerLeaseChecks() {
        val generation = 900_001
        DisplayModeLeaseLocal.publish(generation)
        DisplayModeLeaseLocal.setOverlaySelected(true, generation)
        expect("the overlay waits for the game's acknowledgement",
            !DisplayModeLeaseLocal.mayShowOverlay(generation))
        // An acknowledgement from before the switch does not count.
        DisplayModeLeaseLocal.acknowledge(generation - 1)
        expect("an older acknowledgement does not release the wait",
            !DisplayModeLeaseLocal.mayShowOverlay(generation))
        DisplayModeLeaseLocal.acknowledge(generation)
        expect("the acknowledgement for that generation releases it",
            DisplayModeLeaseLocal.mayShowOverlay(generation))
        expect("the game is now known to exist", DisplayModeLeaseLocal.gameSeen())
        // Back to hook: the flag itself is the instruction, no acknowledgement needed.
        DisplayModeLeaseLocal.setOverlaySelected(false, generation)
        expect("hook mode never shows the overlay", !DisplayModeLeaseLocal.mayShowOverlay(generation))
        DisplayModeLeaseLocal.drop(generation)
    }

    /** Named check for the lease cases (the shared `check` above takes only a value). */
    private fun expect(name: String, condition: Boolean) {
        check(condition) { "display-mode lease: " + name }
    }

    private fun reply(
        overlay: Boolean,
        owner: Int,
        generation: Int,
        unavailable: Boolean = false,
    ) = DisplayModeLease.Reply(
        overlaySelected = overlay,
        owner = owner,
        generation = generation,
        overlayUnavailable = unavailable,
        unreachable = false,
    )
}
