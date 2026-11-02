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
 * Pixel-perfect high-visibility solid color badge cards rendered directly above
 * each recommended hand tile, matching ZizhiAssistant / Akagi in-game HUD.
 */
internal class TileBadgeOverlay(private val context: Context) {
    private data class DiscardHint(
        val kind: String,
        val tile: String,
        val weight: Double,
        val handIndex: Int
    )

    private data class HookedTile(
        val tile: String,
        val normX: Double,
        val normY: Double,
        val isLast: Boolean
    )

    private val windows = context.getSystemService(WindowManager::class.java)
    private val display = context.resources.displayMetrics

    // High-visibility solid colors matching reference
    private val primaryColor = Color.rgb(255, 140, 0)   // Vivid Deep Orange for top recommendation
    private val secondaryColor = Color.rgb(33, 150, 243) // Bright Blue for secondary recommendations
    private val subtleColor = Color.rgb(41, 128, 185)    // Muted Steel Blue for minor suggestions

    private var root: FrameLayout? = null
    private var hand: List<String> = emptyList()
    private var result: JSONObject? = null
    private var hookedTiles: List<HookedTile> = emptyList()

    private val layout = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
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

    fun updateHookedCoords(coords: JSONArray) {
        val list = ArrayList<HookedTile>(coords.length())
        for (i in 0 until coords.length()) {
            val obj = coords.optJSONObject(i) ?: continue
            val tile = obj.optString("tile")
            val nx = obj.optDouble("nx", Double.NaN)
            val ny = obj.optDouble("ny", Double.NaN)
            val isLast = obj.optBoolean("last", false)
            // Filter strictly valid screen viewport bounds to reject invalid 3D camera projections
            if (tile.isNotBlank() && nx in 0.15..0.85 && ny in 0.65..0.98) {
                list.add(HookedTile(tile, nx, ny, isLast))
            }
        }
        if (list.isNotEmpty()) {
            hookedTiles = list
            val recommendations = result?.optJSONArray("recommendations")
            val status = result?.optString("status")
            if ((status == "live" || status == "demo") && recommendations != null && recommendations.length() > 0) {
                render(parseHints(recommendations))
            }
        }
    }

    fun close() {
        result = null
        hookedTiles = emptyList()
        detach()
    }

    fun configurationChanged() {
        detach()
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

            // Scan hand in natural left-to-right order so each badge attaches to its exact tile
            var targetIndex = -1
            if (hand.isNotEmpty()) {
                for (hIdx in hand.indices) {
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

        val badgeTargets = ArrayList<Pair<LinearLayout, Pair<Int, Int>>>()
        val usedHooked = HashSet<Int>()

        for ((rank, hint) in hints.withIndex()) {
            val badgeView = createBadgeCard(hint, rank)
            var targetX: Int? = null
            var targetY: Int? = null

            // 1. Try valid Hook coordinates if available
            if (hookedTiles.isNotEmpty()) {
                for ((idx, ht) in hookedTiles.withIndex()) {
                    if (idx in usedHooked) continue
                    val normalizedHt = ht.tile.replace(Regex("^0([mps])$"), "5$1").removeSuffix("r")
                    if (normalizedHt == hint.tile.removeSuffix("r")) {
                        targetX = (ht.normX * display.widthPixels).roundToInt()
                        targetY = ((ht.normY * display.heightPixels).roundToInt() - dp(36)).coerceAtLeast(0)
                        usedHooked.add(idx)
                        break
                    }
                }
            }

            // 2. High-precision geometric positioning calibrated directly on real gameplay
            if (targetX == null) {
                val tileCenters = computeTileCenters(hand.size)
                targetX = tileCenters.getOrNull(hint.handIndex)
                // Positioned right above the hand tiles upper edge (86.5% screen height)
                targetY = (display.heightPixels * 0.865f).roundToInt() - dp(34)
            }

            if (targetX != null && targetY != null) {
                frame.addView(badgeView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))
                badgeTargets.add(badgeView to (targetX to targetY))
            }
        }

        if (badgeTargets.isEmpty()) {
            detach()
            return
        }

        if (root?.parent == null) {
            runCatching { manager.addView(frame, layout) }
        } else {
            runCatching { manager.updateViewLayout(frame, layout) }
        }

        frame.post {
            for ((badgeView, target) in badgeTargets) {
                val (centerX, topY) = target
                val badgeWidth = badgeView.width
                val badgeHeight = badgeView.height
                val desiredLeft = (centerX - badgeWidth / 2)
                    .coerceIn(dp(2), (frame.width - badgeWidth - dp(2)).coerceAtLeast(0))
                val desiredTop = topY.coerceIn(dp(2), (frame.height - badgeHeight - dp(2)).coerceAtLeast(0))

                val lp = badgeView.layoutParams as FrameLayout.LayoutParams
                if (lp.leftMargin != desiredLeft || lp.topMargin != desiredTop) {
                    lp.leftMargin = desiredLeft
                    lp.topMargin = desiredTop
                    badgeView.layoutParams = lp
                }
            }
        }
    }

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
            setStroke(dp(1), Color.argb(130, 255, 255, 255))
        }

        val isRiichi = hint.kind == "riichi"
        val actionText = (if (isRiichi) "立·" else "切") + tileDisplayName(hint.tile)

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
     * Calibrated strictly to the physical device screen (2376x1080) and in-game geometry:
     * - Hand horizontal center is at 45.61% of screen width (1084px in 2376px)
     * - Tile pitch is 4.24% of screen width (101px in 2376px)
     * - Drawn tile gap is 2.0% of screen width (48px in 2376px)
     */
    private fun computeTileCenters(handSize: Int): List<Int> {
        val screenW = display.widthPixels
        val count = if (handSize in 1..14) handSize else 14

        val tilePitch = (screenW * 0.0424f).roundToInt()
        val drawnGap = (screenW * 0.020f).roundToInt()
        val totalHandWidth = (count - 1) * tilePitch + if (count >= 13) drawnGap else 0

        // Hand is anchored at 45.61% screen center
        val startX = (screenW * 0.4561f - totalHandWidth / 2f).roundToInt()

        return (0 until count).map { i ->
            if (i < count - 1 || count < 13) {
                startX + i * tilePitch + tilePitch / 2
            } else {
                // The last slot is the drawn tile (separated by drawnGap)
                startX + (count - 1) * tilePitch + drawnGap + tilePitch / 2
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
