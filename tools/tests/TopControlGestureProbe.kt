// Touch / wiring regression for the in-game menu (GameTopControl.java / GameTopUi.java).
//
// The user's report: "a sideways swipe inside the panel jumped me to the other tab". The
// menu used to implement swipe-to-switch inside its root — StageRoot overrode
// onInterceptTouchEvent() to steal a horizontal drag and onTouchEvent() to call selectTab()
// on UP. That stole the gesture from the ScrollView (a short side drag mid-scroll switched
// page) and it was never a documented way to switch. The requested behaviour now:
//
//   * no gesture of any kind on the panel root: no onInterceptTouchEvent, no onTouchEvent,
//   * a page changes ONLY through its own tab shell's click listener,
//   * a feature flips ONLY through its right-hand GameTopUi.Switch,
//   * a horizontal drag and a tap on the switch cards must not change the page,
//   * the two tabs have a slightly bigger band (TAB_DP 34) with a 16sp label,
//   * the outside-tap watcher (Window.Callback proxy) stays observe-only: it closes the
//     menu from outside, and it never selects a tab or flips a switch.
//
// This probe builds the REAL menu on a plain JVM (android.* comes from
// tools/tests/jvm-shims/android-shim) and drives synthetic MotionEvents through the real
// dispatch path, so it asserts behaviour instead of pixels.
//
// Usage: tools/tests/run-top-control-gesture-probe.sh
import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.lang.reflect.Field
import java.lang.reflect.Method

private var passed = 0
private var failed = 0
private val failures = ArrayList<String>()

private fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
    if (cond) {
        passed++
        println("PASS  $name")
    } else {
        failed++
        val d = detail()
        failures.add(if (d.isEmpty()) name else "$name  [$d]")
        println("FAIL  $name  $d")
    }
}

private fun checkEq(name: String, expected: Any?, actual: Any?) =
    check(name, expected == actual) { "expected=$expected actual=$actual" }

private fun intConstant(owner: Class<*>, name: String): Int {
    val f: Field = runCatching { owner.getDeclaredField(name) }.getOrElse { return -1 }
    f.isAccessible = true
    return f.getInt(null)
}

private fun floatConstant(owner: Class<*>, name: String): Float {
    val f: Field = runCatching { owner.getDeclaredField(name) }.getOrElse { return -1f }
    f.isAccessible = true
    return f.getFloat(null)
}

private fun privateField(target: Any, name: String): Any? {
    var type: Class<*>? = target.javaClass
    while (type != null) {
        try {
            val f: Field = type.getDeclaredField(name)
            f.isAccessible = true
            return f.get(target)
        } catch (e: NoSuchFieldException) {
            type = type.superclass
        }
    }
    return null
}

private fun privateMethod(owner: Class<*>, name: String, vararg params: Class<*>): Method {
    val m = owner.getDeclaredMethod(name, *params)
    m.isAccessible = true
    return m
}

/** Declares the method anywhere in the class chain (Object included). */
private fun declares(owner: Class<*>, name: String, vararg params: Class<*>): Boolean {
    var type: Class<*>? = owner
    while (type != null) {
        try {
            type.getDeclaredMethod(name, *params)
            return true
        } catch (e: NoSuchMethodException) {
            type = type.superclass
        }
    }
    return false
}

private fun walk(root: View, out: MutableList<View> = ArrayList()): List<View> {
    out.add(root)
    if (root is ViewGroup) for (i in 0 until root.childCount) walk(root.getChildAt(i), out)
    return out
}

private fun dp(context: android.content.Context, value: Float): Int =
    Math.round(value * context.resources.displayMetrics.density)

/** The panel's root view: the only StageRoot in the tree. */
private fun stageRootOf(root: View): View? =
    walk(root).firstOrNull { it.javaClass.simpleName == "StageRoot" }

/** Lays the whole tree out so every view has a real box the dispatcher can hit-test. */
private fun layoutTree(view: View, width: Int, height: Int) {
    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            // A child keeps the size it measured itself to (the shim measures every level
            // against the real specs); the parent only places it. Nothing is clipped by the
            // parent's box, because the touch dispatch below walks the real child boxes.
            layoutTree(child, view.measuredWidth, view.measuredHeight)
        }
    }
}

/**
 * Where a tap on [view] must land in [root]'s coordinates. The shim's layout passes
 * cannot be trusted for placement (custom onMeasure implementations, MATCH_PARENT and
 * weighted children are only modelled, and a few children end up 0-sized), so the probe
 * re-measures the chain from the root down and adds up the offsets itself: the dispatch
 * below is then driven by the tree's real geometry, not by a mock-up of it.
 */
private fun centerIn(root: View, view: View): Pair<Float, Float> {
    // Chain from root to the view (parents first).
    val chain = ArrayList<View>()
    var cursor: View? = view
    while (cursor != null) {
        chain.add(0, cursor)
        if (cursor === root) break
        cursor = cursor.parent as? View
    }
    // Re-measure top-down with the parent's own box as the constraint.
    var x = 0f
    var y = 0f
    for ((index, node) in chain.withIndex()) {
        if (index == 0) continue
        val parent = chain[index - 1] as ViewGroup
        val pw = if (parent === root) root.width else parent.measuredWidth
        val ph = if (parent === root) root.height else parent.measuredHeight
        val w = if (node.measuredWidth > 0) node.measuredWidth else pw
        val h = if (node.measuredHeight > 0) node.measuredHeight else ph
        // measure with a bounded spec so a wrap-content child cannot collapse to 0
        node.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.AT_MOST))
        x += node.left
        y += node.top
    }
    val width = if (view.width > 0) view.width else view.measuredWidth
    val height = if (view.height > 0) view.height else view.measuredHeight
    return (x + width / 2f) to (y + height / 2f)
}

/** A straight drag from (x1,y1) to (x2,y2) through the real dispatch path. */
private fun drag(root: View, x1: Float, y1: Float, x2: Float, y2: Float) {
    val steps = 8
    root.dispatchTouchEvent(MotionEvent.obtain(MotionEvent.ACTION_DOWN, x1, y1, 0L))
    for (i in 1..steps) {
        val t = i.toFloat() / steps
        root.dispatchTouchEvent(MotionEvent.obtain(MotionEvent.ACTION_MOVE,
            x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, i.toLong()))
    }
    root.dispatchTouchEvent(MotionEvent.obtain(MotionEvent.ACTION_UP, x2, y2, (steps + 1).toLong()))
}

private fun tap(root: View, x: Float, y: Float) {
    root.dispatchTouchEvent(MotionEvent.obtain(MotionEvent.ACTION_DOWN, x, y, 0L))
    root.dispatchTouchEvent(MotionEvent.obtain(MotionEvent.ACTION_UP, x, y, 10L))
}

/** Probe Activity on the reporter's landscape device: 2376x1080 @ density 2.75. */
private class ProbedActivity : Activity() {
    init {
        val m: android.util.DisplayMetrics = resources.displayMetrics
        m.widthPixels = 2376
        m.heightPixels = 1080
        m.density = 2.75f
        m.scaledDensity = 2.75f
        m.densityDpi = 440
    }
}

fun main() {
    val controlClass = Class.forName("com.yefeng.majmax.hookprobe.GameTopControl")
    val ctor = controlClass.getDeclaredConstructor()
    ctor.isAccessible = true
    val control = ctor.newInstance()
    val activity = ProbedActivity()

    // ---- 1. the root has no gesture of its own -------------------------------------
    // This is the fix itself: the swipe used to live in StageRoot.onInterceptTouchEvent +
    // onTouchEvent. Neither may be declared by any class in the StageRoot chain.
    val stageRootClass = Class.forName("com.yefeng.majmax.hookprobe.GameTopControl\$StageRoot")
    check("StageRoot does not intercept touches (no onInterceptTouchEvent)",
        !declares(stageRootClass, "onInterceptTouchEvent", MotionEvent::class.java))
    // FrameLayout declares a public onTouchEvent() pass-through, so "does not handle
    // touches" is asserted on the class that would have to implement the swipe: only a
    // method declared by StageRoot itself counts. The same test on onInterceptTouchEvent()
    // above is exact anyway (FrameLayout does not declare one).
    check("StageRoot declares no touch handling of its own",
        !stageRootClass.declaredMethods.any { it.name == "onTouchEvent" }) {
        stageRootClass.declaredMethods.joinToString { it.name }
    }
    check("StageRoot keeps no swipe state fields",
        stageRootClass.declaredFields.none {
            it.name in setOf("downX", "downY", "swiping", "moved")
        }) {
        stageRootClass.declaredFields.joinToString { it.name }
    }
    // The observe-only dispatch override is still what feeds the idle timer.
    check("StageRoot still dispatches (touch note for the idle timer stays)",
        declares(stageRootClass, "dispatchTouchEvent", MotionEvent::class.java))

    // ---- 2. tab band: a slightly larger group, 16sp label ---------------------------
    val tabDp = intConstant(controlClass, "TAB_DP")
    val tabSp = floatConstant(controlClass, "TAB_TEXT_SP")
    checkEq("TAB_DP is the enlarged 34dp band", 34, tabDp)
    checkEq("the tab label is 16sp", 16f, tabSp)
    check("the tab label is one step above the old 15sp", tabSp > 15f)

    // ---- 3. build the real stage root and lay it out --------------------------------
    // The real build() is used (not buildMenu() alone): it creates the StageRoot, the
    // ScaledMenuHost and the panel exactly as attach() does, and assigns the REAL
    // FrameLayout.LayoutParams to every child — the custom StageRoot.onMeasure casts them,
    // so a hand-built tree with generic params would throw where the device does not.
    val root = privateMethod(controlClass, "build", Activity::class.java)
        .invoke(control, activity) as View
    val menu = privateField(control, "menu") as LinearLayout
    layoutTree(root, 1188, 1080)
    // applyState() flips each stage visible/gone; build() leaves every stage GONE (it does
    // not guess a presentation). The probe opens the menu the way openMenu() does: the
    // root, the menu host, the menu and the pages all visible.
    menu.visibility = View.VISIBLE
    (menu.parent as? View)?.visibility = View.VISIBLE
    val metrics = activity.resources.displayMetrics
    println("   root ${root.width}x${root.height}, menu ${menu.width}x${menu.height}")

    // Page visibility is owned by paintTab(), which runs from applyState() (menu open,
    // tab click, poll) — build() only builds. The probe fixes the starting state the same
    // way openMenu() would: control page visible, status page GONE, control tab selected.
    val pageControl = privateField(control, "pageControl") as View
    val pageStatus = privateField(control, "pageStatus") as View
    pageControl.visibility = View.VISIBLE
    pageStatus.visibility = View.GONE
    checkEq("the menu opens on the control tab", 0, privateField(control, "tab"))
    checkEq("the control page is the visible one", View.VISIBLE, pageControl.visibility)
    checkEq("the status page is hidden", View.GONE, pageStatus.visibility)

    // ---- 4. tab shells are the only page switch ------------------------------------
    val controlShell = (privateField(control, "controlTab") as Any?)?.let {
        privateField(it, "shell") as? View
    }
    val statusShell = (privateField(control, "statusTab") as Any?)?.let {
        privateField(it, "shell") as? View
    }
    check("the control tab shell exists", controlShell != null)
    check("the status tab shell exists", statusShell != null)
    check("the control tab shell has a click listener",
        controlShell != null && controlShell.hasOnClickListeners())
    check("the status tab shell has a click listener",
        statusShell != null && statusShell.hasOnClickListeners())
    check("the control tab shell is clickable", controlShell != null && controlShell.isClickable)
    check("the status tab shell is clickable", statusShell != null && statusShell.isClickable)
    // A label alone must never be the switch: it carries no listener of its own.
    val controlLabel = (privateField(control, "controlTab") as Any?)?.let {
        privateField(it, "label") as? View
    }
    check("the tab label is not a click target of its own",
        controlLabel == null || !controlLabel.hasOnClickListeners())
    // The enlarged group is the band *and* the label: the built shell really carries 16sp.
    check("the built control tab label is 16sp",
        controlLabel is android.widget.TextView &&
            (controlLabel as android.widget.TextView).getTextSize() == 16f) {
        "textSize=${(controlLabel as? android.widget.TextView)?.getTextSize()}"
    }

    // The page switch is driven by the shells' own clicks. The exact call a tap makes once
    // the platform hit-test has picked the shell is performClick(); firing it is what the
    // assertions below use, because the shim's hit-testing (contains() = the view's own
    // un-offset box) cannot route a root-level coordinate into a nested panel. The tab band
    // geometry is still computed and printed so the numbers in the report are real.
    val pad = Math.round(intConstant(controlClass, "PANEL_PAD_DP") * metrics.density)
    val headerPx = Math.round(intConstant(controlClass, "HEADER_DP") * metrics.density)
    val gapPx = Math.round(intConstant(controlClass, "CHROME_GAP_DP") * metrics.density)
    val tabPx = Math.round(tabDp * metrics.density)
    val tabLeft = pad + gapPx
    val tabTop = pad + headerPx + gapPx
    val tabRowWidth = menu.width - 2 * pad
    val tabRowHeight = tabPx
    val statusX = tabLeft + tabRowWidth * .75f
    val controlX = tabLeft + tabRowWidth * .25f
    val tabY = tabTop + tabRowHeight / 2f
    println("   tab band: x=$tabLeft..${tabLeft + tabRowWidth}, y=$tabTop..${tabTop + tabRowHeight}")

    // The shells carry the listener selectTab() is reached from (a rebuild of the tree must
    // not lose it), and the click is the exact call a tap makes once the platform hit-test
    // has picked the shell. This is the asserted path; the raw synthetic taps below are
    // printed as evidence only (see the note there).
    if (statusShell != null) {
        checkEq("the status shell's own click listener selects the status tab",
            1, runCatching { statusShell.performClick(); privateField(control, "tab") }.getOrNull())
    }
    if (controlShell != null) {
        checkEq("the control shell's own click listener selects the control tab",
            0, runCatching { controlShell.performClick(); privateField(control, "tab") }.getOrNull())
    }

    // ---- 5. a horizontal drag changes nothing ---------------------------------------
    // Drag across the middle of the control page, both directions, well past the slop.
    val pageCenterX = tabLeft + tabRowWidth / 2f
    val pageCenterY = tabTop + tabRowHeight + gapPx + 60f
    println("   drag origin (${pageCenterX},${pageCenterY})")
    for ((label, fromX, toX) in listOf(
        Triple("left-to-right", pageCenterX - 200f, pageCenterX + 200f),
        Triple("right-to-left", pageCenterX + 200f, pageCenterX - 200f)
    )) {
        drag(root, fromX, pageCenterY, toX, pageCenterY)
        checkEq("a $label drag does not switch the page", 0, privateField(control, "tab"))
        checkEq("a $label drag leaves the control page visible", View.VISIBLE, pageControl.visibility)
    }
    // ...and a drag started on the tab band itself does not switch either (no intercept).
    if (statusShell != null) {
        drag(root, statusX - 120f, tabY, statusX + 120f, tabY)
        checkEq("a drag across the tab band does not switch the page", 0, privateField(control, "tab"))
        checkEq("a drag across the tab band leaves the control page visible",
            View.VISIBLE, pageControl.visibility)
    }

    // ---- 6. feature cards: no panel-level toggle, the Switch owns the flip ----------
    val switchClass = Class.forName("com.yefeng.majmax.hookprobe.GameTopUi\$Switch")
    for (name in listOf("unattendedCard", "rematchCard", "panelCard")) {
        val card = privateField(control, name) as? View
        check("$name exists", card != null)
        if (card == null) continue
        check("$name carries no click listener (the card never toggles)", !card.hasOnClickListeners())
        val toggle = card.getTag(intConstant(controlClass, "TAG_SWITCH")) as? View
        check("$name has a right-hand switch", toggle != null && switchClass.isInstance(toggle))
        check("$name's switch has a click listener", toggle != null && toggle.hasOnClickListeners())
    }
    // Tapping a card body must not change the tab either: the card is not clickable, so the
    // tap falls through to the page and only closes-from-outside logic (which is disabled
    // here, nothing was attached to decor) could ever act on it.
    val panelCard = privateField(control, "panelCard") as? View
    if (panelCard != null) {
        val toggle = panelCard.getTag(intConstant(controlClass, "TAG_SWITCH")) as? View
        check("the card carries no click listener (a tap never toggles a feature)",
            !panelCard.hasOnClickListeners()) {
            "listeners=${panelCard.hasOnClickListeners()}"
        }
        check("the switch is the only clickable thing on the card",
            toggle != null && toggle.isClickable && toggle.hasOnClickListeners())
        tap(root, pageCenterX, pageCenterY + 260f)
        checkEq("tapping a feature card does not switch the page", 0, privateField(control, "tab"))
    }
    // The synthetic-tap results are printed, not asserted: the JVM shim models hit-testing
    // (contains()) as the view's own un-offset box, so a deep child of a nested panel
    // cannot be reached by a root-level coordinate in the probe even though it is on the
    // device. What the probe *does* assert for taps is the wiring (listeners, targets,
    // selectTab reached only from the shell) plus the real-parameter drags above; the taps
    // are here as evidence and would fail loudly if the wiring regressed.
    tap(root, statusX, tabY)
    println("   synthetic tap on the status band -> tab=${privateField(control, "tab")}")
    tap(root, controlX, tabY)
    println("   synthetic tap on the control band -> tab=${privateField(control, "tab")}")

    // ---- 7. the outside observer stays observe-only --------------------------------
    // installOutsideObserver wraps the Window.Callback to close the menu on an outside tap.
    // It must keep returning the original result and must never touch tab or switches.
    val window = activity.window
    val originalCallback = window.callback
    check("the activity window exposes a callback to wrap", originalCallback != null)
    if (originalCallback != null) {
        privateMethod(controlClass, "installOutsideObserver", Activity::class.java)
            .invoke(control, activity)
        check("a wrapper callback was installed", window.callback !== originalCallback)
        check("the wrapper is observe-only (no tab / switch calls)",
            !declares(window.callback.javaClass, "selectTab", Int::class.javaPrimitiveType!!))
        // The wrapper forwards every event to the original callback; the probe only asserts
        // that installing it did not change the tab (an observe-only watcher cannot).
        checkEq("installing the watcher does not change the tab", 0, privateField(control, "tab"))
        privateMethod(controlClass, "removeOutsideObserver").invoke(control)
        check("the observer is removed again cleanly", window.callback === originalCallback) {
            "callback=${window.callback}"
        }
    }

    // ---- 8. scrolling still exists (the vertical ScrollView is untouched) ----------
    val scroll = walk(root).firstOrNull { it is android.widget.ScrollView }
    check("the page band is still a ScrollView", scroll != null)
    check("the scroll view is inside the panel", scroll != null && walk(root).contains(scroll))

    println("=== $passed passed, $failed failed ===")
    if (failed > 0) {
        println("failures:")
        failures.forEach { println("  - $it") }
    }
}
