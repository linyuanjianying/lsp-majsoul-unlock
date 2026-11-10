package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle

/**
 * Regression checks for the single-surface display mode (system overlay vs in-game Hook).
 *
 * The contract these guard is deliberately plain:
 *
 *  - the stored preference is a **selection for the next start**; saving it never rewrites a
 *    running service's mode;
 *  - a service instance snapshots that selection once, in `onCreate`, and that snapshot is
 *    both the surface it builds and the answer it publishes;
 *  - exactly one surface exists: hook mode creates no overlay window, overlay mode creates no
 *    in-game view, and an overlay that cannot be built is **reported**, not replaced by hook;
 *  - a caller that cannot read the mode keeps what it knew (never a silent "hook").
 *
 * The previous revision of this file tested a lease/acknowledgement protocol that has been
 * removed. The stale cases were replaced instead of being kept as a facade.
 */
internal object DisplayModeChecks {
    fun run(target: Context) {
        // Isolated prefs so the real user selection is never read or written.
        val isolated = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("display-mode-checks-$name", mode)
        }
        isolated.getSharedPreferences("assistant-display", Context.MODE_PRIVATE).edit().clear().commit()
        AssistantDisplayRun.reset()

        // Default is Hook (no overlay permission needed).
        check(AssistantDisplayModeSettings.read(isolated) == AssistantDisplayMode.HOOK)
        check(AssistantDisplayMode.fromWire(null) == AssistantDisplayMode.HOOK)
        check(AssistantDisplayMode.fromWire("") == AssistantDisplayMode.HOOK)
        check(AssistantDisplayMode.fromWire("bogus") == AssistantDisplayMode.HOOK)
        check(AssistantDisplayMode.fromWire("overlay") == AssistantDisplayMode.OVERLAY)
        check(AssistantDisplayMode.fromWire("hook") == AssistantDisplayMode.HOOK)

        // Persistence round-trip survives a fresh read; the wire strings are the protocol.
        AssistantDisplayModeSettings.save(isolated, AssistantDisplayMode.OVERLAY)
        check(AssistantDisplayModeSettings.read(isolated) == AssistantDisplayMode.OVERLAY)
        check(AssistantDisplayModeSettings.selectedIs(isolated, AssistantDisplayMode.OVERLAY))
        AssistantDisplayModeSettings.save(isolated, AssistantDisplayMode.HOOK)
        check(AssistantDisplayModeSettings.read(isolated) == AssistantDisplayMode.HOOK
            && AssistantDisplayModeSettings.selectedIs(isolated, AssistantDisplayMode.HOOK))
        check(AssistantDisplayMode.HOOK.wire == "hook")
        check(AssistantDisplayMode.OVERLAY.wire == "overlay")

        selectionVersusRunChecks(isolated)
        overlayErrorChecks(isolated)
        selectionReplyChecks(isolated)
    }

    /**
     * The selection is not the run. Saving overlay while a hook service runs must not change
     * what that service reports, and a later save back to hook must not change a running
     * overlay service either.
     */
    private fun selectionVersusRunChecks(target: Context) {
        AssistantDisplayModeSettings.save(target, AssistantDisplayMode.HOOK)
        val hookRun = AssistantDisplayRun.start(AssistantDisplayMode.HOOK, 1_000L, "run-1")
        check(hookRun.mode == AssistantDisplayMode.HOOK)
        check(!hookRun.overlayReady)
        // The user picks overlay while the service runs: nothing is rebuilt.
        AssistantDisplayModeSettings.save(target, AssistantDisplayMode.OVERLAY)
        check(AssistantDisplayRun.mode == AssistantDisplayMode.HOOK) {
            "a settings save must not change the running mode"
        }
        check(DisplayModeSnapshotStore.ofService()?.mode == AssistantDisplayMode.HOOK)
        check(DisplayModeSnapshotStore.ofService()?.source == DisplayModeSnapshotStore.MODE_SOURCE_SERVICE)
        // The next start takes the new selection.
        AssistantDisplayRun.finish(1_000L)
        check(AssistantDisplayRun.current == null) { "the run must not survive its service" }
        check(AssistantDisplayModeSettings.read(target) == AssistantDisplayMode.OVERLAY) {
            "the selection survives the run"
        }
        val overlayRun = AssistantDisplayRun.start(AssistantDisplayMode.OVERLAY, 2_000L, "run-2")
        check(overlayRun.mode == AssistantDisplayMode.OVERLAY)
        check(overlayRun.overlayReady) { "an overlay run without a reported error is ready" }
        // And a save back to hook leaves it alone until the next start.
        AssistantDisplayModeSettings.save(target, AssistantDisplayMode.HOOK)
        check(AssistantDisplayRun.mode == AssistantDisplayMode.OVERLAY)
        // A superseded instance must not clear the current run's snapshot.
        AssistantDisplayRun.finish(1_000L)
        check(AssistantDisplayRun.current?.nonce == "run-2")
        AssistantDisplayRun.finish(2_000L)
        check(AssistantDisplayRun.current == null)
        AssistantDisplayModeSettings.save(target, AssistantDisplayMode.HOOK)
    }

    /**
     * A selected overlay that cannot be built keeps the OVERLAY mode and reports the reason.
     * It never resolves to HOOK and never claims to be ready.
     */
    private fun overlayErrorChecks(target: Context) {
        AssistantDisplayModeSettings.save(target, AssistantDisplayMode.OVERLAY)
        AssistantDisplayRun.start(AssistantDisplayMode.OVERLAY, 3_000L, "run-3")
        AssistantDisplayRun.noteOverlayError("overlay_permission_missing")
        val snapshot = DisplayModeSnapshotStore.ofService()
        check(snapshot?.mode == AssistantDisplayMode.OVERLAY) {
            "an unavailable overlay must not become hook mode"
        }
        check(snapshot?.overlayReady == false)
        check(snapshot?.overlayError == "overlay_permission_missing")
        // Idempotent: the tick repeats the same reason.
        AssistantDisplayRun.noteOverlayError("overlay_permission_missing")
        check(AssistantDisplayRun.current?.overlayError == "overlay_permission_missing")
        // A different reason replaces it (the user granted the permission, the add failed).
        AssistantDisplayRun.noteOverlayError("floating_window_failed")
        check(AssistantDisplayRun.current?.overlayError == "floating_window_failed")
        // Recovery clears it without touching the mode.
        AssistantDisplayRun.clearOverlayError()
        check(DisplayModeSnapshotStore.ofService()?.overlayReady == true)
        check(DisplayModeSnapshotStore.ofService()?.mode == AssistantDisplayMode.OVERLAY)
        AssistantDisplayRun.finish(3_000L)
        AssistantDisplayModeSettings.save(target, AssistantDisplayMode.HOOK)
    }

    /**
     * The reply a game process reads. One mode, one answer; a missing service falls back to
     * the stored selection, and nothing is ever reported as an "owner" or a "lease".
     */
    private fun selectionReplyChecks(target: Context) {
        // No service: the stored selection is the answer, marked as such.
        AssistantDisplayModeSettings.save(target, AssistantDisplayMode.OVERLAY)
        val selectedOnly = DisplayModeSnapshotStore.snapshot(target)
        check(selectedOnly.mode == AssistantDisplayMode.OVERLAY)
        check(selectedOnly.source == DisplayModeSnapshotStore.MODE_SOURCE_SELECTION)
        check(!selectedOnly.overlayReady) { "a selection alone is not a running overlay" }

        // Running hook service: the reply says hook even though overlay is stored.
        AssistantDisplayRun.start(AssistantDisplayMode.HOOK, 4_000L, "run-4")
        val run = AssistLaunch.withDisplayMode(target, Bundle())
        check(run.getString("mode") == "hook")
        check(run.getString("displayMode") == "hook")
        check(!run.getBoolean("overlayMode", true))
        check(!run.getBoolean("overlayReady", true))
        check(run.getInt("displaySource", -1) == DisplayModeSnapshotStore.MODE_SOURCE_SERVICE)
        check(run.getString("displayNonce") == "run-4")
        // The selection for the next start is still visible, but informational.
        check(run.getString("displaySelected") == "overlay")
        // The dedicated read-only endpoint agrees with the status reply.
        val readOnly = AssistLaunch.readDisplaySnapshot(target)
        check(readOnly?.mode == AssistantDisplayMode.HOOK)
        // None of the removed protocol fields are published any more.
        check(!run.containsKey("displayOwner")) { "the lease owner field must be gone" }
        check(!run.containsKey("overlaySelected")) { "the lease selection field must be gone" }
        check(!run.containsKey("displayOverlayFailed")) { "the lease failure field must be gone" }
        check(!run.containsKey("displayGeneration")) { "the lease generation field must be gone" }
        AssistantDisplayRun.finish(4_000L)

        // Running overlay service with a broken window: overlay is still the answer.
        AssistantDisplayRun.start(AssistantDisplayMode.OVERLAY, 5_000L, "run-5")
        AssistantDisplayRun.noteOverlayError("badge_overlay_failed")
        val broken = AssistLaunch.withDisplayMode(target, Bundle())
        check(broken.getString("mode") == "overlay")
        check(broken.getBoolean("overlayReady", true) == false)
        check(broken.getString("overlayError") == "badge_overlay_failed")
        check(broken.getInt("displaySource", -1) == DisplayModeSnapshotStore.MODE_SOURCE_SERVICE)
        AssistantDisplayRun.finish(5_000L)

        // A service exists but has not produced a snapshot yet: no invented answer.
        // `AiEndpointProvider.putDisplaySnapshot` turns this into `displaySource = 0` with no
        // `mode` key at all, which `GameDisplayGate.apply` refuses — the game process must be
        // able to tell "I could not tell" from "hook", because only the latter builds a
        // surface. The reply shape itself is asserted here; the gate's refusal is covered by
        // `tools/tests/DisplayModeProbe.kt` (both are plain logic).
        check(DisplayModeSnapshotStore.ofService() == null)
        AssistantDisplayModeSettings.save(target, AssistantDisplayMode.HOOK)
    }
}
