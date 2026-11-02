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
    companion object {
        /** Viewport tolerance so edge-sitting tiles are not dropped by float drift. */
        private const val EPS = 0.005

        /** Discard kinds that get a badge card above their tile. */
        private val DISCARD_KINDS = setOf("discard", "riichi")

        /** Badge anchor sits this fraction of the overlay height above the tile top. */
        private const val BADGE_LIFT_FRACTION = 0.0333f

        /**
         * Filters a raw `hand_coords` array into valid in-viewport tiles plus a
         * bounded textual sample of the rejected ones. Pure and testable: it does
         * not touch the overlay window, so a camera/projection fault that sends
         * every coordinate off-screen is observable as `kept=0` with a sample
         * rather than as an empty overlay.
         */
        internal fun filterCoords(coords: JSONArray): FilteredCoords {
            val kept = ArrayList<HookedTile>(coords.length())
            var rejected = 0
            val sample = StringBuilder()
            for (i in 0 until coords.length()) {
                val obj = coords.optJSONObject(i) ?: continue
                val tile = obj.optString("tile")
                val nx = obj.optDouble("nx", Double.NaN)
                val ny = obj.optDouble("ny", Double.NaN)
                val isLast = obj.optBoolean("last", false)
                if (tile.isNotBlank() && nx.isFinite() && ny.isFinite() &&
                    nx > -EPS && nx < 1.0 + EPS && ny > -EPS && ny < 1.0 + EPS) {
                    kept.add(HookedTile(tile, nx, ny, isLast))
                } else {
                    rejected++
                    if (sample.length < 96 && nx.isFinite() && ny.isFinite()) {
                        if (sample.isNotEmpty()) sample.append(' ')
                        sample.append(tile.ifBlank { "?" })
                            .append('@').append(String.format(Locale.ROOT, "%.3f", nx))
                            .append(',').append(String.format(Locale.ROOT, "%.3f", ny))
                    }
                }
            }
            return FilteredCoords(kept, rejected, sample.toString())
        }
    }

    internal data class FilteredCoords(
        val kept: List<HookedTile>,
        val rejected: Int,
        val sample: String
    )

    internal data class HookedTile(
        val tile: String,
        val normX: Double,
        val normY: Double,
        val isLast: Boolean
    )

    private data class DiscardHint(
        val kind: String,
        val tile: String,
        val weight: Double,
        val handIndex: Int
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

    /** Changes whenever the rendered order snapshot would differ. */
    private var lastOrderSignature: String = ""

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

        // The AI result changes on every poll, but its hand only changes on a
        // discard/draw/meld/round boundary. When it does change, any coordinates
        // captured for the previous shape are stale. Instead of dropping them on a
        // seat/round key (which is an AI-side dimension that does not advance in
        // lock-step with the game-side tile views), compare the on-screen tile
        // multiset against the protocol hand. Only a genuine tile-set mismatch
        // clears the anchors, so a coordinate set that arrives right after the
        // hand changes still pairs correctly.
        if (hand.isNotEmpty() && hookedTiles.isNotEmpty()) {
            val handCounts = hand.groupingBy { canonicalTile(it) }.eachCount()
            val viewCounts = hookedTiles.groupingBy { canonicalTile(it.tile) }.eachCount()
            if (handCounts != viewCounts) {
                GeometryTrace.note("HAND_SHAPE", "views=${hookedTiles.size};hand=${hand.size}")
                hookedTiles = emptyList()
            }
        }

        traceOrders()
        renderHints()
    }

    /** Re-renders the current recommendation set against the current coordinates. */
    private fun renderHints() {
        val status = result?.optString("status")
        val recommendations = result?.optJSONArray("recommendations")
        if ((status == "live" || status == "demo") && recommendations != null && recommendations.length() > 0) {
            render(parseHints(recommendations))
        } else {
            render(emptyList())
        }
    }

    fun updateHookedCoords(coords: JSONArray) {
        val filtered = filterCoords(coords)
        val list = filtered.kept
        if (list.isNotEmpty()) {
            if (filtered.rejected > 0 || list.size != hookedTiles.size) {
                GeometryTrace.note(
                    "HOOK_COORDS",
                    "kept=${list.size};rejected=${filtered.rejected}" +
                        if (filtered.sample.isNotEmpty()) ";sample=${filtered.sample}" else ""
                )
            }
            hookedTiles = list
            traceOrders()
            renderHints()
        } else {
            // Always record a fully-rejected capture, including its sample, so a
            // camera/projection fault is visible instead of looking like "no data".
            GeometryTrace.note(
                "HOOK_COORDS",
                "kept=0;rejected=${filtered.rejected}" +
                    if (filtered.sample.isNotEmpty()) ";sample=${filtered.sample}" else ""
            )
            // Never keep stale coordinates from the previous round/seat/rotation.
            hookedTiles = emptyList()
            renderHints()
        }
    }

    fun close() {
        result = null
        hookedTiles = emptyList()
        lastOrderSignature = ""
        GeometryTrace.reset()
        detach()
    }

    fun configurationChanged() {
        // A rotation/resize invalidates every pixel anchor captured before it.
        hookedTiles = emptyList()
        lastOrderSignature = ""
        detach()
        result?.let { update(it) }
    }

    /** Measured overlay size, or the display metrics before the window is laid out. */
    fun describeGeometry(): Pair<Int, Int> {
        val frame = root
        val w = frame?.width?.takeIf { it > 0 } ?: display.widthPixels
        val h = frame?.height?.takeIf { it > 0 } ?: display.heightPixels
        return w to h
    }

    /**
     * Emits one bounded record per distinct hand shape that contains both orderings
     * side by side, so the pairing logic can be validated against real rounds:
     *
     *  - `order`: protocol hand (AI tehai) tile names, index order;
     *  - `views`: game view tile names with their normalized coordinates, view order;
     *  - `reds`:   how many red fives each side reports.
     *
     * Tile names and coordinates are plain gameplay state, not credentials, and the
     * store sanitises them to letters/digits plus a few separators. Emitted only when
     * the hand shape changes, so a full round produces a handful of lines, not a
     * per-tick stream. Intended to answer: is view order == protocol order, and is
     * the red-five encoding consistent between the two sources.
     */
    private fun traceOrders() {
        if (hookedTiles.isEmpty() || hand.isEmpty()) return
        val signature = hand.joinToString(",") + "#" + hookedTiles.joinToString(",") { "${it.tile}@${it.normX}" }
        if (signature == lastOrderSignature) return
        lastOrderSignature = signature
        val views = hookedTiles.joinToString(" ") {
            "${canonicalTile(it.tile)}@${String.format(java.util.Locale.ROOT, "%.3f", it.normX)}"
        }
        val reds = hand.count { canonicalTile(it).endsWith("r") }.toString() + "/" +
            hookedTiles.count { canonicalTile(it.tile).endsWith("r") }
        GeometryTrace.note(
            "HINT_ORDER",
            "order=${hand.joinToString(",")};views=$views;reds=$reds;" +
                "hand=${hand.size};views_n=${hookedTiles.size}"
        )
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

            // Red fives are distinct tiles: "0m" (protocol) and "5mr" (game view)
            // both mean the aka five, while "5m" is the ordinary five. Folding them
            // into one key made a recommendation for the red five match whichever
            // normal five happened to appear first in the hand.
            val normalizedTile = canonicalTile(tile)

            var targetIndex = -1
            if (hand.isNotEmpty()) {
                for (hIdx in hand.indices) {
                    if (hIdx !in usedIndices && canonicalTile(hand[hIdx]) == normalizedTile) {
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

    /**
     * Maps each hinted hand index to an on-screen (x, y) anchor.
     *
     * The protocol hand (AI `tehai`) and the game view list (`_handpai`, after the
     * Lua side drops melds and the wildcard) are two independent orderings. They
     * are known to carry the same multiset of tiles, but their relative order is
     * not guaranteed.
     *
     * Only the *hinted* tile type needs to be unique. A duplicated tile that is
     * not being recommended cannot move a badge, so it never suppresses one:
     * with hand `1m 1m 9s`, a hint on `9s` still lands correctly.
     *
     * Without any hook coordinates there is no reliable anchor, so nothing is
     * drawn. The old calibrated-row fallback silently depended on a guessed pitch
     * measured on one device and put badges on the wrong tile everywhere else; it
     * has been removed rather than kept as a wrong-but-plausible default.
     */
    private fun positionHints(hints: List<DiscardHint>, frame: FrameLayout): Map<Int, Pair<Int, Int>> {
        val placed = HashMap<Int, Pair<Int, Int>>(hints.size)
        if (hints.isEmpty()) return placed
        if (hookedTiles.isEmpty()) {
            GeometryTrace.note("BADGE_HIDDEN", "reason=no_coords;hints=${hints.size};hand=${hand.size}")
            return placed
        }

        // Normalized coordinates describe a *relative* position in the game's
        // viewport. Mapping them onto the overlay's own measured size is correct
        // whenever the two viewports share an aspect ratio and full-screen coverage
        // (the common case: the overlay is MATCH_PARENT + NO_LIMITS + IN_SCREEN).
        // It does not require the two pixel sizes to be numerically equal, and it is
        // not claimed to hold for a letterboxed or non-full-screen viewport; the
        // recorded screenW/screenH vs overlayW/overlayH values let that be checked
        // per device rather than assumed here.
        val frameWidth = frame.width.takeIf { it > 0 } ?: display.widthPixels
        val frameHeight = frame.height.takeIf { it > 0 } ?: display.heightPixels
        val lift = (frameHeight * BADGE_LIFT_FRACTION).roundToInt()

        val viewCounts = hookedTiles.groupingBy { canonicalTile(it.tile) }.eachCount()
        var ambiguous = 0
        var missing = 0

        for (hint in hints) {
            val key = canonicalTile(hint.tile)
            // Ambiguity is only relevant for the tile we actually want to point at.
            if ((viewCounts[key] ?: 0) > 1) {
                ambiguous++
                continue
            }
            val view = hookedTiles.firstOrNull { canonicalTile(it.tile) == key }
            if (view == null) {
                missing++
                continue
            }
            placed[hint.handIndex] = (view.normX * frameWidth).roundToInt() to
                ((view.normY * frameHeight).roundToInt() - lift).coerceAtLeast(0)
        }

        if (ambiguous > 0 || missing > 0) {
            GeometryTrace.note(
                "BADGE_PARTIAL",
                "ambiguous=$ambiguous;missing=$missing;placed=${placed.size};hand=${hand.size};" +
                    "views=${hookedTiles.size};overlayW=$frameWidth;overlayH=$frameHeight"
            )
        }
        return placed
    }

    /**
     * Canonical tile key that keeps red fives distinct.
     *
     * `0m` (protocol encoding) and `5mr` (game view encoding) both denote the aka
     * five; `5m` denotes the ordinary five. Everything else passes through.
     */
    private fun canonicalTile(tile: String): String {
        val trimmed = tile.trim()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "?") return trimmed
        val red = trimmed.replace(Regex("^0([mps])$"), "$1r")
        return if (red.endsWith("r")) "5" + red[0] + "r" else red
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
        val placement = positionHints(hints, frame)

        for ((rank, hint) in hints.withIndex()) {
            val targetX = placement[hint.handIndex] ?: continue
            val badgeView = createBadgeCard(hint, rank)
            frame.addView(badgeView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            badgeTargets.add(badgeView to targetX)
        }

        if (badgeTargets.isEmpty()) {
            // No trustworthy tile anchor: show the floating text panel only rather
            // than drawing a badge over the wrong tile.
            GeometryTrace.note("BADGE_HIDDEN", "reason=no_anchor;hints=${hints.size};views=${hookedTiles.size}")
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
}
