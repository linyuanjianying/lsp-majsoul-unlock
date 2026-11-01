package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Read-only badges drawn just above the player's corresponding hand tiles.
 *
 * The window never takes input: FLAG_NOT_TOUCHABLE lets every touch fall
 * through to the game, so a suggestion can never steal a tap from the tiles
 * underneath it. Only the player's own discards and riichi declarations are
 * shown here.
 */
internal class TileBadgeOverlay(private val context: Context) {
    private data class Suggestion(
        val kind: String,
        val tile: String,
        val weight: Double,
        val handIndex: Int
    )

    private val windows = context.getSystemService(WindowManager::class.java)
    private val display = context.resources.displayMetrics
    private val green = Color.rgb(114, 236, 176)
    private val accent = Color.rgb(120, 180, 255)
    private val muted = Color.rgb(200, 213, 206)

    private var root: FrameLayout? = null
    /** Kept because rotation redraws without repeating the whole hand. */
    private var hand: List<String> = emptyList()
    private var result: JSONObject? = null

    private val layout = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, dp(34),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = top(display.heightPixels)
    }

    fun show() = render(emptyList())

    fun update(value: JSONObject) {
        result = value
        hand(value).takeIf { it.isNotEmpty() }?.let { hand = it }
        val status = value.optString("status")
        val rows = value.optJSONArray("recommendations")
        render(if ((status == "live" || status == "demo") && rows != null) suggestions(rows) else emptyList())
    }

    fun close() {
        result = null
        detach()
    }

    fun configurationChanged() {
        detach()
        layout.y = top(display.heightPixels)
        result?.let { update(it) }
    }

    private fun suggestions(rows: JSONArray): List<Suggestion> {
        val found = ArrayList<Suggestion>(3)
        for (i in 0 until rows.length()) {
            if (found.size == 3) break
            val row = rows.optJSONObject(i) ?: continue
            val kind = row.optString("kind")
            val tile = row.optString("tile")
            if (kind !in DISCARD_KINDS || tile.isBlank() || tile == "null") continue
            val label = tile.replace(Regex("^0([mps])$"), "5$1")
            
            // Find which hand tile this suggestion corresponds to.
            // Match exact tile name or red dora counterpart.
            val targetIndex = if (hand.isNotEmpty()) {
                val match = hand.indexOfLast { it == label || it.removeSuffix("r") == label.removeSuffix("r") }
                if (match < 0) continue // Skip actions for tiles not in hand
                match
            } else -1

            val raw = row.optDouble("weight", Double.NaN).takeIf { it.isFinite() }
                ?: (row.optDouble("policyProbability", 0.0) * 100).takeIf { it.isFinite() } ?: 0.0
            found.add(Suggestion(kind, label, raw.coerceIn(0.0, 100.0), targetIndex))
        }
        return found
    }

    private fun hand(value: JSONObject): List<String> {
        value.optJSONArray("hand")?.let(::tiles)?.takeIf { it.isNotEmpty() }?.let { return it }
        val analysis = value.optJSONObject("analysis") ?: return emptyList()
        tiles(analysis.optJSONArray("hand14")).takeIf { it.isNotEmpty() }?.let { return it }
        return tiles(analysis.optJSONArray("hand13"))
    }

    private fun tiles(values: JSONArray?): List<String> {
        if (values == null) return emptyList()
        return (0 until values.length()).mapNotNull { index ->
            values.optString(index).takeIf { it.isNotBlank() && it != "null" && it != "?" }
        }
    }

    private fun render(values: List<Suggestion>) {
        if (values.isEmpty()) {
            detach()
            return
        }
        val manager = windows ?: return
        val frame = root ?: FrameLayout(context).apply {
            isClickable = false
            isFocusable = false
        }.also { root = it }

        val cells = handCells(hand.size)
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
            for ((index, item) in values.withIndex()) {
                addView(badge(item, index), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)))
            }
        }
        frame.removeAllViews()
        frame.addView(strip, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        layout.y = top(display.heightPixels)
        if (root?.parent == null) runCatching { manager.addView(frame, layout) } else runCatching {
            manager.updateViewLayout(frame, layout)
        }
        frame.post { align(frame, strip, values, cells) }
    }

    /**
     * Compute horizontal screen center (X) for each hand tile.
     * In Majsoul landscape view, hand tiles are aligned along the bottom,
     * typically taking ~55-65% of screen width, slightly offset to the right.
     */
    private fun handCells(count: Int): List<Int> {
        val screenW = display.widthPixels
        val slots = if (count in 1..14) count else 14
        // Hand tile width typically occupies ~4.8% of screen width in landscape.
        val tileWidth = (screenW * 0.048f).roundToInt().coerceAtLeast(dp(28))
        val gap = (tileWidth * 0.35f).roundToInt() // Drawn tile extra gap
        val totalHandWidth = (slots - 1) * tileWidth + if (slots == 14) gap else 0

        // Centered slightly to the right (about 52% center point in standard Majsoul layout).
        val startX = (screenW * 0.52f - totalHandWidth / 2f).roundToInt()

        return (0 until slots).map { i ->
            val offset = i * tileWidth + if (i == slots - 1 && slots == 14) gap else 0
            startX + offset + tileWidth / 2
        }
    }

    /**
     * Align each badge directly over its specific target tile.
     */
    private fun align(frame: FrameLayout, strip: LinearLayout, values: List<Suggestion>, cells: List<Int>) {
        val width = frame.width
        if (width <= 0) return
        val fallbackCenter = width / 2
        var minLeft = dp(4)

        for (i in 0 until strip.childCount) {
            val chip = strip.getChildAt(i)
            val suggestion = values.getOrNull(i)
            val targetX = if (suggestion != null && suggestion.handIndex >= 0) {
                cells.getOrNull(suggestion.handIndex) ?: fallbackCenter
            } else {
                cells.getOrNull(i) ?: fallbackCenter
            }

            val desiredLeft = (targetX - chip.width / 2)
                .coerceIn(minLeft, (width - chip.width - dp(4)).coerceAtLeast(minLeft))

            val params = chip.layoutParams as LinearLayout.LayoutParams
            if (params.leftMargin != desiredLeft) {
                params.leftMargin = desiredLeft
                chip.layoutParams = params
            }
            minLeft = desiredLeft + chip.width + dp(4)
        }
    }

    private fun badge(item: Suggestion, index: Int) = TextView(context).apply {
        text = if (item.weight > 0.0) {
            "${verb(item.kind)}${tileName(item.tile)} ${percentage(item.weight)}"
        } else {
            "${verb(item.kind)}${tileName(item.tile)}"
        }
        textSize = 12f
        setTextColor(when (index) { 0 -> green; 1 -> accent; else -> muted })
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        includeFontPadding = false
        maxLines = 1
        gravity = Gravity.CENTER
        setPadding(dp(7), dp(2), dp(7), dp(2))
        elevation = dp(3).toFloat()
        isClickable = false
        isFocusable = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(if (index == 0) Color.argb(230, 10, 36, 26) else Color.argb(220, 16, 26, 40))
            setStroke(dp(1), if (index == 0) green else Color.argb(180, 120, 180, 255))
        }
    }

    private fun verb(kind: String) = if (kind == "riichi") "立直·切" else "切"
    private fun percentage(value: Double) = String.format(Locale.ROOT, "%.1f%%", value)
    private fun tileName(tile: String): String {
        val honors = mapOf("E" to "东", "S" to "南", "W" to "西", "N" to "北", "P" to "白", "F" to "发", "C" to "中")
        honors[tile]?.let { return it }
        if (tile.length < 2) return "—"
        val suit = when (tile[1]) { 'm' -> "万"; 'p' -> "筒"; 's' -> "索"; else -> "" }
        return (if (tile.endsWith("r")) "赤" else "") + tile[0] + suit
    }

    /** Badges sit in the clear band right above the player's hand tiles (~21% up from bottom). */
    private fun top(height: Int) = (height * (1f - BAND)).roundToInt() - dp(34)
    private fun dp(value: Int) = (value * display.density).roundToInt()

    private fun detach() {
        val frame = root ?: return
        root = null
        runCatching { windows?.removeView(frame) }
    }

    private companion object {
        val DISCARD_KINDS = setOf("discard", "riichi")
        const val BAND = 0.21f
    }
}
