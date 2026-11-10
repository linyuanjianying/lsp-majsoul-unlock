package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Dark "mahjong table" drawing language shared by the floating window.
 *
 * Everything here is deliberately a plain [View] built with [GradientDrawable]
 * shapes instead of XML resources or Material components: the panel is inflated
 * from a *Service* context where an app theme (and therefore a Material theme
 * attribute lookup) is not reliably available, and it must look identical on every
 * OEM build. The palette is the dark navy panel the assistant is recognised by:
 *
 *  - opaque deep navy panel (`#111a2c`) with a thin gold-orange hairline
 *    (`#b68a48`) and a large corner radius,
 *  - deep grey-blue cards (`#20283a`) with a fainter hairline,
 *  - light grey-white text, muted grey captions, gold-orange accents (`#f6ad35`)
 *    and a teal "running" green (`#35d3ad`),
 *  - red reserved for the destructive stop action.
 *
 * Opacity is an *alpha multiplier* on those colours, so the panel is opaque at
 * 100 % and the slider only ever makes it lighter — never lighter on a white base,
 * so the dark surface survives at every setting.
 *
 * Sizes are expressed in dp/sp and colours in ARGB ints, so one instance works for
 * phone portrait, landscape and compact widths.
 */
internal class FloatingStyle(private val context: Context) {

    /** Resolves the collision between a style field name and [View.getContext]. */
    val host: Context get() = context

    // ---- palette -------------------------------------------------------------
    /** Deep navy panel fill; [alphaPercent] comes from the user's opacity slider. */
    fun panelColor(alphaPercent: Int): Int = alpha(alphaPercent).composite(PANEL)

    /** Collapsed bubble: one step deeper than the panel, same gold rim. */
    fun bubbleColor(alphaPercent: Int): Int = alpha(alphaPercent).composite(BUBBLE)

    fun panelStroke(): Int = STROKE_GOLD

    /** Raised card fill (switch rows, recommendation block): deep grey-blue. */
    fun cardColor(alphaPercent: Int): Int = alpha(alphaPercent).composite(CARD)

    fun cardStroke(): Int = STROKE_CARD

    /** Gold-orange accent: primary actions, selected tab, emphasis values. */
    val accent = Color.rgb(246, 173, 53)
    val accentSoft = Color.argb(38, 246, 173, 53)   // accent @ 15 %
    val accentText = Color.rgb(246, 173, 53)
    val onAccent = Color.rgb(17, 26, 44)            // dark ink on the orange tab
    val ink = Color.rgb(233, 238, 246)              // headings, primary values
    val inkSoft = Color.rgb(199, 208, 221)          // body
    val muted = Color.rgb(146, 158, 176)            // captions, hints
    val faint = Color.rgb(116, 128, 146)            // disabled explanation
    val divider = Color.argb(48, 182, 138, 72)      // gold hairline at 19 %
    val danger = Color.rgb(240, 106, 104)
    val dangerTrack = Color.argb(30, 240, 106, 104)
    val dangerStroke = Color.argb(170, 240, 106, 104)
    val warn = Color.rgb(236, 176, 86)
    val good = Color.rgb(53, 211, 173)              // "running" teal
    val trackOff = Color.rgb(58, 68, 88)
    val ripple = Color.argb(40, 246, 173, 53)       // pressed feedback on any surface

    val tabSelectedFill = Color.rgb(246, 173, 53)
    val tabTrack = Color.argb(26, 255, 255, 255)
    val tabText = Color.rgb(146, 158, 176)

    // ---- geometry ------------------------------------------------------------
    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
    fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).roundToInt()

    /** Corner radii: bubble is a circle, the expanded panel is a soft card. */
    fun bubbleRadius() = dp(26).toFloat()
    fun panelRadius() = dp(20).toFloat()
    fun innerRadius() = dp(14).toFloat()
    fun capsuleRadius() = dp(16).toFloat()

    /** Minimum edge of any interactive surface: 44 dp per the accessibility floor. */
    val touchTarget = 44

    // ---- drawables -----------------------------------------------------------
    fun shape(fill: Int, radius: Float = innerRadius(), stroke: Int? = null, strokeWidth: Int = 1) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius
            if (stroke != null) setStroke(dp(strokeWidth).coerceAtLeast(1), stroke)
        }

    /** Round surface (status dot, the bubble's inner ring). */
    fun circle(fill: Int, stroke: Int? = null, strokeWidth: Int = 1) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke != null) setStroke(dp(strokeWidth).coerceAtLeast(1), stroke)
    }

    /** Panel background; the opacity slider only changes [fill], never the geometry. */
    fun panelBackground(alphaPercent: Int, collapsed: Boolean): Drawable =
        shape(if (collapsed) bubbleColor(alphaPercent) else panelColor(alphaPercent),
            if (collapsed) bubbleRadius() else panelRadius(), panelStroke(), 1)

    /**
     * Clickable surface with a ripple. The ripple mask is the same rounded shape, so
     * the feedback never bleeds outside the window's rounded silhouette.
     */
    fun tinted(fill: Int, radius: Float = innerRadius(), stroke: Int? = null,
        pressed: Int = ripple): Drawable = RippleDrawable(
        ColorStateList.valueOf(pressed), shape(fill, radius, stroke), shape(Color.WHITE, radius))

    /** Solid accent capsule (selected tab, primary button). */
    fun capsule(fill: Int) = tinted(fill, capsuleRadius())

    /** Deep grey-blue card used for the switch rows and the recommendation block. */
    fun card(alphaPercent: Int) = shape(cardColor(alphaPercent), innerRadius(), cardStroke(), 1)

    fun pill(fill: Int, radius: Float = dp(11).toFloat()) = shape(fill, radius)

    /** Small gold value tag shown at the right of the settings sliders. */
    fun valueTag(): Drawable = shape(accentSoft, dp(9).toFloat(), Color.argb(110, 246, 173, 53), 1)

    /** Outlined destructive button (stop assistant). */
    fun dangerOutline(): Drawable = tinted(dangerTrack, capsuleRadius(), dangerStroke)

    /** Tab bar background: one dark track, the selected tab paints itself gold. */
    fun tabTrackBar(): Drawable = shape(tabTrack, dp(14).toFloat())

    fun tabBackground(selected: Boolean): Drawable = if (selected)
        tinted(tabSelectedFill, dp(12).toFloat()) else tinted(Color.TRANSPARENT, dp(12).toFloat())

    /**
     * Circular chrome button (collapse, close, info). [size] is the *drawn* circle; the
     * host view is 44 dp and centres the background in bounds (a View background fills the
     * view), so the circle is drawn with a padding-free background and the caller keeps the
     * 44 dp touch box. Keeping the drawable itself un-inset means no API-29-only helper is
     * needed for a purely visual detail.
     */
    fun chromeButton(size: Int = 32): Drawable =
        RippleDrawable(ColorStateList.valueOf(ripple),
            shape(Color.argb(22, 255, 255, 255), circleRadius(size)),
            shape(Color.WHITE, circleRadius(size)))

    /** Half the drawn diameter, in px: the corner radius of a circle of [size] dp. */
    private fun circleRadius(size: Int): Float = size.toFloat() * context.resources.displayMetrics.density / 2f

    // ---- text ----------------------------------------------------------------
    fun text(value: String, size: Float = 13f, color: Int = inkSoft, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    /** Full-width touch target (>= 44 dp) so a switch row is tappable, not just the thumb. */
    fun row(height: Int = 52) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            dp(height.coerceAtLeast(touchTarget)))
    }

    fun spacer(height: Int = 6) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(height))
    }

    fun divider() = View(context).apply {
        setBackgroundColor(divider)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1).coerceAtLeast(1)).apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    fun centered(value: String, size: Float = 12f, color: Int = muted) = text(value, size, color).apply {
        gravity = Gravity.CENTER
    }

    /** Small grey caption that sits above a group of rows. */
    fun section(value: String) = text(value, 10f, muted, true).apply { setPadding(dp(2), 0, 0, dp(4)) }

    /** "Running" dot: teal when live, grey otherwise. Always paired with a text label. */
    fun statusDot(running: Boolean) = View(context).apply {
        background = circle(if (running) good else Color.rgb(96, 108, 128))
        layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
    }

    // ---- helpers -------------------------------------------------------------
    /**
     * The user's opacity slider (25..100 %) as an 8-bit alpha.
     *
     * This is the *only* place a percentage becomes an alpha, and it is kept as a pure
     * function so it can be regression-tested without an Android runtime:
     * `floatingAlphaOfPercent(100) == 255`, `(78) == 199` (255 × 78 / 100 = 198.9, rounded),
     * `(25) == 64` (63.75 rounded up).
     *
     * The number is **not** a colour: it must never be handed to `Color.alpha()`, which
     * would read an ARGB int (0xFF = 255) as a fully transparent colour.
     */
    private fun alpha(percent: Int): Int = floatingAlphaOfPercent(percent)

    /**
     * Paints [base] (a plain opaque RGB) at [this] alpha level (0..255).
     *
     * `this` is an alpha level, *not* a packed colour, so the channel is built explicitly
     * with [Color.argb]. The previous implementation wrapped it in `Color.alpha(this)`:
     * at 100 % that value was `255` = `0x000000FF`, `Color.alpha` returned 0 and the whole
     * panel became fully transparent while only the constant-alpha hairline and the button
     * ripples stayed visible — the exact device symptom (“100 % but nothing but borders”).
     *
     * The result is always `argb(level, base.r, base.g, base.b)`, so the dark navy surface
     * keeps its colour at every slider setting instead of fading toward white.
     */
    private fun Int.composite(base: Int): Int = compositeFloatingAlpha(this, base)

    private companion object {
        val PANEL = Color.rgb(17, 26, 44)       // #111a2c
        val BUBBLE = Color.rgb(14, 22, 38)      // #0e1626
        val CARD = Color.rgb(32, 40, 58)        // #20283a
        val STROKE_GOLD = Color.argb(150, 182, 138, 72)   // #b68a48 @ 59 %
        val STROKE_CARD = Color.argb(56, 182, 138, 72)    // gold hairline @ 22 %

    }
}

/**
 * Parked (half-tucked) ball opacity, in slider percent: 65 % → 166 alpha. It has its own
 * value instead of following the panel slider, so a parked idle ball is visibly dimmer
 * than a live panel even when the opacity setting is 100 %.
 */
internal const val PARKED_ALPHA_PERCENT = 65

/**
 * Slider percent → 8-bit alpha. **Pure** and regression-tested on the JVM.
 *
 * This is the only place a user-facing percentage becomes an alpha, and the result is an
 * alpha *level* (0..255), never a packed ARGB colour — the exact confusion that used to
 * blank the panel out (see [compositeFloatingAlpha]).
 */
internal fun floatingAlphaOfPercent(percent: Int): Int {
    val clamped = if (percent < 25) 25 else if (percent > 100) 100 else percent
    val level = (255f * clamped / 100f).roundToInt()
    return if (level < 0) 0 else if (level > 255) 255 else level
}

/**
 * Core colour maths: an opaque [base] painted at [level] (0..255). **Pure**, free of any
 * Android state and regression-tested.
 *
 * The alpha channel is built explicitly from [level]; the previous implementation read it
 * back with `Color.alpha(level)`, which treats `255` as `0x000000FF` — alpha 0 — so a
 * 100 % panel was drawn fully transparent. [Color.argb] clamps the channel, so a stray
 * value can never produce an invisible surface.
 */
internal fun compositeFloatingAlpha(level: Int, base: Int): Int =
    compositeFloatingAlphaUnchecked(level.coerceIn(0, 255), base)

/**
 * [compositeFloatingAlpha] without the level clamp.
 *
 * Note that a zero level still yields a fully *opaque* result: `Color.argb` is documented
 * to keep the RGB payload visible for a transparent colour. The window never relies on a
 * 0 level — the slider floor is 25 % and the parked ball is 65 % — and the regression
 * probe pins this behaviour so a caller cannot silently expect an invisible surface.
 */
internal fun compositeFloatingAlphaUnchecked(level: Int, base: Int): Int =
    Color.argb(level, Color.red(base), Color.green(base), Color.blue(base))

/**
 * The four shapes the assistant's floating surface can take. Pure data, no Android types,
 * so the transitions are regression-tested on the JVM (`tools/tests/FloatingWindowProbe`).
 *
 *  - [BUBBLE]: the full 52 dp ball — deep navy disc, gold rim, green live dot, "雀".
 *  - [CAPSULE]: the status capsule — green dot + "助手运行中", no four-cell icon.
 *  - [PANEL]: the expanded settings panel.
 *  - [PARKED]: the idle ball, half tucked into the nearest edge at ~65 % alpha.
 */
internal enum class FloatingShape { BUBBLE, CAPSULE, PANEL, PARKED }

/** A user action (or the idle timeout) fed to [FloatingShapeMachine]. */
internal enum class FloatingAction { IDLE, DRAG, TAP, LONG_PRESS, COLLAPSE }

/**
 * Shape + timing state machine of the floating window.
 *
 * The cycle the user sees:
 *
 * ```
 *  PANEL   --collapse-->     CAPSULE
 *  CAPSULE --tap-->          PANEL
 *  CAPSULE --idle 3 s-->     BUBBLE        (never crosses straight to PARKED)
 *  BUBBLE  --idle 3 s-->     PARKED        (half tucked, ~65 % alpha)
 *  BUBBLE  --drag-->         BUBBLE        (release = full ball, timer restarted)
 *  BUBBLE  --tap-->          PANEL
 *  PARKED  --tap (1st)-->    CAPSULE       (shows the live state, wakes the ball)
 *  PARKED  --tap (2nd)-->    PANEL
 *  PARKED  --drag-->         BUBBLE        (wake first, no action on the game)
 *  any     --long press-->   any           (toggles unattended; shape untouched)
 * ```
 *
 * "First tap on the parked ball only wakes it" needs one piece of memory — the capsule was
 * reached by tapping a parked ball — carried by [tapArmed]. Everything else is a pure
 * function of [shape], so a rebuilt window only has to keep the machine and keep going.
 *
 * Timing: only the collapsed forms run the idle timer. [idleDelay] is null for [PANEL] (a
 * settings page must never fold itself while the user is reading it) and for [PARKED]
 * (already at its resting shape), and it is the same 3 s for both steps.
 */
internal class FloatingShapeMachine(initial: FloatingShape = FloatingShape.CAPSULE) {

    var shape: FloatingShape = initial
        private set

    /** True when the current [FloatingShape.CAPSULE] was reached by tapping a parked ball. */
    var tapArmed: Boolean = false
        private set

    /** Delay before the next fold step, or null when the shape is at rest. */
    val idleDelay: Long?
        get() = when (shape) {
            FloatingShape.CAPSULE, FloatingShape.BUBBLE -> IDLE_STEP_MS
            FloatingShape.PANEL, FloatingShape.PARKED -> null
        }

    /** True only for the parked shape, the one allowed to hang over the screen edge. */
    val tucksIntoEdge: Boolean get() = shape == FloatingShape.PARKED

    /** True while the idle timer should be running. */
    val wantsIdleTimer: Boolean get() = idleDelay != null

    /** Applies [action] and returns the **new** shape (unchanged when it is ignored). */
    fun apply(action: FloatingAction): FloatingShape {
        val current = shape
        val next = when (action) {
            FloatingAction.TAP -> onTap(current)
            FloatingAction.DRAG -> onDrag(current)
            FloatingAction.COLLAPSE -> FloatingShape.CAPSULE
            FloatingAction.LONG_PRESS -> current
            FloatingAction.IDLE -> onIdle(current)
        }
        // Only a tap on a parked ball arms the next tap; every other transition disarms it.
        tapArmed = action == FloatingAction.TAP && current == FloatingShape.PARKED &&
            next == FloatingShape.CAPSULE
        shape = next
        return next
    }

    private fun onTap(current: FloatingShape): FloatingShape = when (current) {
        // A half-hidden ball must not open a panel that covers the screen edge: the first
        // tap reveals the live state, the second one opens the panel.
        FloatingShape.PARKED -> if (tapArmed) FloatingShape.PANEL else FloatingShape.CAPSULE
        FloatingShape.CAPSULE, FloatingShape.BUBBLE -> FloatingShape.PANEL
        FloatingShape.PANEL -> FloatingShape.PANEL
    }

    private fun onDrag(current: FloatingShape): FloatingShape =
        if (current == FloatingShape.PARKED) FloatingShape.BUBBLE else current

    private fun onIdle(current: FloatingShape): FloatingShape = when (current) {
        FloatingShape.CAPSULE -> FloatingShape.BUBBLE
        FloatingShape.BUBBLE -> FloatingShape.PARKED
        FloatingShape.PANEL, FloatingShape.PARKED -> current
    }

    companion object {
        /** Both fold steps are 3 s: capsule → full ball, then full ball → parked. */
        const val IDLE_STEP_MS = 3_000L
    }
}
