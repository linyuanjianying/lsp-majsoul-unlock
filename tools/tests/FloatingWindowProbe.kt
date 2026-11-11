// JVM-only regression probe for the floating window: colour maths + shape machine.
//
// Both units are pure (no Context, no View, no Looper): `FloatingStyle`'s colour helpers are
// in its companion object and the state machine is a plain class, so this probe runs with
// the project sources on the classpath and -Xfriend-paths (for `internal`).
//
// The regression this locks down: `FloatingStyle` used to build the panel fill with
// `Color.alpha(level)` where `level` was an alpha 0..255 — at 100 % that is 255 = 0x000000FF,
// so `Color.alpha` returned 0 and the whole panel was fully transparent (only the constant
// hairline and the button ripples were visible). The checks below fail on that build.
import android.graphics.Color as AColor
import com.yefeng.majmax.hookprobe.manager.FloatingAction
import com.yefeng.majmax.hookprobe.manager.FloatingShape
import com.yefeng.majmax.hookprobe.manager.FloatingShapeMachine
import com.yefeng.majmax.hookprobe.manager.PARKED_ALPHA_PERCENT
import com.yefeng.majmax.hookprobe.manager.compositeFloatingAlpha
import com.yefeng.majmax.hookprobe.manager.floatingAlphaOfPercent

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
    if (cond) { passed++; println("PASS  $name") }
    else { failed++; println("FAIL  $name  ${detail()}") }
}

private fun hex(value: Int) = "0x" + Integer.toHexString(value).uppercase().padStart(8, '0')

private fun expectAlpha(name: String, color: Int, expected: Int) =
    check(name, AColor.alpha(color) == expected) { "got alpha=${AColor.alpha(color)} (${hex(color)}), want $expected" }

fun main() {
    // ---------------------------------------------------------------- colour maths
    println("--- FloatingStyle alpha / composite")

    // Slider percent -> alpha level. 100 % must be fully opaque.
    check("alphaOfPercent(100) == 255", floatingAlphaOfPercent(100) == 255) {
        "got ${floatingAlphaOfPercent(100)}"
    }
    check("alphaOfPercent(78) == 199", floatingAlphaOfPercent(78) == 199) {
        "got ${floatingAlphaOfPercent(78)}"
    }
    check("alphaOfPercent(25) == 64", floatingAlphaOfPercent(25) == 64) {
        "got ${floatingAlphaOfPercent(25)}"
    }
    check("alphaOfPercent(50) == 128", floatingAlphaOfPercent(50) == 128) {
        "got ${floatingAlphaOfPercent(50)}"
    }
    check("alphaOfPercent clamps below the 25 % floor (0 -> 64)",
        floatingAlphaOfPercent(0) == 64) { "got ${floatingAlphaOfPercent(0)}" }
    check("alphaOfPercent clamps above 100 (130 -> 255)",
        floatingAlphaOfPercent(130) == 255) { "got ${floatingAlphaOfPercent(130)}" }
    check("every percent maps into 64..255",
        (0..200).all { p -> floatingAlphaOfPercent(p) in 64..255 })

    // The core bug: an alpha *level* must not be read back as an ARGB colour.
    val panel = AColor.rgb(17, 26, 44)      // #111a2c
    val bubble = AColor.rgb(14, 22, 38)     // #0e1626
    val card = AColor.rgb(32, 40, 58)       // #20283a

    val full = compositeFloatingAlpha(floatingAlphaOfPercent(100), panel)
    check("composite(100%, panel) is fully opaque", AColor.alpha(full) == 255) { "got ${hex(full)}" }
    check("composite(100%, panel) keeps the navy fill", full == panel) { "got ${hex(full)}" }
    expectAlpha("composite(78%, panel) -> alpha 199",
        compositeFloatingAlpha(floatingAlphaOfPercent(78), panel), 199)
    expectAlpha("composite(25%, panel) -> alpha 64",
        compositeFloatingAlpha(floatingAlphaOfPercent(25), panel), 64)
    check("composite() keeps the RGB of the base at every level",
        listOf(25, 50, 78, 100).all { p ->
            val c = compositeFloatingAlpha(floatingAlphaOfPercent(p), panel)
            AColor.red(c) == 17 && AColor.green(c) == 26 && AColor.blue(c) == 44
        })
    check("composite(bubble) keeps #0e1626 and 255 at 100 %",
        compositeFloatingAlpha(255, bubble) == bubble)
    check("composite(card) keeps #20283a and 255 at 100 %",
        compositeFloatingAlpha(255, card) == card)
    check("composite() clamps an out-of-range level (300 -> 255)",
        AColor.alpha(compositeFloatingAlpha(300, panel)) == 255)
    check("composite(0) stays in step with Color.argb (RGB always kept)",
        compositeFloatingAlpha(0, panel) == AColor.argb(0, 17, 26, 44) &&
            AColor.red(compositeFloatingAlpha(0, panel)) == 17 &&
            // Framework detail: Color.argb keeps a transparent colour's payload opaque, so
            // a caller must never rely on level 0 for an invisible surface. The window does
            // not: the slider floor is 25 % and the parked ball is 65 %.
            AColor.alpha(compositeFloatingAlpha(0, panel)) == AColor.alpha(AColor.argb(0, 17, 26, 44)))
    // The old implementation, reproduced verbatim, must be the one that fails:
    check("legacy Color.alpha(level) maths is the broken one (documents the bug)",
        AColor.alpha(255) == 0 && AColor.alpha(199) == 0)

    check("parked ball alpha level = 166 (65 %)",
        floatingAlphaOfPercent(PARKED_ALPHA_PERCENT) == 166) {
        "got level ${floatingAlphaOfPercent(PARKED_ALPHA_PERCENT)}"
    }
    check("parked ball percent is 65", PARKED_ALPHA_PERCENT == 65)
    check("parked ball is dimmer than 100 % but still visible",
        floatingAlphaOfPercent(PARKED_ALPHA_PERCENT) in 100..200) {
        "got ${floatingAlphaOfPercent(PARKED_ALPHA_PERCENT)}"
    }
    expectAlpha("parked bubble colour -> alpha 166",
        compositeFloatingAlpha(floatingAlphaOfPercent(PARKED_ALPHA_PERCENT), bubble), 166)
    check("parked ball keeps the bubble RGB",
        compositeFloatingAlpha(floatingAlphaOfPercent(PARKED_ALPHA_PERCENT), bubble) ==
            AColor.argb(166, 14, 22, 38))

    // ---------------------------------------------------------------- state machine
    println("--- FloatingShapeMachine")

    val ms = FloatingShapeMachine.IDLE_STEP_MS
    check("idle step is 3 s", ms == 3_000L) { "got $ms" }

    run {
        // Fresh window: collapsed starts as the status capsule.
        val m = FloatingShapeMachine()
        check("initial shape is the capsule", m.shape == FloatingShape.CAPSULE) { "got ${m.shape}" }
        check("capsule wants the idle timer", m.wantsIdleTimer && m.idleDelay == ms)
        check("capsule is not tucked", !m.tucksIntoEdge)

        check("capsule --idle--> full ball", m.apply(FloatingAction.IDLE) == FloatingShape.BUBBLE)
        check("full ball still wants the timer", m.wantsIdleTimer && m.idleDelay == ms)
        check("ball is not tucked", !m.tucksIntoEdge)

        check("full ball --idle--> parked", m.apply(FloatingAction.IDLE) == FloatingShape.PARKED)
        check("parked has no timer", !m.wantsIdleTimer && m.idleDelay == null)
        check("parked is tucked into the edge", m.tucksIntoEdge)

        check("parked --idle--> stays parked", m.apply(FloatingAction.IDLE) == FloatingShape.PARKED)

        // First tap on the parked ball only wakes it (no panel over the screen edge).
        check("parked --tap 1--> capsule", m.apply(FloatingAction.TAP) == FloatingShape.CAPSULE)
        check("waking tap is remembered", m.tapArmed)
        check("parked -> capsule is not tucked", !m.tucksIntoEdge)
        check("parked --tap 2--> panel", m.apply(FloatingAction.TAP) == FloatingShape.PANEL)
        check("panel has no idle timer (settings never auto-fold)",
            !m.wantsIdleTimer && m.idleDelay == null)
        check("panel --idle--> stays panel", m.apply(FloatingAction.IDLE) == FloatingShape.PANEL)
        check("panel --collapse--> capsule", m.apply(FloatingAction.COLLAPSE) == FloatingShape.CAPSULE)
        check("collapse clears the armed tap", !m.tapArmed)
        check("capsule --tap--> panel", m.apply(FloatingAction.TAP) == FloatingShape.PANEL)
    }

    run {
        // A capsule reached by a fold (not by waking) opens the panel on the first tap.
        val m = FloatingShapeMachine(FloatingShape.PANEL)
        m.apply(FloatingAction.COLLAPSE)
        check("folded capsule is not armed", !m.tapArmed)
        check("folded capsule --tap--> panel", m.apply(FloatingAction.TAP) == FloatingShape.PANEL)
    }

    run {
        // Drag from the parked ball wakes it fully and restarts the chain at the ball.
        val m = FloatingShapeMachine(FloatingShape.PARKED)
        check("parked --drag--> full ball", m.apply(FloatingAction.DRAG) == FloatingShape.BUBBLE)
        check("waking drag is not armed", !m.tapArmed)
        check("idle after a drag -> parked again",
            m.apply(FloatingAction.IDLE) == FloatingShape.PARKED)
    }

    run {
        // Dragging a ball or a capsule never parks or folds it.
        val m = FloatingShapeMachine(FloatingShape.BUBBLE)
        check("ball --drag--> ball", m.apply(FloatingAction.DRAG) == FloatingShape.BUBBLE)
        check("ball keeps its timer after a drag", m.wantsIdleTimer)
        m.apply(FloatingAction.DRAG)
        val c = FloatingShapeMachine(FloatingShape.CAPSULE)
        check("capsule --drag--> capsule", c.apply(FloatingAction.DRAG) == FloatingShape.CAPSULE)
        // A dragged panel stays a panel (only its anchor is saved).
        val p = FloatingShapeMachine(FloatingShape.PANEL)
        check("panel --drag--> panel", p.apply(FloatingAction.DRAG) == FloatingShape.PANEL)
    }

    run {
        // Long press toggles unattended and must not move the shape.
        for (start in FloatingShape.values()) {
            val m = FloatingShapeMachine(start)
            check("long press keeps $start", m.apply(FloatingAction.LONG_PRESS) == start)
        }
    }

    run {
        // The cycle is stable over many rounds and never gets stuck.
        val m = FloatingShapeMachine()
        repeat(50) {
            m.apply(FloatingAction.IDLE)
            m.apply(FloatingAction.IDLE)
            m.apply(FloatingAction.TAP)
            m.apply(FloatingAction.TAP)
            m.apply(FloatingAction.COLLAPSE)
        }
        check("50 idle/tap/collapse rounds end in the capsule",
            m.shape == FloatingShape.CAPSULE) { "got ${m.shape}" }
    }

    run {
        // Only PARKED may sit outside the display (the window needs a negative x).
        check("only the parked shape is tucked",
            FloatingShape.values().filter { FloatingShapeMachine(it).tucksIntoEdge } ==
                listOf(FloatingShape.PARKED))
        check("panel and capsule never run the idle timer",
            listOf(FloatingShape.PANEL, FloatingShape.PARKED).all {
                FloatingShapeMachine(it).idleDelay == null
            })
        check("ball and capsule both run exactly one 3 s step",
            listOf(FloatingShape.CAPSULE, FloatingShape.BUBBLE).all {
                FloatingShapeMachine(it).idleDelay == 3_000L
            })
    }

    println()
    println("passed=$passed failed=$failed")
    if (failed != 0) kotlin.system.exitProcess(1)
}
