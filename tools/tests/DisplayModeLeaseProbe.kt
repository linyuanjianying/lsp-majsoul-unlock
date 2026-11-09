// JVM-only regression probe for the single-surface hand-off (overlay vs in-game drawing).
// Compiles against the already-built universalDebug classes with -Xfriend-paths so the
// `internal` types are reachable. Nothing touches a Context or a window.
//
// The two processes are simulated as what they really are: two state machines that only
// exchange status replies and acknowledgements. `Manager` mirrors
// DisplayModeLeaseLocal + the service's applyDisplayMode decision; `Game` mirrors
// GameTopControl + DisplayModeLease. The reply the game sees is built from the manager's
// *published* lease exactly as AiEndpointProvider.putLease builds it, and the
// acknowledgement the manager sees is exactly what GameTopControl puts in the
// `displayGeneration` extra. So an interleaving here is an interleaving of the real code.
import com.yefeng.majmax.hookprobe.manager.AssistantDisplayModeSettings
import com.yefeng.majmax.hookprobe.manager.DiagnosticsStore
import com.yefeng.majmax.hookprobe.manager.DisplayModeLease
import com.yefeng.majmax.hookprobe.manager.DisplayModeLeaseLocal
import com.yefeng.majmax.hookprobe.manager.DisplayModeOwner

private var passed = 0
private var failed = 0

private fun check(name: String, condition: Boolean, detail: () -> String = { "" }) {
    if (condition) {
        passed++
        println("PASS  " + name)
    } else {
        failed++
        println("FAIL  " + name + "  " + detail())
    }
}

/** One phone, one fake clock, two state machines. */
private class Phone {
    var now = 1_000L
    var generation = 500_000

    /** The manager's published lease, as the provider would read it. */
    var publishedSelected = false
    var publishedOwner = DisplayModeOwner.NONE
    var publishedFailed = false

    /** The game's mirror. */
    val game = DisplayModeLease({ now })

    /** What the game actually draws right now (control + hint layer follow the same flag). */
    var inGamePaints = true

    /** What the manager's own system window shows right now. */
    var overlayVisible = false

    fun bothVisible() = inGamePaints && overlayVisible

    fun switch(overlay: Boolean) {
        generation++
        publishedOwner = DisplayModeOwner.NONE
        publishedFailed = false
        if (overlay) {
            // Selecting overlay: the intent is published, the window is not shown yet.
            DisplayModeLeaseLocal.setOverlaySelected(true, generation)
            publishedSelected = true
        } else {
            // Hook mode: the service removes its windows first, and only then publishes
            // "not selected" (AiOverlayService.applyDisplayMode does exactly this order).
            overlayVisible = false
            DisplayModeLeaseLocal.setOverlaySelected(false, generation)
            publishedSelected = false
        }
    }

    /** The service's tick: only a real acknowledgement may start the window. */
    fun managerTick() {
        if (!publishedSelected) {
            overlayVisible = false
            return
        }
        if (DisplayModeLeaseLocal.mayShowOverlay(generation)) {
            overlayVisible = true
            publishedOwner = DisplayModeOwner.OVERLAY
            DisplayModeLeaseLocal.claim(generation)
        } else {
            overlayVisible = false
        }
    }

    /** The game's poll: build the reply, apply it, hide, then acknowledge if required. */
    fun gamePoll() {
        val reply = DisplayModeLease.Reply(
            overlaySelected = publishedSelected,
            owner = publishedOwner,
            generation = generation,
            overlayUnavailable = publishedFailed,
            unreachable = false,
        )
        game.apply(reply)
        inGamePaints = !game.mustHide()
        val ack = game.generationToAcknowledge()
        if (ack > 0) DisplayModeLeaseLocal.acknowledge(ack)
    }

    fun resetLease() {
        DisplayModeLeaseLocal.setOverlaySelected(false, generation)
    }
}

private fun r(sel: Boolean, owner: Int, gen: Int, unavailable: Boolean = false) =
    DisplayModeLease.Reply(
        overlaySelected = sel,
        owner = owner,
        generation = gen,
        overlayUnavailable = unavailable,
        unreachable = false,
    )

fun main() {
    // ---- single-state cases ------------------------------------------------------------
    run {
        val p = Phone()
        p.resetLease()
        p.game.apply(r(false, DisplayModeOwner.NONE, p.generation))
        check("hook mode paints", !p.game.mustHide())
        check("hook mode acknowledges nothing", p.game.generationToAcknowledge() == 0)
    }

    run {
        val p = Phone()
        p.resetLease()
        p.game.apply(r(true, DisplayModeOwner.NONE, p.generation))
        check("the switch window hides first", p.game.mustHide())
        check("the switch window acknowledges its generation",
            p.game.generationToAcknowledge() == p.generation, { p.game.describe() })
        check("nobody owns the surface yet", !p.game.overlayOwnsSurface())
    }

    run {
        var now = 1_000L
        val lease = DisplayModeLease({ now })
        lease.apply(r(true, DisplayModeOwner.OVERLAY, 7))
        var hidden = true
        repeat(30) {
            now += 1_000L
            lease.apply(r(true, DisplayModeOwner.OVERLAY, 7))
            hidden = hidden && lease.mustHide()
        }
        check("a renewed claim never lapses", hidden)
    }

    run {
        val lease = DisplayModeLease({ 1_000L })
        lease.apply(r(true, DisplayModeOwner.NONE, 8, unavailable = true))
        check("a failed overlay paints", !lease.mustHide())
    }

    run {
        val lease = DisplayModeLease({ 1_000L })
        lease.apply(r(true, DisplayModeOwner.OVERLAY, 9))
        lease.apply(DisplayModeLease.Reply(false, DisplayModeOwner.NONE, 9, false, true))
        check("a lost manager makes the control reachable", !lease.mustHide())
    }

    run {
        val lease = DisplayModeLease({ 1_000L })
        lease.apply(r(false, DisplayModeOwner.NONE, 11))
        lease.apply(r(true, DisplayModeOwner.OVERLAY, 10))
        check("an older reply is dropped", !lease.mustHide() && lease.generation == 11)
    }

    // ---- the interleavings that used to show both surfaces -----------------------------

    // A. First switch with a game that has never spoken. The old code required an
    //    acknowledgement before the manager even considered the game present, so the overlay
    //    painted immediately while the in-game control still did.
    run {
        val p = Phone()
        p.resetLease()
        p.switch(true)          // user selects overlay
        p.managerTick()         // manager tick before any game poll
        check("A: overlay does not appear before the game hides", !p.overlayVisible)
        p.gamePoll()            // game hides and acknowledges
        p.managerTick()         // now the window may appear
        check("A: overlay appears once acknowledged", p.overlayVisible)
        p.gamePoll()            // game sees the claim
        check("A: the in-game surface stays down", !p.inGamePaints)
        check("A: never both", !p.bothVisible())
    }

    // B. The reported deadlock: acknowledgement was only sent when the overlay already owned
    //    the surface, but ownership required the acknowledgement. The game must acknowledge
    //    on the switch reply (owner NONE) and the manager must then paint — no 10 s timeout.
    run {
        val p = Phone()
        p.resetLease()
        p.switch(true)
        p.gamePoll()            // owner NONE, overlay wanted: hide + ack
        val ackedImmediately = DisplayModeLeaseLocal.mayShowOverlay(p.generation)
        p.managerTick()
        check("B: the acknowledgement arrives without a timeout", ackedImmediately)
        check("B: the overlay appears on the next manager tick", p.overlayVisible)
        p.gamePoll()
        check("B: no overlap", !p.bothVisible())
    }

    // C. Switch back to hook: the manager publishes "not selected" only after its windows
    //    are gone, so the game never paints next to a window that is still attached.
    run {
        val p = Phone()
        p.resetLease()
        p.switch(true); p.gamePoll(); p.managerTick(); p.gamePoll()
        check("C: setup overlays", p.overlayVisible && !p.inGamePaints)
        // The user switches to hook while the overlay is still on screen: nothing may paint.
        p.generation++
        // Still selected until the windows are removed.
        p.gamePoll()
        check("C: the game keeps hiding while the window is attached", !p.inGamePaints)
        check("C: no overlap during the teardown", !p.bothVisible())
        p.publishedSelected = false
        p.overlayVisible = false
        p.publishedOwner = DisplayModeOwner.NONE
        p.gamePoll()
        check("C: the game paints once the window is gone", p.inGamePaints)
    }

    // D. Rapid switch hook -> overlay -> hook before the game ever answered.
    run {
        val p = Phone()
        p.resetLease()
        p.switch(true)
        p.switch(true)          // user changes their mind twice, generation advances
        p.gamePoll()
        // overlay still selected: the game hides
        check("D: game hides for the newest generation", !p.inGamePaints)
        p.managerTick()
        check("D: overlay may appear after the acknowledgement", p.overlayVisible)
        p.gamePoll()
        check("D: never both", !p.bothVisible())
    }

    // E. Overlay build fails: the manager reports it, the game paints, nobody claims.
    run {
        val p = Phone()
        p.resetLease()
        p.switch(true); p.gamePoll()
        // build fails -> noteOverlayFailed starts a new generation, published as failed
        p.generation++
        p.publishedFailed = true
        p.publishedSelected = false
        p.publishedOwner = DisplayModeOwner.NONE
        p.overlayVisible = false
        p.managerTick()
        p.gamePoll()
        check("E: the in-game surface takes over", p.inGamePaints)
        check("E: the overlay stays away", !p.overlayVisible)
    }

    // F. A long run of ordinary polling must never show both surfaces.
    run {
        val p = Phone()
        p.resetLease()
        var overlap = false
        repeat(200) { step ->
            when (step % 23) {
                0 -> p.switch(true)
                5 -> p.switch(false)
                11 -> { p.managerTick(); p.gamePoll() }
                else -> { p.gamePoll(); p.managerTick() }
            }
            if (p.bothVisible()) overlap = true
        }
        check("F: 200 interleaved steps never show both surfaces", !overlap)
    }

    // ---- generation / export naming ----------------------------------------------------

    run {
        check("a stored generation is resumed", AssistantDisplayModeSettings.resumeGeneration(42) == 42)
        check("a missing generation starts at zero",
            AssistantDisplayModeSettings.resumeGeneration(0) == 0)
        check("a negative stored value is clamped",
            AssistantDisplayModeSettings.resumeGeneration(-5) == 0)
    }

    run {
        val a = DiagnosticsStore.exportFileName(1_700_000_000_000L)
        val b = DiagnosticsStore.exportFileName(1_700_000_061_000L)
        check("export file name is a timestamped .zip",
            a.matches(Regex("majmax-diagnostics-\\d{8}-\\d{6}\\.zip")) && a != b) { a }
    }

    check("owner ids are wire values",
        DisplayModeLease.OWNER_NONE == 0 &&
            DisplayModeLease.OWNER_OVERLAY == 1 &&
            DisplayModeLease.OWNER_HOOK == 2 &&
            DisplayModeOwner.NONE == DisplayModeLease.OWNER_NONE &&
            DisplayModeLease.LEASE_MS >= 3_000L)

    println("=== " + passed + " passed, " + failed + " failed ===")
    if (failed != 0) kotlin.system.exitProcess(1)
}
