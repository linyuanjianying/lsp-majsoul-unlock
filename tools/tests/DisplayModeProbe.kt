// JVM-only regression probe for the single-surface display mode (overlay vs in-game hook).
//
// Compiles against the already-built universalDebug classes with -Xfriend-paths so the
// `internal` manager types are reachable; nothing touches a Context, a window or a device.
//
// It replaces the old DisplayModeLeaseProbe: the lease / acknowledgement / owner protocol is
// gone, so what is checked here is what actually decides the surface now —
//
//   1. the stored selection is only a *next start* preference (AssistantDisplayModeSettings),
//   2. the service snapshot is the run mode (AssistantDisplayRun / DisplayModeSnapshotStore),
//   3. a selected-but-unbuildable overlay stays OVERLAY and reports the reason,
//   4. the game-side gate (GameDisplayGate) builds the in-game surface only for HOOK, only
//      after an answer, and never turns an unreachable provider into hook.
//
// Step 4 is a mirror of the Java class rather than the class itself: the game side runs with
// the manager-free game process layout, so the probe keeps an exact transcription of its
// transition rules and the instrumentation build compiles the real one.
import com.yefeng.majmax.hookprobe.manager.AssistantDisplayMode
import com.yefeng.majmax.hookprobe.manager.AssistantDisplayModeSettings
import com.yefeng.majmax.hookprobe.manager.AssistantDisplayRun
import com.yefeng.majmax.hookprobe.manager.DisplayModeSnapshotStore

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

/** Exact transcription of GameDisplayGate's state rules (see GameDisplayGate.java). */
private class GateMirror {
    enum class Mode { UNKNOWN, HOOK, OVERLAY }

    var mode = Mode.UNKNOWN
        private set
    var decided = false
        private set
    var overlayError = ""
        private set
    var handedOver = false
        private set
    /** How many provider asks are in flight; must never exceed one. */
    var inFlight = 0
        private set
    /** Asks that were dropped because one was already pending / the mode was known. */
    var suppressedAsks = 0
        private set
    var asks = 0
        private set
    /** In-game surface instances this process created. */
    var inGameSurfaces = 0
        private set

    val hookSelected get() = decided && mode == Mode.HOOK

    /** Mirrors GameDisplayGate.requestMode: asynchronous, single flight, no blocking. */
    fun requestMode() {
        if (decided) { suppressedAsks++; return }
        if (inFlight > 0) { suppressedAsks++; return }
        inFlight++
        asks++
    }

    /** Mirrors the main-thread completion of one ask with a reply (null = no answer). */
    fun complete(
        wire: String?,
        error: String? = null,
        unreachable: Boolean = false,
        source: Int? = 1,
    ): Boolean {
        if (inFlight > 0) inFlight--
        return receive(wire, error, unreachable, source)
    }

    /**
     * One `displayMode` / `autoStatus` answer; null means "no usable answer".
     * `source` is the manager's `displaySource`; 0 is refused, null is an older manager.
     */
    fun receive(
        wire: String?,
        error: String? = null,
        unreachable: Boolean = false,
        source: Int? = SOURCE_SERVICE,
    ): Boolean {
        if (unreachable || wire == null) return decided
        val next = when (wire) {
            "overlay" -> Mode.OVERLAY
            "hook" -> Mode.HOOK
            else -> return decided
        }
        // A reply whose source is UNKNOWN carries a placeholder mode, not an answer.
        if (source == SOURCE_UNKNOWN) return decided
        if (decided) {
            // A later answer only refreshes the reported overlay error.
            overlayError = error ?: ""
            return true
        }
        mode = next
        overlayError = error ?: ""
        decided = true
        return true
    }

    companion object {
        const val SOURCE_UNKNOWN = 0
        const val SOURCE_SERVICE = 1
        const val SOURCE_SELECTION = 2
    }

    /** Mirrors the hand-over: the in-game surface is constructed once, and only for hook. */
    fun handOver() {
        if (handedOver || !hookSelected) return
        handedOver = true
        inGameSurfaces++
    }

    /** Mirrors GameTopControl.attach: refused unless hook mode handed the surface over. */
    fun attachInGame(): Boolean {
        if (!handedOver || !hookSelected) return false
        if (inGameSurfaces == 0) inGameSurfaces++
        return true
    }
}

private class FakePrefs {
    var mode: String? = null
    fun read(): AssistantDisplayMode = AssistantDisplayMode.fromWire(mode)
    fun save(value: AssistantDisplayMode) { mode = value.wire }
}

fun main() {
    // 1. Hook mode is the default and needs nothing else.
    run {
        val prefs = FakePrefs()
        check("default selection is hook", prefs.read() == AssistantDisplayMode.HOOK)
        check("wire round-trip", AssistantDisplayMode.fromWire("overlay") == AssistantDisplayMode.OVERLAY)
        check("unknown wire falls back to hook", AssistantDisplayMode.fromWire("nonsense") == AssistantDisplayMode.HOOK)
    }

    // 2. A run snapshots the selection; later saves change only what the next start reads.
    run {
        val prefs = FakePrefs()
        AssistantDisplayRun.reset()
        prefs.save(AssistantDisplayMode.HOOK)
        val run = AssistantDisplayRun.start(prefs.read(), 10L, "a")
        prefs.save(AssistantDisplayMode.OVERLAY)
        check("run keeps its snapshotted mode", AssistantDisplayRun.mode == AssistantDisplayMode.HOOK) {
            "got ${AssistantDisplayRun.mode}"
        }
        check("run snapshot is the service answer",
            DisplayModeSnapshotStore.ofService()?.mode == AssistantDisplayMode.HOOK)
        check("snapshot source is the service",
            DisplayModeSnapshotStore.ofService()?.source == DisplayModeSnapshotStore.MODE_SOURCE_SERVICE)
        AssistantDisplayRun.finish(run.startedAt)
        check("finish clears the snapshot", AssistantDisplayRun.current == null)
        check("next start reads the new selection", prefs.read() == AssistantDisplayMode.OVERLAY)
        val second = AssistantDisplayRun.start(prefs.read(), 20L, "b")
        check("second run is overlay", second.mode == AssistantDisplayMode.OVERLAY)
        check("overlay run reports ready", second.overlayReady)
        AssistantDisplayRun.finish(10L)
        check("a stale instance cannot clear the live run", AssistantDisplayRun.current?.nonce == "b")
        AssistantDisplayRun.finish(second.startedAt)
    }

    // 3. A selected overlay that cannot be built stays selected and reports why.
    run {
        AssistantDisplayRun.reset()
        AssistantDisplayRun.start(AssistantDisplayMode.OVERLAY, 30L, "c")
        check("overlay without error is ready", DisplayModeSnapshotStore.ofService()?.overlayReady == true)
        AssistantDisplayRun.noteOverlayError("overlay_permission_missing")
        val failed = DisplayModeSnapshotStore.ofService()
        check("a broken overlay stays overlay", failed?.mode == AssistantDisplayMode.OVERLAY) {
            "got ${failed?.mode}"
        }
        check("a broken overlay is not ready", failed?.overlayReady == false)
        check("the reason is published", failed?.overlayError == "overlay_permission_missing")
        AssistantDisplayRun.noteOverlayError("overlay_permission_missing")
        check("repeated reason is idempotent", AssistantDisplayRun.current?.overlayError == "overlay_permission_missing")
        AssistantDisplayRun.clearOverlayError()
        check("recovery keeps the mode", DisplayModeSnapshotStore.ofService()?.mode == AssistantDisplayMode.OVERLAY)
        check("recovery marks it ready", DisplayModeSnapshotStore.ofService()?.overlayReady == true)
        AssistantDisplayRun.finish(30L)
    }

    // 4. Game side: nothing is built before an answer, overlay builds nothing, hook builds once.
    run {
        val gate = GateMirror()
        check("nothing is drawn before the first answer", gate.inGameSurfaces == 0 && !gate.hookSelected)
        check("attach is refused before the answer", !gate.attachInGame())
        gate.handOver()
        check("hand-over refuses an unknown mode", !gate.handedOver)
    }
    run {
        val gate = GateMirror()
        gate.receive("overlay")
        gate.handOver()
        check("overlay mode never builds the in-game surface", gate.inGameSurfaces == 0) {
            "built ${gate.inGameSurfaces}"
        }
        check("overlay mode refuses attach", !gate.attachInGame())
        // A later answer (even hook) cannot re-open the decision for this process.
        gate.receive("hook")
        gate.handOver()
        check("a later answer does not build what the first answer refused", gate.inGameSurfaces == 0)
    }
    run {
        val gate = GateMirror()
        gate.receive("hook")
        gate.handOver()
        check("hook mode builds exactly one in-game surface", gate.inGameSurfaces == 1)
        gate.handOver()
        check("the hand-over happens once", gate.inGameSurfaces == 1)
        check("attach succeeds in hook mode", gate.attachInGame() && gate.inGameSurfaces == 1)
    }
    run {
        val gate = GateMirror()
        gate.receive(null, unreachable = true)
        check("an unreachable provider decides nothing", !gate.decided)
        check("an unreachable provider builds nothing", gate.inGameSurfaces == 0)
        gate.receive("hook")
        check("a later answer still decides", gate.hookSelected)
    }
    run {
        val gate = GateMirror()
        gate.receive("overlay", error = "badge_overlay_failed")
        check("the game knows why the overlay is missing", gate.overlayError == "badge_overlay_failed")
        check("a broken overlay still hides the in-game surface", gate.mode == GateMirror.Mode.OVERLAY)
    }

    // 5. A reply whose display source is UNKNOWN is refused, never read as "hook".
    //    (Regression: the manager used to default an unreadable snapshot to HOOK, and the game
    //    process then locked itself to the in-game surface.)
    run {
        val gate = GateMirror()
        gate.requestMode()
        check("an ask is in flight, nothing is drawn yet",
            gate.inFlight == 1 && gate.inGameSurfaces == 0 && !gate.decided)
        val accepted = gate.complete("hook", source = GateMirror.SOURCE_UNKNOWN)
        check("source UNKNOWN is not accepted as an answer", !accepted) { "gate decided hook" }
        check("source UNKNOWN leaves the gate undecided", !gate.decided && gate.mode == GateMirror.Mode.UNKNOWN)
        check("source UNKNOWN builds nothing", gate.inGameSurfaces == 0)
        gate.handOver()
        check("source UNKNOWN never hands the surface over", !gate.handedOver)
        // A later valid answer still decides, including hook.
        gate.complete("hook", source = GateMirror.SOURCE_SERVICE)
        check("a valid reply after an UNKNOWN one decides", gate.hookSelected)
        gate.handOver()
        check("a valid hook reply builds the surface", gate.inGameSurfaces == 1)
    }
    run {
        val gate = GateMirror()
        // An older manager that omits `displaySource` but sends a real mode is accepted.
        gate.receive("overlay", source = null)
        check("a source-less reply with a real mode is accepted",
            gate.decided && gate.mode == GateMirror.Mode.OVERLAY) {
            "gate=${gate.mode}"
        }
    }
    run {
        val gate = GateMirror()
        gate.receive(null, source = GateMirror.SOURCE_UNKNOWN)
        check("a reply with no mode stays undecided", !gate.decided)
        gate.receive("", source = GateMirror.SOURCE_SERVICE)
        check("a blank mode stays undecided", !gate.decided)
        gate.receive("bogus", source = GateMirror.SOURCE_SERVICE)
        check("an unknown mode token stays undecided", !gate.decided)
    }

    // 6. Asynchronous single-flight asking: a resume while unknown re-asks, but never stacks,
    //    and the gate does not draw while waiting.
    run {
        val gate = GateMirror()
        gate.requestMode()
        gate.requestMode()
        check("a second ask while one is pending is suppressed", gate.inFlight == 1 && gate.asks == 1)
        gate.complete(null, unreachable = true)
        check("an unanswered ask frees the slot for a retry", gate.inFlight == 0 && !gate.decided)
        gate.requestMode()
        check("the retry is armed as a new ask", gate.asks == 2 && gate.inFlight == 1)
        gate.complete("overlay", source = GateMirror.SOURCE_SERVICE)
        gate.requestMode()
        check("a decided gate does not ask again", gate.asks == 2 && gate.suppressedAsks >= 2)
        check("the decided overlay gate builds nothing", gate.inGameSurfaces == 0)
    }

    // 7. Any failure of an overlay window means "no overlay error cleared": the completeness
    //    rule the service uses (all three windows or none).
    run {
        AssistantDisplayRun.reset()
        AssistantDisplayRun.start(AssistantDisplayMode.OVERLAY, 50L, "e")
        fun complete(floating: Boolean, badge: Boolean, panel: Boolean): Boolean =
            floating && badge && panel
        check("all three windows up is a complete overlay", complete(true, true, true))
        check("missing the badge is incomplete", !complete(true, false, true))
        check("missing the panel is incomplete", !complete(true, true, false))
        check("missing the floating window is incomplete", !complete(false, true, true))
        AssistantDisplayRun.noteOverlayError("recommendation_panel_failed")
        check("a panel failure is reported as the overlay error",
            DisplayModeSnapshotStore.ofService()?.overlayError == "recommendation_panel_failed")
        check("a failed overlay is still overlay mode",
            DisplayModeSnapshotStore.ofService()?.mode == AssistantDisplayMode.OVERLAY)
        AssistantDisplayRun.finish(50L)
    }

    // 8. Overlay mode does not touch hook-mode surfaces even when the run reports an error.
    run {
        AssistantDisplayRun.reset()
        AssistantDisplayRun.start(AssistantDisplayMode.OVERLAY, 40L, "d")
        AssistantDisplayRun.noteOverlayError("floating_window_failed")
        val gate = GateMirror()
        gate.receive("overlay", error = "floating_window_failed")
        gate.handOver()
        check("an overlay error never creates the hook surface", gate.inGameSurfaces == 0)
        AssistantDisplayRun.finish(40L)
    }

    println("=== $passed passed, $failed failed ===")
    if (failed > 0) kotlin.system.exitProcess(1)
}
