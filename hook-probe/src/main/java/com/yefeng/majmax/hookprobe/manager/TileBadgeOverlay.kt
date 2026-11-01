package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Micro badges rendered directly above each recommended discard tile.
 *
 * Fully click-through (FLAG_NOT_TOUCHABLE), positioned precisely above
 * the player's in-hand tiles.
 */
internal class TileBadgeOverlay(private val context: Context) {
    private data class DiscardHint(
        val kind: String,
        val tile: String,
        val weight: Double,
        val handIndex: Int
    )

    private val windows = context.getSystemService(WindowManager::class.java)
    private val display = context.resources.displayMetrics
    private val green = Color.rgb(114, 236, 176)
    private val accent = Color.rgb(120, 190, 255)
    private val dimmed = Color.rgb(200, 210, 220)

    private var root: FrameLayout? = null
    private var hand: List<String> = emptyList()
    private var result: JSONObject? = null

    private val layout = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, dp(26),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = computeTop(display.heightPixels)
    }

    fun show() = render(emptyList())

    fun update(value: JSONObject) {
        result = value
        val currentHand = extractHand(value)
        if (currentHand.isNotEmpty()) hand = currentHand

        val status = value.optString("status")
        val recommendations = value.optJSONArray("recommendations")
        if ((status == "live" || status == "demo") && recommendations != null && recommendations.length() > 0) {
            render(parseHints(recommendations))
        } else {
            render(emptyList())
        }
    }

    fun close() {
        result = null
        detach()
    }

    fun configurationChanged() {
        detach()
        layout.y = computeTop(display.heightPixels)
        result?.let { update(it) }
    }

    private fun parseHints(rows: JSONArray): List<DiscardHint> {
        val hints = ArrayList<DiscardHint>(3)
        val usedIndices = HashSet<Int>()

        for (i in 0 until rows.length()) {
            if (hints.size >= 3) break
            val row = rows.optJSONObject(i) ?: continue
            val kind = row.optString("kind")
            val tile = row.optString("tile")
            if (kind !in DISCARD_KINDS || tile.isBlank() || tile == "null") continue

            // Standardize red-dora format: 0m -> 5m, 0p -> 5p, 0s -> 5s
            val normalizedTile = tile.replace(Regex("^0([mps])$"), "5$1")

            // Locate target tile position in player's current hand.
            // When multiple identical tiles exist, prefer the drawn tile (last tile) or rightmost copy.
            var targetIndex = -1
            if (hand.isNotEmpty()) {
                for (hIdx in hand.indices.reversed()) {
                    val hTile = hand[hIdx].replace(Regex("^0([mps])$"), "5$1").removeSuffix("r")
                    if (hTile == normalizedTile.removeSuffix("r") && hIdx !in usedIndices) {
                        targetIndex = hIdx
                        usedIndices.add(hIdx)
                        break
                    }
                }
                if (targetIndex < 0) continue // Tile is not currently in hand
            }

            val rawWeight = row.optDouble("weight", Double.NaN).takeIf { it.isFinite() }
                ?: (row.optDouble("policyProbability", 0.0) * 100).takeIf { it.isFinite() } ?: 0.0

            hints.add(DiscardHint(kind, normalizedTile, rawWeight.coerceIn(0.0, 100.0), targetIndex))
        }
        return hints
    }

    private fun extractHand(value: JSONObject): List<String> {
        value.optJSONArray("hand")?.let(::parseTiles)?.takeIf { it.isNotEmpty() }?.let { return it }
        val analysis = value.optJSONObject("analysis") ?: return emptyList()
        parseTiles(analysis.optJSONArray("hand14")).takeIf { it.isNotEmpty() }?.let { return it }
        return parseTiles(analysis.optJSONArray("hand13"))
    }

    private fun parseTiles(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optString(i).takeIf { it.isNotBlank() && it != "null" && it != "?" }
        }
    }

    private fun render(hints: List<DiscardHint>) {
        if (hints.isEmpty()) {
            detach()
            return
        }
        val manager = windows ?: return
        val frame = root ?: FrameLayout(context).apply {
            isClickable = false
            isFocusable = false
        }.also { root = it }

        frame.removeAllViews()

        val tileCenters = computeTileCenters(hand.size)
        val badges = ArrayList<Pair<TextView, Int>>()

        for ((rank, hint) in hints.withIndex()) {
            val badgeView = createBadge(hint, rank)
            val center = tileCenters.getOrNull(hint.handIndex)
            if (center != null) {
                frame.addView(badgeView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))
                badges.add(badgeView to center)
            }
        }

        if (badges.isEmpty()) {
            detach()
            return
        }

        layout.y = computeTop(display.heightPixels)
        if (root?.parent == null) {
            runCatching { manager.addView(frame, layout) }
        } else {
            runCatching { manager.updateViewLayout(frame, layout) }
        }

        frame.post {
            for ((badgeView, centerX) in badges) {
                val badgeWidth = badgeView.width
                val desiredLeft = (centerX - badgeWidth / 2)
                    .coerceIn(dp(4), (frame.width - badgeWidth - dp(4)).coerceAtLeast(0))
                val lp = badgeView.layoutParams as FrameLayout.LayoutParams
                if (lp.leftMargin != desiredLeft) {
                    lp.leftMargin = desiredLeft
                    badgeView.layoutParams = lp
                }
            }
        }
    }

    /**
     * Compute horizontal center (X) for every hand tile on the screen.
     * In Majsoul landscape gameplay:
     * - Hand starts at ~18.5% screen width
     * - Drawn tile (14th tile) ends at ~75.8% screen width
     * - Tile pitch is ~3.95% screen width
     * - Drawn tile has ~0.65x pitch gap from the 13th tile
     */
    private fun computeTileCenters(handSize: Int): List<Int> {
        val screenW = display.widthPixels
        val count = if (handSize in 1..14) handSize else 14

        val startX = (screenW * 0.185f).roundToInt()
        val tilePitch = (screenW * 0.0395f).roundToInt()
        val drawnGap = (tilePitch * 0.65f).roundToInt()

        return (0 until count).map { i ->
            if (i < 13) {
                startX + i * tilePitch + tilePitch / 2
            } else {
                startX + 13 * tilePitch + drawnGap + tilePitch / 2
            }
        }
    }

    private fun createBadge(hint: DiscardHint, rank: Int) = TextView(context).apply {
        val isRiichi = hint.kind == "riichi"
        val prefix = if (isRiichi) "立·" else ""
        text = if (hint.weight > 0.0) {
            "$prefix${String.format(Locale.ROOT, "%.1f%%", hint.weight)}"
        } else {
            if (isRiichi) "立直" else "推荐"
        }

        textSize = 10f
        setTextColor(when (rank) { 0 -> green; 1 -> accent; else -> dimmed })
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        includeFontPadding = false
        maxLines = 1
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(1), dp(4), dp(1))
        elevation = dp(2).toFloat()
        isClickable = false
        isFocusable = false

        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setColor(when (rank) {
                0 -> Color.argb(235, 10, 44, 28)
                1 -> Color.argb(230, 16, 32, 54)
                else -> Color.argb(220, 24, 28, 36)
            })
            setStroke(dp(1), when (rank) {
                0 -> green
                1 -> Color.argb(200, 120, 190, 255)
                else -> Color.argb(160, 160, 175, 190)
            })
        }
    }

    /**
     * Vertical position: precisely sitting on top of the hand tile upper edge.
     * In Majsoul, hand tiles start at ~81% of screen height from top.
     */
    private fun computeTop(screenH: Int) = (screenH * 0.805f).roundToInt() - dp(24)

    private fun dp(value: Int) = (value * display.density).roundToInt()

    private fun detach() {
        val frame = root ?: return
        root = null
        runCatching { windows?.removeView(frame) }
    }

    private companion object {
        val DISCARD_KINDS = setOf("discard", "riichi")
    }
}
