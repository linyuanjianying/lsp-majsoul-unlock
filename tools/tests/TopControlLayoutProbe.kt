// Layout regression for the in-game menu (GameTopControl.java / GameTopUi.java).
//
// The user's device is a 2376x1080 landscape window at density 2.75, so the menu is drawn
// through ScaledMenuHost at .75: 998x648 visible px, i.e. a 1330x864 pre-scale box. The
// previous build spent the top of that box on chrome — ~160px of header and ~135px per
// row — so only the first switch card was visible without scrolling. This probe builds the
// REAL menu on a plain JVM (android.* comes from tools/tests/jvm-shims/android-shim, whose
// measure passes are faithful enough for structure) and asserts the geometry:
//
//   * the window box (42% x 60% of the screen at SCALE .75) is unchanged,
//   * header + tab + gutters + panel padding <= 28% of the menu height (the control/status
//     tab group was enlarged: TAB_DP 22 -> 34, label 15sp -> 16sp),
//   * the scrollable content keeps >= 65% of the menu height (>= 420 visible px),
//   * the first three switch cards are all reachable in the first screenful,
//   * the fixed trailing collapse row is gone, and nothing else grew a 60dp box,
//   * the handle is a wide, flat, top-anchored pill inside a wider touch box.
//
// Text rows are measured by the shim as padding + 1.22*1.10*textSizePx (no font on the
// JVM), so the px numbers below are structure, not a pixel-exact mock-up: the assertions
// are written against ratios and deltas that hold for any sane font metrics.
//
// Usage: tools/tests/run-top-control-layout-probe.sh
import android.app.Activity
import android.util.DisplayMetrics
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.reflect.Constructor
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

/** Constant of the control class; -1 when the build predates it (old-layout baseline). */
private fun intConstant(owner: Class<*>, name: String): Int {
    val f: Field = runCatching { owner.getDeclaredField(name) }.getOrElse { return -1 }
    f.isAccessible = true
    return f.getInt(null)
}

private fun longConstant(owner: Class<*>, name: String): Long {
    val f: Field = runCatching { owner.getDeclaredField(name) }.getOrElse { return -1L }
    f.isAccessible = true
    return f.getLong(null)
}

private fun hasField(owner: Class<*>, name: String): Boolean {
    var type: Class<*>? = owner
    while (type != null && type != Any::class.java) {
        if (type.declaredFields.any { it.name == name }) return true
        type = type.superclass
    }
    return false
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

private fun newInstance(owner: Class<*>, paramTypes: Array<Class<*>>, vararg args: Any?): Any {
    val ctor: Constructor<*> = owner.getDeclaredConstructor(*paramTypes)
    ctor.isAccessible = true
    return ctor.newInstance(*args)
}

private fun walk(root: View, out: MutableList<View> = ArrayList()): List<View> {
    out.add(root)
    if (root is ViewGroup) for (i in 0 until root.childCount) walk(root.getChildAt(i), out)
    return out
}

/** Views the panel actually paints (GONE children take no space). */
private fun visibleTree(root: View, out: MutableList<View> = ArrayList()): List<View> {
    if (root.visibility == View.GONE) return out
    out.add(root)
    if (root is ViewGroup) for (i in 0 until root.childCount) visibleTree(root.getChildAt(i), out)
    return out
}

private fun visibleChildren(parent: ViewGroup): List<View> =
    (0 until parent.childCount).map { parent.getChildAt(it) }.filter { it.visibility != View.GONE }

private fun childrenOf(parent: View): List<View> =
    if (parent is ViewGroup) (0 until parent.childCount).map { parent.getChildAt(it) } else emptyList()

private fun measureInto(view: View, width: Int, height: Int) {
    view.measure(
        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    view.layout(0, 0, width, height)
}

private fun dp(activity: Activity, value: Float): Int {
    val density = activity.resources.displayMetrics.density
    return (value * density + .5f).toInt()
}

private fun px(view: View, activity: Activity): Int =
    Math.round(view.measuredHeight * .75f)

/** Probe Activity on a 2376x1080 @ 2.75 device, the reporter's landscape screen. */
private class ProbedActivity : Activity() {
    init {
        val m: DisplayMetrics = resources.displayMetrics
        m.widthPixels = 2376
        m.heightPixels = 1080
        m.density = 2.75f
        m.scaledDensity = 2.75f
        m.densityDpi = 440
    }
}

fun main() {
    val controlClass = Class.forName("com.yefeng.majmax.hookprobe.GameTopControl")
    val uiClass = Class.forName("com.yefeng.majmax.hookprobe.GameTopUi")
    val control: Any = newInstance(controlClass, emptyArray())
    val activity = ProbedActivity()
    val density = activity.resources.displayMetrics.density
    val scale = .75f
    println("== device 2376x1080 @ ${density}, SCALE=$scale, dp(1)=${dp(activity, 1f)}px ==")

    // ---- constants: the compact chrome is real, not a scaled TARGET_DP ---------------
    val targetDp = intConstant(controlClass, "TARGET_DP")
    val headerDp = intConstant(controlClass, "HEADER_DP")
    val tabDp = intConstant(controlClass, "TAB_DP")
    val rowDp = intConstant(controlClass, "ROW_DP")
    val gapDp = intConstant(controlClass, "CHROME_GAP_DP")
    val padDp = intConstant(controlClass, "PANEL_PAD_DP")
    println("   TARGET_DP=$targetDp HEADER_DP=$headerDp TAB_DP=$tabDp ROW_DP=$rowDp " +
        "CHROME_GAP_DP=$gapDp PANEL_PAD_DP=$padDp")
    check("header is a compact 70..85 visible px", headerDp in 25..32) {
        "HEADER_DP=$headerDp -> ${Math.round(headerDp * density * scale)}px visible"
    }
    check("tab is a slightly larger group: 30..36dp band (~62..74 visible px)", tabDp in 30..36) {
        "TAB_DP=$tabDp -> ${Math.round(tabDp * density * scale)}px visible"
    }
    check("the global touch target was not shrunk for every row", targetDp >= 44) {
        "TARGET_DP=$targetDp"
    }
    check("the fold window is still 3s for the capsule",
        longConstant(controlClass, "IDLE_CAPSULE_MS") == 3_000L)
    check("the fold window is still 6s for the menu",
        longConstant(controlClass, "IDLE_MENU_MS") == 6_000L)

    // ---- build the real menu --------------------------------------------------------
    val menu = privateMethod(controlClass, "buildMenu", Activity::class.java)
        .invoke(control, activity) as LinearLayout
    val hostClass = Class.forName("com.yefeng.majmax.hookprobe.GameTopControl\$ScaledMenuHost")
    val host = newInstance(hostClass, arrayOf<Class<*>>(android.content.Context::class.java), activity) as FrameLayout
    host.addView(menu, FrameLayout.LayoutParams(dp(activity, 500f), ViewGroup.LayoutParams.WRAP_CONTENT))

    // The stage root measures the host against the window; the visible box comes back as
    // the host's own measured size, so that is what the finger sees.
    measureInto(host, 2376, 1080)
    val visualW = host.measuredWidth
    val visualH = host.measuredHeight
    val preW = menu.measuredWidth
    val preH = menu.measuredHeight
    println("== menu: visible ${visualW}x$visualH px (pre-scale ${preW}x$preH) ==")

    checkEq("visible width is still 42% of the screen", Math.round(2376 * .42f), visualW)
    checkEq("visible height is still 60% of the screen", Math.round(1080 * .60f), visualH)
    checkEq("pre-scale width is the visible width / SCALE", Math.round(visualW / scale), preW)

    val panelChildren = childrenOf(menu)
    println("   panel direct children: " + panelChildren.joinToString {
        "${it.javaClass.simpleName}(${it.measuredWidth}x${it.measuredHeight})"
    })
    val panelPad = dp(activity, padDp.toFloat())
    val gap = dp(activity, gapDp.toFloat())
    // Old builds have no HEADER_DP/TAB_DP: measure the real header/tab rows instead of guessing.
    val headerPx = if (headerDp > 0) dp(activity, headerDp.toFloat()) else panelChildren.getOrNull(0)?.measuredHeight ?: 0
    val tabPx = if (tabDp > 0) dp(activity, tabDp.toFloat()) else panelChildren.getOrNull(2)?.measuredHeight ?: 0
    val topChrome = 2 * panelPad + headerPx + gap + tabPx + gap
    val chromeVisible = Math.round(topChrome * scale)
    val contentVisible = visualH - chromeVisible
    println("== chrome: panel pad ${Math.round(panelPad * scale)}px + header " +
        "${Math.round(headerPx * scale)}px + gutter ${Math.round(gap * scale)}px + tab " +
        "${Math.round(tabPx * scale)}px + gutter ${Math.round(gap * scale)}px = ${chromeVisible}px visible ==")
    println("== content band: ${contentVisible}px of ${visualH}px = " +
        "${(100.0 * contentVisible / visualH).toInt()}% ==")

    // The chrome budget was 25% with the 22dp tab band. The control/status tab group was
    // then enlarged on purpose (TAB_DP 22 -> 34, label 15sp -> 16sp), which costs 24 more
    // visible px: 166px = 25.6% of the menu. The budget moves to 28% so the enlarged group
    // is allowed, while the content band still has to keep >= 65% / >= 420px below — i.e. the
    // header and the enlarged tab can never squeeze the cards out of the first screenful.
    check("chrome (header+tab+gutters+padding) stays under 28% of the menu",
        chromeVisible * 100 <= visualH * 28) { "chrome=$chromeVisible of $visualH" }
    check("the content band keeps at least 65% of the menu height",
        contentVisible * 100 >= visualH * 65) { "$contentVisible of $visualH" }
    check("the content band is at least 420 visible px", contentVisible >= 420) {
        "$contentVisible px"
    }

    // ---- the fixed trailing collapse row is gone ------------------------------------
    val hairline = panelChildren.count { child ->
        child !is LinearLayout && child.measuredHeight in 1..3
    }
    check("no fixed divider hairline is left outside the scroll area", hairline == 0) {
        "$hairline 1dp view(s)"
    }
    check("the fixed bottom collapse row no longer exists",
        !hasField(controlClass, "collapseRow"))

    val body = panelChildren.first { it is LinearLayout &&
        (it.layoutParams as LinearLayout.LayoutParams).height == 0 &&
        (it.layoutParams as LinearLayout.LayoutParams).weight > 0f }
    println("   body (weight 1) measured ${body.measuredWidth}x${body.measuredHeight}")
    checkEq("the scrolling body is the content band", preH - topChrome, body.measuredHeight)

    // The panel's last child must be the body: nothing pinned under the scroll area.
    check("the scroll band is the last thing in the panel",
        panelChildren.last() === body)

    // ---- first screenful: three switch cards ----------------------------------------
    val page = privateField(control, "pageControl") as LinearLayout
    measureInto(page, body.measuredWidth, 20_000)
    val cardNames = listOf("unattendedCard", "rematchCard", "panelCard")
    val cards = cardNames.map { privateField(control, it) as LinearLayout? }
    val rows = visibleChildren(page)
    val tops = ArrayList<Int>()
    var cursor = 0
    for (row in rows) {
        tops.add(cursor)
        cursor += row.measuredHeight
    }
    println("   control page rows: " + rows.joinToString {
        "${it.javaClass.simpleName}(${px(it, activity)}px)"
    })
    for ((index, name) in cardNames.withIndex()) {
        val card = cards[index]
        check("$name exists", card != null)
        if (card == null) continue
        val row = rows.indexOf(card)
        val top = if (row >= 0) Math.round(tops[row] * scale) else -1
        val height = Math.round(card.measuredHeight * scale)
        val state = privateField(control, name.removeSuffix("Card") + "State") as TextView?
        val note = privateField(control, name.removeSuffix("Card") + "Note") as TextView?
        println("   $name: ${height}px visible at y=${top}px, state lines=${state?.maxLines}, " +
            "note lines=${note?.maxLines}")
        check("$name starts inside the first screenful", top in 0 until contentVisible) {
            "top=${top}px content=${contentVisible}px"
        }
        check("$name is short enough that its neighbour peeks in", height in 60..132) {
            "${height}px visible (old layout: one 135px row + a 135px box)"
        }
        check("$name's note line is capped at one row", note == null || note.maxLines == 1)
    }
    val third = cards[2]
    val thirdBottom = if (third != null) {
        val row = rows.indexOf(third)
        Math.round((tops[row] + third.measuredHeight) * scale)
    } else Int.MAX_VALUE
    check("all three switch cards end inside the first screenful",
        thirdBottom > 0 && thirdBottom <= contentVisible) {
        "third card bottom=${thirdBottom}px content=${contentVisible}px"
    }

    // ---- no hidden 60dp boxes left among the page rows -------------------------------
    // The containers (scroll view, page wrappers, the GONE status page) are taller than the
    // cards by design; what mattered in the old build was that a *row* — a card or an action
    // — was 60dp (=135px) tall. So the scan is on the pages' own children.
    val switchClass = Class.forName("com.yefeng.majmax.hookprobe.GameTopUi\$Switch")
    val pageRows = rows.filterNot { it is View && it.layoutParams == null }
    println("   tallest page row: ${pageRows.maxOfOrNull { it.measuredHeight } ?: 0}px pre-scale")
    val tallRows = (rows + childrenOf(page)).distinct()
        .filter { !switchClass.isInstance(it) }
        .filter { it.measuredHeight >= dp(activity, targetDp.toFloat()) }
        .filter { it !== page }
    check("no page row measures a 60dp touch box any more", tallRows.isEmpty()) {
        tallRows.joinToString { "${it.javaClass.simpleName}=${it.measuredHeight}" }
    }

    // The one row that legitimately keeps TARGET_DP is the status page's start/stop cell.
    val startStop = privateField(control, "startStop") as View?
    check("the status page's primary action still keeps a 60dp target",
        startStop != null && startStop.getMinimumHeight() == dp(activity, targetDp.toFloat())) {
        "minHeight=${startStop?.getMinimumHeight()}"
    }

    // ---- the handle: wide, flat, top-anchored ---------------------------------------
    val handleClass = Class.forName("com.yefeng.majmax.hookprobe.GameTopUi\$HandleView")
    val handle = newInstance(handleClass, arrayOf<Class<*>>(android.content.Context::class.java), activity) as View
    // A parent measures and then lays the child out; the stub View only does the second half
    // in layout(), so both are called here (an unbounded parent gives the wrap-content box).
    handle.measure(MeasureSpec.makeMeasureSpec(10_000, MeasureSpec.AT_MOST),
        MeasureSpec.makeMeasureSpec(10_000, MeasureSpec.AT_MOST))
    handle.layout(0, 0, handle.measuredWidth, handle.measuredHeight)
    val visDp = intConstant(handleClass, "VISIBLE_DP")
    val widthDp = intConstant(handleClass, "WIDTH_DP")
    val touchDp = intConstant(handleClass, "TOUCH_DP")
    val touchWidthDp = intConstant(handleClass, "TOUCH_WIDTH_DP")
    val visibleW = Math.round(widthDp * density)
    val visibleH = Math.round(visDp * density)
    val cx = (visibleW / 2).toFloat()
    println("== handle: touch ${handle.measuredWidth}x${handle.measuredHeight}px, " +
        "visible ${visibleW}x${visibleH}px ==")
    check("handle touch box is ~108x44dp",
        handle.measuredWidth == dp(activity, touchWidthDp.toFloat()) &&
            handle.measuredHeight == dp(activity, touchDp.toFloat())) {
        "${handle.measuredWidth}x${handle.measuredHeight}"
    }
    check("handle touch box is wider than it is tall", handle.measuredWidth > handle.measuredHeight * 2)
    check("handle visible body is ~84x30dp and wide", widthDp in 80..88 && visDp in 28..32) {
        "${widthDp}x${visDp}dp"
    }
    check("handle visible body fits in the touch box", touchDp >= visDp + 10)
    check("the touch box is invisible padding around the pill",
        handle.measuredWidth > visibleW && handle.measuredHeight > visibleH) {
        "touch ${handle.measuredWidth}x${handle.measuredHeight} vs visible ${visibleW}x${visibleH}"
    }

    // ---- handle onDraw: the pills sit in one row, no vertical stacking ---------------
    // HandleView.onDraw is called directly with a recording Canvas; the view is given the
    // same pixel box it would have on the device, so every number below is a device number.
    val draw = HandleDrawProbe(activity)
    privateMethod(handleClass, "onDraw", android.graphics.Canvas::class.java)
        .invoke(handle, draw)
    println("   handle draw: ${draw.summary()}")
    // The visible body is a GradientDrawable: its bounds and radius say whether it is the
    // wide, flat, top-anchored U of the design or the old narrow pill.
    val bodyDrawable = GameTopUiAccess.handleBody(handle)
    check("the handle background is a shape drawable (the pill body)", bodyDrawable != null)
    if (bodyDrawable != null) {
        val b = bodyDrawable.bounds
        println("   handle body: l=${b.left} t=${b.top} r=${b.right} b=${b.bottom} " +
            "radius=${bodyDrawable.cornerRadius / density}dp in a view ${visibleW}x${handle.measuredHeight}")
        check("the pill body is the visible width, not the touch box",
            Math.abs(b.width() - visibleW) <= dp(activity, 1f)) {
            "width=${b.width()} visible=$visibleW touch=${handle.measuredWidth}"
        }
        check("the pill body is centred in the wider touch box",
            Math.abs((b.left + b.right) / 2f - handle.measuredWidth / 2f) <= dp(activity, 1f)) {
            "body=${b.left}..${b.right} touch=${handle.measuredWidth}"
        }
        check("the pill body ends at the bottom of the visible band (it hangs from the edge)",
            Math.abs(b.bottom - visibleH) <= dp(activity, 1f)) { "bottom=${b.bottom} visible=$visibleH" }
        check("the pill body starts above the window edge (top-anchored U)", b.top < -visibleH) {
            "top=${b.top}"
        }
        check("the lip is shallow: at most 3.5x the visible band is hidden above the edge",
            b.height() <= visibleH * 3.5f) {
            "height=${b.height()} visible band=$visibleH"
        }
        check("the lip is flat: radius well under half its height",
            bodyDrawable.cornerRadius in 1f..(visibleH * .55f)) {
            "radius=${bodyDrawable.cornerRadius / density}dp of height ${visibleH}px"
        }
    }
    check("the touch box is not painted (no round rect of the touch size)", draw.roundRects.isEmpty()) {
        "${draw.roundRects.size} painted round rect(s)"
    }
    check("exactly one state dot is painted", draw.circles.size == 1) {
        "circles=${draw.circles.size}"
    }
    val dot = draw.circles.firstOrNull()
    check("the chevron is a stroked 3-point path", draw.pathPoints.size == 3) {
        "points=${draw.pathPoints.size}"
    }
    if (dot != null) {
        val (dotX, dotY, radius) = dot
        val centerX = visibleW / 2f
        println("   handle content: dot($dotX,$dotY r=$radius) chevron x=" +
            draw.pathPoints.map { it[0] } + " y=" + draw.pathPoints.map { it[1] })
        check("the dot and the chevron sit in one horizontal band",
            Math.abs(dotY - visibleH / 2f) <= visibleH * .22f) { "dotY=$dotY band=$visibleH" }
        check("the dot sits left of the lip's centre (paired with the chevron)",
            dotX < centerX * .92f) { "dotX=$dotX centre=$centerX" }
        check("the dot keeps a visible gap from the rounded left end",
            dotX - radius > dp(activity, 8f)) { "dotX=$dotX radius=$radius" }
        check("the chevron is horizontally off-centre on the other side",
            draw.pathPoints.isNotEmpty() && draw.pathPoints.map { it[0] }.average() > centerX) {
            draw.pathPoints.map { it[0] }.toString()
        }
        check("the dot is not above the chevron (no vertical stack)",
            Math.abs(draw.pathPoints.map { it[1] }.average() - dotY) <= visibleH * .45f) {
            "dotY=$dotY chevronY=${draw.pathPoints.map { it[1] }}"
        }
        check("the dot keeps a readable size (>= 3.5 visible px radius)", radius >= 3.5f) {
            "radius=$radius"
        }
    }
    check("the chevron points down (apex below both ends)", draw.chevronPointsDown) {
        draw.pathPoints.toString()
    }
    check("nothing is drawn with a dashed stroke", !draw.dashed)

    println("=== $passed passed, $failed failed ===")
    if (failed > 0) {
        println("failures:")
        failures.forEach { println("  - $it") }
    }
}

/**
 * Reads a view's drawable background. The control's UI class is package private and only
 * its nested types are not, so the reflection climbs the declared field instead of casting.
 */
private object GameTopUiAccess {
    fun handleBody(view: View): android.graphics.drawable.GradientDrawable? {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == "com.yefeng.majmax.hookprobe.GameTopUi\$HandleView") {
                val f = type.getDeclaredField("body")
                f.isAccessible = true
                return f.get(view) as? android.graphics.drawable.GradientDrawable
            }
            type = type.superclass
        }
        return null
    }
}

/** Records what HandleView.onDraw paints, in view coordinates. */
private class HandleDrawProbe(context: android.content.Context) : android.graphics.Canvas() {
    val roundRects = ArrayList<List<Float>>()
    val circles = ArrayList<List<Float>>()
    val lines = ArrayList<FloatArray>()
    var pathPoints: List<FloatArray> = emptyList()
    var dashed = false
    var bodyRadius = 0f
    var chevronPointsDown = false

    override fun drawRoundRect(rect: android.graphics.RectF, rx: Float, ry: Float,
                               paint: android.graphics.Paint) {
        roundRects.add(listOf(rect.left, rect.top, rect.right, rect.bottom))
        if (bodyRadius == 0f) bodyRadius = rx
    }

    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: android.graphics.Paint) {
        circles.add(listOf(cx, cy, radius))
    }

    override fun drawLine(sx: Float, sy: Float, ex: Float, ey: Float, paint: android.graphics.Paint) {
        lines.add(floatArrayOf(sx, sy, ex, ey))
    }

    override fun drawPath(path: android.graphics.Path, paint: android.graphics.Paint) {
        pathPoints = path.points().map { floatArrayOf(it[0], it[1]) }
        // moveTo, lineTo, lineTo: the apex is the middle vertex.
        if (pathPoints.size == 3) {
            val first = pathPoints[0][1]
            val apex = pathPoints[1][1]
            val last = pathPoints[2][1]
            chevronPointsDown = apex > first && apex > last
        }
    }

    fun summary(): String =
        "roundRects=${roundRects.size} circles=$circles chevronDown=$chevronPointsDown"
}
