// Layout regression for the in-game panel (GameTopControl.java / GameTopUi.java /
// GameTopDesign.java) after the redesign from the queshen-panel-kit hand-off.
//
// The user's device is a 2376x1080 landscape window at density 2.75 whose short side is the
// design's 1080px stage, so the design scale is 1 and every design pixel is one visible
// pixel. The panel is drawn through ScaledMenuHost at .75, which means the design value has
// to be divided by .75 exactly once (GameTopDesign.px). This probe builds the REAL control
// on a plain JVM (android.* comes from tools/tests/jvm-shims/android-shim) and asserts the
// design geometry, not a ratio:
//
//   * design tokens: px(640) -> 853 pre-scale -> 640 visible (the .75 is divided out once,
//     never twice), sp() matches px(), the motion constants are the kit's,
//   * the panel is 640x530 visible px on the control page (640 wide, padding 16/18, header
//     52, tab band 58 with 12/14 margins, four 84px rows with 8px gaps),
//   * the collapsed bar is 72px (10 + 52 + 10) and has no body at all,
//   * all four control rows fit the panel without scrolling (maxScroll == 0),
//   * the naming row is a value row: 84px, a value pill, a disclosure arrow, its settings
//     block folded away — and it grows by exactly that block when tapped,
//   * the status page is four 64px key/value rows of real polled state plus the two actions,
//   * the switch owns the flip, the row owns the disclosure, the tab shell owns the page,
//   * the handle is untouched: 84x30dp lip in a 108x44dp touch box, dot + chevron in one row.
//
// Text rows are measured by the shim as padding + 1.34*textSize (no font on the JVM), so the
// px numbers below come from the explicit minimum heights of the design, never from font
// metrics: the assertions are the design's own numbers.
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

private fun floatConstant(owner: Class<*>, name: String): Float {
    val f: Field = runCatching { owner.getDeclaredField(name) }.getOrElse { return -1f }
    f.isAccessible = true
    return f.getFloat(null)
}

private fun longConstant(owner: Class<*>, name: String): Long {
    val f: Field = runCatching { owner.getDeclaredField(name) }.getOrElse { return -1L }
    f.isAccessible = true
    return f.getLong(null)
}

private fun staticValue(owner: Class<*>, name: String): Any? {
    val f: Field = runCatching { owner.getDeclaredField(name) }.getOrElse { return null }
    f.isAccessible = true
    return f.get(null)
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

/** Calls setCollapsed(collapsed, animate): animate is always false in a probe (no window). */
private fun fold(control: Any, controlClass: Class<*>, collapsed: Boolean) {
    val method = privateMethod(controlClass, "setCollapsed",
        Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
    method.invoke(control, collapsed, false)
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

private fun visibleChildren(parent: ViewGroup): List<View> =
    (0 until parent.childCount).map { parent.getChildAt(it) }.filter { it.visibility != View.GONE }

private fun childrenOf(parent: View): List<View> =
    if (parent is ViewGroup) (0 until parent.childCount).map { parent.getChildAt(it) } else emptyList()

private fun marginTop(view: View): Int =
    (view.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0

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
    val designClass = Class.forName("com.yefeng.majmax.hookprobe.GameTopDesign")
    val control: Any = newInstance(controlClass, emptyArray())
    val activity = ProbedActivity()
    val density = activity.resources.displayMetrics.density
    val hostScale = floatConstant(designClass, "HOST_SCALE")
    println("== device 2376x1080 @ $density, HOST_SCALE=$hostScale, dp(1)=${dp(activity, 1f)}px ==")

    // ---- A. the design scale: one factor, divided out once ---------------------------
    val designScale = privateMethod(designClass, "scale", android.content.Context::class.java)
        .invoke(null, activity) as Float
    checkEq("the design scale of this device is 1 (its short side is the 1080px stage)", 1f, designScale)
    val pxOf = privateMethod(designClass, "px", android.content.Context::class.java,
        Float::class.javaPrimitiveType!!)
    fun designPx(value: Float): Int = pxOf.invoke(null, activity, value) as Int
    val panelWidth = (staticValue(designClass, "PANEL_W") as Int).toFloat()
    val prePanel = designPx(panelWidth)
    println("   panel: design ${panelWidth.toInt()}px -> pre-scale ${prePanel}px -> " +
        "visible ${Math.round(prePanel * hostScale)}px")
    checkEq("the 640px panel is laid out $prePanel px wide inside the .75 host", 853, prePanel)
    checkEq("...and is back to exactly 640px once the host draws it",
        640, Math.round(prePanel * hostScale))
    // The same round trip for every design value the panel uses: none of them may be scaled
    // twice (640*.75 = 480 would be the double-scale bug, 640/.75/2.75*2.75 the other one).
    for ((name, expected) in listOf(
        "PANEL_W" to 640, "HEADER_H" to 52, "TABS_H" to 58, "ROW_H" to 84,
        "ROW_H_STATUS" to 64, "COLLAPSED_H" to 72, "PANEL_PAD_H" to 16,
        "PANEL_PAD_TOP" to 16, "PANEL_PAD_BOTTOM" to 18, "ROW_GAP" to 8
    )) {
        val design = (staticValue(designClass, name) as Int).toFloat()
        val visible = Math.round(designPx(design) * hostScale)
        check("$name ($expected px) survives the host round trip", Math.abs(visible - expected) <= 1) {
            "design=$design visible=$visible"
        }
    }
    val spOf = privateMethod(designClass, "sp", android.content.Context::class.java,
        Float::class.javaPrimitiveType!!)
    val titleSp = spOf.invoke(null, activity, 30f) as Float
    check("the 30px title is ${(Math.round(titleSp * 100) / 100f)}sp at 2.75 density",
        Math.abs(titleSp - 30f / hostScale / density) < .01f) { "sp=$titleSp" }

    // ---- B. motion: the kit's own numbers --------------------------------------------
    checkEq("expand is 280ms", 280L, longConstant(designClass, "MS_EXPAND"))
    checkEq("collapse is 220ms", 220L, longConstant(designClass, "MS_COLLAPSE"))
    checkEq("content fades in over 200ms", 200L, longConstant(designClass, "MS_CONTENT_IN"))
    checkEq("...after an 80ms delay", 80L, longConstant(designClass, "MS_CONTENT_IN_DELAY"))
    checkEq("content fades out over 120ms", 120L, longConstant(designClass, "MS_CONTENT_OUT"))
    checkEq("rows are staggered by 30ms", 30L, longConstant(designClass, "MS_ROW_STAGGER"))
    checkEq("the switch takes 180ms", 180L, longConstant(designClass, "MS_TOGGLE"))
    checkEq("the tab page fades in over 160ms", 160L, longConstant(designClass, "MS_TAB_CONTENT"))
    checkEq("the capsule keeps its 3s idle window", 3_000L,
        longConstant(controlClass, "IDLE_CAPSULE_MS"))
    checkEq("the menu keeps its 6s idle window", 6_000L,
        longConstant(controlClass, "IDLE_MENU_MS"))
    val ease = privateMethod(designClass, "ease", FloatArray::class.java,
        Float::class.javaPrimitiveType!!)
    val expand = staticValue(designClass, "EASE_EXPAND") as FloatArray
    checkEq("the expand curve starts at 0", 0f, ease.invoke(null, expand, 0f) as Float)
    checkEq("the expand curve ends at 1", 1f, ease.invoke(null, expand, 1f) as Float)
    val standard = staticValue(designClass, "EASE_STANDARD") as FloatArray
    val collapse = staticValue(designClass, "EASE_COLLAPSE") as FloatArray
    val midExpand = ease.invoke(null, expand, .5f) as Float
    val midStandard = ease.invoke(null, standard, .5f) as Float
    val midCollapse = ease.invoke(null, collapse, .5f) as Float
    println("   easing at t=.5: expand=$midExpand standard=$midStandard collapse=$midCollapse")
    // The kit's curves, by shape: (.2,.8,.2,1) is strongly front-loaded, (.2,0,0,1) is
    // gentler, (.3,0,.8,.15) starts slow and settles last. Every one must be monotonic and
    // stay inside 0..1, which is what makes a frame loop safe at any duration.
    check("the expand curve is front-loaded", midExpand > .9f) { "ease(.5)=$midExpand" }
    check("the standard curve is faster than linear but slower than expand",
        midStandard > .5f && midStandard < midExpand) { "ease(.5)=$midStandard" }
    check("the collapse curve starts slow", midCollapse < .3f) { "ease(.5)=$midCollapse" }
    for ((label, curve) in listOf("expand" to expand, "standard" to standard,
        "collapse" to collapse)) {
        var previous = 0f
        var monotonic = true
        var bounded = true
        for (step in 0..20) {
            val value = ease.invoke(null, curve, step / 20f) as Float
            if (value < previous - 1e-6f) monotonic = false
            if (value < 0f || value > 1f) bounded = false
            previous = value
        }
        check("the $label curve is monotonic and stays inside 0..1", monotonic && bounded)
    }
    val canAnimate = privateMethod(designClass, "canAnimate", View::class.java)
    checkEq("a view without a window never animates (the probes see final geometry)",
        false, canAnimate.invoke(null, View(activity)))

    // ---- C. build the real control, exactly as attach() does -------------------------
    val root = privateMethod(controlClass, "build", Activity::class.java).invoke(control, activity) as View
    val menu = privateField(control, "menu") as LinearLayout
    val host = privateField(control, "menuHost") as FrameLayout
    val body = privateField(control, "body") as LinearLayout
    val band = privateField(control, "band") as View
    val pageControl = privateField(control, "pageControl") as LinearLayout
    val pageStatus = privateField(control, "pageStatus") as LinearLayout
    check("build() produced a panel, its host, the body and the band",
        menu != null && host != null && body != null && band != null)
    // The two pages start hidden (build() paints no stage). Open the control page the way
    // paintTab() does after applyState(), then measure.
    pageControl.visibility = View.VISIBLE
    pageStatus.visibility = View.GONE
    fold(control, controlClass, false)
    measureInto(host, 2376, 1080)
    val visibleW = host.measuredWidth
    val visibleH = host.measuredHeight
    val preW = menu.measuredWidth
    val preH = menu.measuredHeight
    println("== panel: visible ${visibleW}x$visibleH px (pre-scale ${preW}x$preH) ==")
    checkEq("the panel is 640 design px wide", 640, visibleW)
    check("the panel is 530 visible px tall on the control page (design: 16+52+12+58+14+360+18)",
        Math.abs(visibleH - 530) <= 1) { "$visibleH px" }
    checkEq("the pre-scale width is the visible width / SCALE", Math.round(visibleW / hostScale), preW)
    checkEq("the pre-scale height is what the design asks for (530 / .75)",
        Math.round(visibleH / hostScale), preH)

    // ---- D. the pieces of the design ------------------------------------------------
    val header = privateField(control, "headerRow") as View
    val tabsRow = childrenOf(body).firstOrNull { it !== band && it.visibility != View.GONE }
    val bodyChildren = visibleChildren(body)
    checkEq("the body is the tab band plus the scrolling pages", 2, bodyChildren.size)
    println("   header=${Math.round(header.measuredHeight * hostScale)}px " +
        "tabs=${Math.round((tabsRow?.measuredHeight ?: 0) * hostScale)}px " +
        "band=${Math.round(band.measuredHeight * hostScale)}px")
    checkEq("the header is the design's 52px band", 52, Math.round(header.measuredHeight * hostScale))
    checkEq("the segmented tab band is 58px", 58, Math.round((tabsRow?.measuredHeight ?: 0) * hostScale))
    checkEq("the tab band sits 12px under the header", 12,
        Math.round(marginTop(tabsRow ?: header) * hostScale))
    checkEq("the panel's horizontal padding is 16px",
        16, Math.round(menu.paddingLeft * hostScale))
    checkEq("the panel's top padding is 16px", 16, Math.round(menu.paddingTop * hostScale))
    checkEq("the panel's bottom padding is 18px", 18, Math.round(menu.paddingBottom * hostScale))

    // ---- E. four compact rows, all of them on screen --------------------------------
    val rows = visibleChildren(pageControl)
    println("   control page rows: " + rows.joinToString {
        "${it.javaClass.simpleName}(${Math.round(it.measuredHeight * hostScale)}px, " +
            "gap ${Math.round(marginTop(it) * hostScale)}px)"
    })
    checkEq("the control page has exactly four rows", 4, rows.size)
    for ((index, row) in rows.withIndex()) {
        checkEq("row $index is the design's 84px row", 84,
            Math.round(row.measuredHeight * hostScale))
        checkEq("row $index keeps the design's 8px gap", if (index == 0) 0 else 8,
            Math.round(marginTop(row) * hostScale))
    }
    val pageContent = rows.sumOf { it.measuredHeight + marginTop(it) }
    check("the four rows fill the design's 360px of content",
        Math.abs(Math.round(pageContent * hostScale) - 360) <= 2) {
        "${Math.round(pageContent * hostScale)}px"
    }
    checkEq("the scroll band is exactly the room the rows need (nothing to scroll)",
        Math.round(pageContent * hostScale), Math.round(band.measuredHeight * hostScale))
    val scroll = walk(menu).firstOrNull { it is android.widget.ScrollView }
    check("the page band is still a ScrollView", scroll != null)
    checkEq("the band is inside the panel and ends at its bottom edge",
        Math.round(preH * hostScale),
        Math.round((header.measuredHeight + menu.paddingTop +
            (tabsRow?.measuredHeight ?: 0) + marginTop(tabsRow ?: header) +
            marginBottom(tabsRow ?: header) + band.measuredHeight +
            menu.paddingBottom) * hostScale))

    // ---- F. the rows are wired, and only the switch flips a feature ------------------
    val switchClass = Class.forName("com.yefeng.majmax.hookprobe.GameTopUi\$Switch")
    for (name in listOf("unattendedCard", "rematchCard", "panelCard")) {
        val card = privateField(control, name) as? View
        check("$name exists", card != null)
        if (card == null) continue
        check("$name carries no click listener (a tap on the copy never toggles)",
            !card.hasOnClickListeners())
        val toggle = card.getTag(2113994756) as? View // TAG_SWITCH
        check("$name has a right-hand switch", toggle != null && switchClass.isInstance(toggle))
        check("$name's switch is the click target", toggle != null && toggle.hasOnClickListeners())
        val state = card.getTag(2113994754) as? TextView // TAG_STATE
        val note = card.getTag(2113994755) as? TextView // TAG_NOTE
        check("$name keeps two text lines: a title and one state+note line",
            state != null && note != null && note.maxLines == 1)
    }

    // ---- G. the naming row is a value row with a folded settings block --------------
    val namingCard = privateField(control, "namingCard") as? LinearLayout
    val namingRow = privateField(control, "namingRow") as? View
    val namingValue = privateField(control, "namingValue") as? TextView
    val namingSettings = privateField(control, "namingSettings") as? View
    check("the opponent-naming row exists", namingCard != null && namingRow != null)
    check("the naming row is the fourth row of the page",
        namingCard != null && rows.last() === namingCard)
    check("the naming row carries the disclosure listener (the arrow and the pill are not buttons)",
        namingRow != null && namingRow.hasOnClickListeners())
    check("the value pill exists", namingValue != null && namingValue.text.isNotEmpty())
    check("the value pill is a chip, not plain copy",
        namingValue != null && namingValue.background != null)
    check("the naming settings block starts folded",
        namingSettings != null && namingSettings.visibility == View.GONE)
    val namingCells = privateField(control, "namingOptions") as? Array<*>
    checkEq("the three mode cells live in the folded block", 3, namingCells?.size)
    check("...and are inside it, so one disclosure reveals all of them",
        namingSettings != null && namingCells?.all { c ->
            c is View && walk(namingSettings).contains(c)
        } == true)
    // The disclosure is driven the way the tap drives it: the row's listener flips
    // `namingOpen` and applyState() -> paintNaming() owns the visibility. The listener itself
    // is asserted above, and the painter is invoked directly here because applyState() needs
    // the display gate (a provider answer) that a JVM probe does not have.
    val openField = controlClass.getDeclaredField("namingOpen")
    openField.isAccessible = true
    val foldedCard = namingCard?.measuredHeight ?: 0
    openField.setBoolean(control, true)
    privateMethod(controlClass, "paintNaming").invoke(control)
    check("opening the settings block shows it", namingSettings?.visibility == View.VISIBLE)
    check("opening the settings keeps the row itself at 84px",
        Math.round((namingRow?.measuredHeight ?: 0) * hostScale) == 84) {
        "${Math.round((namingRow?.measuredHeight ?: 0) * hostScale)}"
    }
    menu.measure(MeasureSpec.makeMeasureSpec(preW, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(20_000, MeasureSpec.AT_MOST))
    val grownCard = namingCard?.measuredHeight ?: 0
    println("   naming card: folded ${Math.round(foldedCard * hostScale)}px, " +
        "open ${Math.round(grownCard * hostScale)}px")
    check("...and grows the card by the settings block", grownCard > foldedCard) {
        "folded=$foldedCard open=$grownCard"
    }
    openField.setBoolean(control, false)
    privateMethod(controlClass, "paintNaming").invoke(control)
    check("closing it folds the block away again", namingSettings?.visibility == View.GONE)

    // ---- H. the collapsed bar is 72px and has no body -------------------------------
    fold(control, controlClass, true)
    measureInto(host, 2376, 1080)
    val collapsedH = host.measuredHeight
    println("== collapsed: ${host.measuredWidth}x$collapsedH px, body ${body.visibility} ==")
    check("the collapsed bar is the design's 72px (10 + 52 + 10), within 1px of rounding",
        Math.abs(collapsedH - 72) <= 1) { "$collapsedH px" }
    checkEq("the collapsed bar is the same width as the panel", 640, host.measuredWidth)
    checkEq("nothing but the header is drawn while collapsed", View.GONE, body.visibility)
    val chevron = privateField(control, "chevron") as? View
    check("the chevron points down while collapsed", chevron != null && chevron.rotation == 180f) {
        "rotation=${chevron?.rotation}"
    }
    val headerRow = privateField(control, "headerRow") as? View
    check("the whole header is the fold toggle", headerRow != null && headerRow.hasOnClickListeners())
    check("the header is clickable", headerRow != null && headerRow.isClickable)
    // Expanding again gives the panel its height back, and the chevron turns up.
    fold(control, controlClass, false)
    measureInto(host, 2376, 1080)
    check("expanding restores the panel height",
        Math.abs(visibleH - host.measuredHeight) <= 1) {
        "was=$visibleH now=${host.measuredHeight}"
    }
    checkEq("the chevron points up again", 0f, chevron?.rotation ?: -1f)

    // ---- I. the status page: real values in 64px rows --------------------------------
    // The page is GONE while the control tab is up, so it has to be measured on its own,
    // with the width the pages host would hand down.
    pageStatus.measure(
        MeasureSpec.makeMeasureSpec(pageControl.measuredWidth, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    val statusRows = visibleChildren(pageStatus)
    println("   status page rows: " + statusRows.joinToString {
        "${it.javaClass.simpleName}(${Math.round(it.measuredHeight * hostScale)}px)"
    })
    val kvRows = statusRows.take(4)
    for ((index, row) in kvRows.withIndex()) {
        checkEq("status row $index is the design's 64px", 64,
            Math.round(row.measuredHeight * hostScale))
    }
    val statusService = privateField(control, "statusService") as? TextView
    val statusMode = privateField(control, "statusMode") as? TextView
    val statusRematch = privateField(control, "statusRematch") as? TextView
    val statusPanelValue = privateField(control, "statusPanel") as? TextView
    check("the four status values exist",
        statusService != null && statusMode != null && statusRematch != null &&
            statusPanelValue != null)
    // Keys of the four rows are the design's key/value pairs, not placeholder data.
    val keyTexts = kvRows.map { row ->
        (row as ViewGroup).getChildAt(0).let { if (it is TextView) it.text.toString() else "" }
    }
    println("   status keys: $keyTexts")
    checkEq("the status page reports the service first", "服务状态", keyTexts.firstOrNull())
    checkEq("...then the unattended mode", "无人值守", keyTexts.getOrNull(1))
    checkEq("...then auto-rematch", "自动下一局", keyTexts.getOrNull(2))
    checkEq("...then the recommendation panel", "推荐与番点", keyTexts.getOrNull(3))
    // The primary action of the page keeps a full row of touch.
    val startStop = privateField(control, "startStop") as? View
    check("the status page keeps its start/stop action at 84px",
        startStop != null && Math.round(startStop.measuredHeight * hostScale) == 84) {
        "${startStop?.measuredHeight}"
    }

    // ---- J. the handle: untouched ---------------------------------------------------
    val handleClass = Class.forName("com.yefeng.majmax.hookprobe.GameTopUi\$HandleView")
    val handle = newInstance(handleClass, arrayOf<Class<*>>(android.content.Context::class.java), activity) as View
    handle.measure(MeasureSpec.makeMeasureSpec(10_000, MeasureSpec.AT_MOST),
        MeasureSpec.makeMeasureSpec(10_000, MeasureSpec.AT_MOST))
    handle.layout(0, 0, handle.measuredWidth, handle.measuredHeight)
    val visDp = (staticValue(handleClass, "VISIBLE_DP") as Int)
    val widthDp = (staticValue(handleClass, "WIDTH_DP") as Int)
    val touchDp = (staticValue(handleClass, "TOUCH_DP") as Int)
    val touchWidthDp = (staticValue(handleClass, "TOUCH_WIDTH_DP") as Int)
    val visibleHW = Math.round(widthDp * density)
    val visibleHH = Math.round(visDp * density)
    println("== handle: touch ${handle.measuredWidth}x${handle.measuredHeight}px, " +
        "visible ${visibleHW}x$visibleHH px ==")
    check("handle touch box is still 108x44dp",
        handle.measuredWidth == dp(activity, touchWidthDp.toFloat()) &&
            handle.measuredHeight == dp(activity, touchDp.toFloat())) {
        "${handle.measuredWidth}x${handle.measuredHeight}"
    }
    check("handle visible body is still 84x30dp", widthDp == 84 && visDp == 30) {
        "${widthDp}x${visDp}dp"
    }
    check("the touch box is invisible padding around the lip",
        handle.measuredWidth > visibleHW && handle.measuredHeight > visibleHH)
    val draw = HandleDrawProbe(activity)
    privateMethod(handleClass, "onDraw", android.graphics.Canvas::class.java).invoke(handle, draw)
    val bodyDrawable = GameTopUiAccess.handleBody(handle)
    check("the handle background is the same shape drawable", bodyDrawable != null)
    if (bodyDrawable != null) {
        val b = bodyDrawable.bounds
        check("the lip body is still the visible width, centred in the touch box",
            Math.abs(b.width() - visibleHW) <= dp(activity, 1f) &&
                Math.abs((b.left + b.right) / 2f - handle.measuredWidth / 2f) <= dp(activity, 1f)) {
            "body=${b.left}..${b.right} visible=$visibleHW touch=${handle.measuredWidth}"
        }
        check("the lip body still hangs from the edge and stays shallow",
            Math.abs(b.bottom - visibleHH) <= dp(activity, 1f) && b.top < -visibleHH &&
                b.height() <= visibleHH * 3.5f) {
            "top=${b.top} bottom=${b.bottom}"
        }
        check("the lip radius is still flat", bodyDrawable.cornerRadius in 1f..(visibleHH * .55f)) {
            "radius=${bodyDrawable.cornerRadius}"
        }
    }
    check("the touch box is not painted", draw.roundRects.isEmpty()) {
        "${draw.roundRects.size} painted round rect(s)"
    }
    check("exactly one state dot is painted", draw.circles.size == 1)
    check("the chevron is a stroked 3-point path", draw.pathPoints.size == 3)
    val dot = draw.circles.firstOrNull()
    if (dot != null) {
        val (dotX, dotY, radius) = dot
        check("the dot and the chevron still share one horizontal band",
            Math.abs(dotY - visibleHH / 2f) <= visibleHH * .22f) { "dotY=$dotY" }
        check("the dot is still left of the lip centre", dotX < visibleHW / 2f * .92f)
        check("the chevron is still right of it",
            draw.pathPoints.map { it[0] }.average() > visibleHW / 2f)
        check("the dot keeps a readable size", radius >= 3.5f) { "radius=$radius" }
    }
    check("the chevron still points down", draw.chevronPointsDown)
    check("nothing is still drawn dashed", !draw.dashed)

    // ---- K. the cap ------------------------------------------------------------------
    // A shorter box than the design still clamps, and the panel keeps its own height when
    // the window is much taller: the panel is content-sized, not window-sized.
    val freshControl: Any = newInstance(controlClass, emptyArray())
    val freshMenu = privateMethod(controlClass, "buildMenu", Activity::class.java)
        .invoke(freshControl, activity) as LinearLayout
    freshMenu.measure(MeasureSpec.makeMeasureSpec(853, MeasureSpec.AT_MOST),
        MeasureSpec.makeMeasureSpec(300, MeasureSpec.AT_MOST))
    check("a 300px box caps the panel at 300px", freshMenu.measuredHeight == 300) {
        "${freshMenu.measuredHeight}"
    }
    freshMenu.measure(MeasureSpec.makeMeasureSpec(853, MeasureSpec.AT_MOST),
        MeasureSpec.makeMeasureSpec(60_000, MeasureSpec.AT_MOST))
    check("a very tall window leaves the panel at its content height (never the window's)",
        freshMenu.measuredHeight < 900) { "${freshMenu.measuredHeight}px pre-scale" }

    println("=== $passed passed, $failed failed ===")
    if (failed > 0) {
        println("failures:")
        failures.forEach { println("  - $it") }
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}

private fun marginBottom(view: View): Int =
    (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

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
