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
 * High-visibility solid color badge cards rendered precisely above each recommended hand tile,
 * mirroring the classic mahjong assistant HUD styling (ZizhiAssistant / Akagi).
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

    // Vibrant solid colors matching the reference style (Image 2)
    private val primaryColor = Color.rgb(255, 140, 0)   // Vivid Deep Orange for top pick
    private val secondaryColor = Color.rgb(33, 150, 243) // Bright Blue for secondary picks
    private val subtleColor = Color.rgb(41, 128, 185)    // Muted Blue for minor suggestions

    private var root: FrameLayout? = null
    private var hand: List<String> = emptyList()
    private var result: JSONObject? = null

    private val layout = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, dp(36),
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
        val hints = ArrayList<DiscardHint>(4)
        val usedIndices = HashSet<Int>()

        for (i in 0 until rows.length()) {
            if (hints.size >= 4) break
            val row = rows.optJSONObject(i) ?: continue
            val kind = row.optString("kind")
            val tile = row.optString("tile")
            if (kind !in DISCARD_KINDS || tile.isBlank() || tile == "null") continue

            val normalizedTile = tile.replace(Regex("^0([mps])$"), "5$1")

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
                if (targetIndex < 0) continue
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
        val badgeViews = ArrayList<Pair<LinearLayout, Int>>()

        for ((rank, hint) in hints.withIndex()) {
            val center = tileCenters.getOrNull(hint.handIndex)
            if (center != null) {
                val badgeView = createBadgeCard(hint, rank)
                frame.addView(badgeView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))
                badgeViews.add(badgeView to center)
            }
        }

        if (badgeViews.isEmpty()) {
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
            for ((badgeView, centerX) in badgeViews) {
                val badgeWidth = badgeView.width
                val desiredLeft = (centerX - badgeWidth / 2)
                    .coerceIn(dp(2), (frame.width - badgeWidth - dp(2)).coerceAtLeast(0))
                val lp = badgeView.layoutParams as FrameLayout.LayoutParams
                if (lp.leftMargin != desiredLeft) {
                    lp.leftMargin = desiredLeft
                    badgeView.layoutParams = lp
                }
            }
        }
    }

    /**
     * Creates a solid-color two-row badge card (action on top, probability on bottom)
     * matching the style shown in the reference assistant (Image 2).
     */
    private fun createBadgeCard(hint: DiscardHint, rank: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = false
        isFocusable = false
        setPadding(dp(4), dp(1), dp(4), dp(1))

        val bgColor = when (rank) {
            0 -> primaryColor   // Solid Orange/Amber for #1
            1 -> secondaryColor // Solid Bright Blue for #2
            else -> subtleColor // Solid Steel Blue for #3 / #4
        }

        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4).toFloat()
            setColor(bgColor)
            // Delicate white outline for crisp contrast against any game background
            setStroke(dp(1), Color.argb(120, 255, 255, 255))
        }

        val isRiichi = hint.kind == "riichi"
        val actionText = (if (isRiichi) "立·" else "切") + tileDisplayName(hint.tile)

        // Top line: Action name (e.g. "切西", "切9万", "立·切9索")
        addView(TextView(context).apply {
            text = actionText
            textSize = 10f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
        })

        // Bottom line: Percentage (e.g. "94.7%", "73.0%")
        addView(TextView(context).apply {
            text = if (hint.weight > 0.0) String.format(Locale.ROOT, "%.1f%%", hint.weight) else "—"
            textSize = 9.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
        })
    }

    /**
     * Compute horizontal center (X) for each hand tile on the screen.
     * Aligned with actual gameplay screenshot:
     * - Hand begins at ~18.5% of screen width
     * - Tile pitch is ~3.95% of screen width
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

    private fun tileDisplayName(tile: String): String {
        val honors = mapOf("E" to "东", "S" to "南", "W" to "西", "N" to "北", "P" to "白", "F" to "发", "C" to "中")
        honors[tile]?.let { return it }
        if (tile.length < 2) return tile
        val suit = when (tile[1]) { 'm' -> "万"; 'p' -> "筒"; 's' -> "索"; else -> "" }
        return (if (tile.endsWith("r")) "赤" else "") + tile[0] + suit
    }

    /**
     * Vertical position: right above the player's hand tiles.
     * Hand tiles start at ~85.5% screen height in real gameplay; badge rests directly on top edge.
     */
    private fun computeTop(screenH: Int) = (screenH * 0.855f).roundToInt() - dp(35)

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
