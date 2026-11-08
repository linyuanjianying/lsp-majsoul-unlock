package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Flat, light "system panel" drawing language shared by the floating window.
 *
 * Everything here is deliberately a plain [View] built with [GradientDrawable]
 * shapes instead of XML resources or Material components: the panel is inflated
 * from a *Service* context where an app theme (and therefore a Material theme
 * attribute lookup) is not reliably available, and it must look identical on every
 * OEM build. The palette mirrors the in-game drawing so a user who switches between
 * Hook and overlay mode sees the same assistant:
 *
 *  - translucent light grey-white rounded panel (no pure white, no heavy shadow),
 *  - a blue capsule for navigation / the primary action,
 *  - dark slate text with muted grey captions,
 *  - pale blue separators and warm notes,
 *  - red reserved only for the destructive stop action.
 *
 * Sizes are expressed in dp/sp and colours in ARGB ints, so one instance works for
 * phone portrait, landscape and compact widths.
 */
internal class FloatingStyle(private val context: Context) {

    /** Resolves the collision between a style field name and [View.getContext]. */
    val host: Context get() = context

    // ---- palette -------------------------------------------------------------
    /** Panel fill: light, slightly cool grey-white; [alphaPercent] comes from the
     *  user's opacity slider so the window stays see-through over the game. */
    fun panelColor(alphaPercent: Int): Int =
        Color.argb((255 * alphaPercent.coerceIn(25, 100) / 100f).roundToInt(), 246, 247, 249)

    fun bubbleColor(alphaPercent: Int): Int =
        Color.argb((255 * alphaPercent.coerceIn(65, 100) / 100f).roundToInt(), 24, 33, 44)

    fun panelStroke(): Int = Color.argb(150, 255, 255, 255)

    /** Raised card fill (settings rows, detail blocks): a touch whiter than the panel. */
    fun cardColor(alphaPercent: Int): Int =
        Color.argb((255 * alphaPercent.coerceIn(25, 100) / 100f).roundToInt(), 255, 255, 255)

    fun cardStroke(): Int = Color.argb(120, 214, 222, 232)

    val accent = Color.rgb(47, 111, 237)          // blue capsule / primary
    val accentSoft = Color.rgb(233, 240, 254)     // selected capsule track
    val accentText = Color.rgb(37, 86, 196)       // blue text on light track
    val onAccent = Color.WHITE
    val ink = Color.rgb(28, 36, 46)               // headings, primary values
    val inkSoft = Color.rgb(58, 68, 80)           // body
    val muted = Color.rgb(124, 135, 148)          // captions, hints
    val faint = Color.rgb(158, 168, 178)          // disabled explanation
    val divider = Color.argb(140, 197, 218, 243)  // pale blue separator
    val danger = Color.rgb(214, 64, 66)
    val dangerTrack = Color.rgb(253, 235, 235)
    val warn = Color.rgb(196, 118, 26)
    val good = Color.rgb(24, 140, 96)
    val trackOff = Color.rgb(210, 218, 228)

    val tabSelectedFill = Color.rgb(47, 111, 237)
    val tabTrack = Color.argb(70, 200, 210, 224)
    val tabText = Color.rgb(96, 108, 122)

    // ---- geometry ------------------------------------------------------------
    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
    fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).roundToInt()

    /** Corner radii: bubble is a circle, the expanded panel is a soft card. */
    fun bubbleRadius() = dp(26).toFloat()
    fun panelRadius() = dp(20).toFloat()
    fun innerRadius() = dp(14).toFloat()
    fun capsuleRadius() = dp(16).toFloat()

    // ---- drawables -----------------------------------------------------------
    fun shape(fill: Int, radius: Float = innerRadius(), stroke: Int? = null, strokeWidth: Int = 1) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius
            if (stroke != null) setStroke(dp(strokeWidth).coerceAtLeast(1), stroke)
        }

    /** Panel background; the opacity slider only changes [fill], never the geometry. */
    fun panelBackground(alphaPercent: Int, collapsed: Boolean): Drawable =
        shape(if (collapsed) bubbleColor(alphaPercent) else panelColor(alphaPercent),
            if (collapsed) bubbleRadius() else panelRadius(),
            if (collapsed) 0xFF485565.toInt() else panelStroke(), 1)

    /**
     * Clickable surface with a ripple. The ripple mask is the same rounded shape, so
     * the feedback never bleeds outside the window's rounded silhouette.
     */
    fun tinted(fill: Int, radius: Float = innerRadius(), stroke: Int? = null,
        pressed: Int = Color.argb(38, 30, 40, 60)): Drawable = RippleDrawable(
        ColorStateList.valueOf(pressed), shape(fill, radius, stroke), shape(Color.WHITE, radius))

    /** Solid accent capsule (navigation selection, primary button). */
    fun capsule(fill: Int) = tinted(fill, capsuleRadius())

    /** Light card used for the switch rows and the recommendation block. */
    fun card(alphaPercent: Int) = shape(cardColor(alphaPercent), innerRadius(), cardStroke(), 1)

    fun pill(fill: Int, radius: Float = dp(11).toFloat()) = shape(fill, radius)

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
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(height))
    }

    fun spacer(height: Int = 6) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(height))
    }

    fun divider(): View = View(context).apply {
        setBackgroundColor(divider)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1).coerceAtLeast(1)).apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    fun centered(value: String, size: Float = 12f, color: Int = muted) = text(value, size, color).apply {
        gravity = Gravity.CENTER
    }
}
