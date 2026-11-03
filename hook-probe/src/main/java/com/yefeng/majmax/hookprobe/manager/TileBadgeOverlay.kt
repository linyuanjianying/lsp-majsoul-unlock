package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Why a badge could not be drawn. Each case is reported with its own reason token so
 * the log distinguishes an unset source viewport from a frame that has not been laid
 * out yet or from an origin that could not be read, instead of collapsing everything
 * into the old catch-all `no_origin`.
 */
internal enum class FrameFailure(val reason: String) {
    SOURCE_MISSING("source_missing"),
    FRAME_UNMEASURED("frame_unmeasured"),
    METRICS_INVALID("metrics_invalid"),
    ORIGIN_MISSING("origin_missing"),
}

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

        /**
         * Honor letters (protocol hand / recommendation) to `1z..7z` (game view).
         * `P` is the white dragon (白) and `F` the green dragon (發), consistent
         * with `AutoDiscardController.toGameTile` and Lua `tile()`.
         */
        private val HONOR_TO_Z = mapOf(
            "E" to "1z", "S" to "2z", "W" to "3z", "N" to "4z",
            "P" to "5z", "F" to "6z", "C" to "7z",
        )

        /**
         * Distance, as a fraction of the rendered frame height, from the projected tile
         * anchor (Unity-normalized `ny` of the tile's own transform) up to the visual
         * **top edge** of that tile.
         *
         * The anchor is not the tile's top edge: the game projects a point inside the
         * card, so a badge placed at the raw anchor lands on the tile face. Measured on
         * the reference device (2376x1080 render, overlay frame 2256x1080 at origin
         * 120, game build `lastUpdate 2026-11-03 14:28:47`): the live log reports
         * `ny = 0.9228` → anchorY = 997 px, while the matching screenshot shows the
         * hand tile's top edge at y ≈ 922 px — a 75 px anchor→tile-top distance
         * (75 / 1080 = 0.0694 of the frame height).
         *
         * Deliberately the coordinate-normalized ratio, not a fraction of the card
         * height: the same coordinate payload's camera perspective compresses `ny`
         * differences between tiles (~16 px from row to row in that log), so a card
         * height re-estimated from those differences came out at ~48 px and left the
         * badge ~40 px too low. The frame-normalized distance is the one quantity the
         * log and the screenshot agree on. It scales with resolution and density but is
         * tied to the camera the projection was taken with.
         */
        private const val TILE_TOP_OFFSET_OF_FRAME_HEIGHT = 0.0694f

        /**
         * Sanity band for the derived card height when it is estimated from a sample
         * ([estimateCardHeightPx]) — a card is a small but visible fraction of the hand
         * area. The estimate is used to *report* the calibration and to reject a sample
         * that cannot be a hand at all; it does not move the offset above.
         */
        private const val CARD_HEIGHT_MIN_OF_FRAME = 0.045f
        private const val CARD_HEIGHT_MAX_OF_FRAME = 0.16f

        /** Small air gap between the badge's bottom edge and the tile's top edge. */
        private const val BADGE_GAP_DP = 1

        /**
         * Reference card geometry measured on the 2376x1080 screenshot: a resting
         * hand tile is ~94-95 px wide and its visible face spans y 922..1074 (~152 px
         * high), i.e. ~14.1 % of the frame height.
         *
         * A badge is drawn *one card per hint*, so it is sized like the card it points
         * at instead of using a shrink-wrapped text card (~111x81 px on the reference
         * frame). Both figures are frame-height normalized so the badge keeps the same
         * visual proportion on other resolutions.
         */
        private const val TILE_FACE_HEIGHT_OF_FRAME = 0.1407f

        /**
         * Card pitch fallbacks, as a fraction of the frame *width*, used only when the
         * coordinate sample yields no trustworthy horizontal step (a single anchored
         * hint). Derived from the live projection: adjacent standard tiles differ by
         * nx ≈ 0.0399, i.e. ~95 px at 2376 px wide.
         */
        private const val TILE_PITCH_OF_FRAME_WIDTH = 0.0400f

        /**
         * The rightmost tile of the row is the freshly drawn one and sits ~30 px off
         * the resting pitch (~1.3 card widths). Steps that large are snapped by
         * [deriveBadgeSizePx]; small jitter is absorbed by these bounds instead.
         */
        private const val TILE_PITCH_TOLERANCE = 0.15
        private const val MIN_TILE_PITCH_OF_FRAME_WIDTH = 0.0300f
        private const val MAX_TILE_PITCH_OF_FRAME_WIDTH = 0.0550f

        /** Clamps for the derived badge *height* (card-face height), as frame fractions. */
        private const val BADGE_HEIGHT_MIN_OF_FRAME = 0.09f
        private const val BADGE_HEIGHT_MAX_OF_FRAME = 0.20f

        /**
         * Text sizes (sp, density-independent) for the fits-inside-the-card badge. The
         * two lines share the card height via weights, so these only need to stay below
         * half of a card face: ~14 sp / ~12 sp against the ~152 px card at 1080p.
         */
        private const val BADGE_ACTION_TEXT_SP = 13f
        private const val BADGE_WEIGHT_TEXT_SP = 11.5f

        /**
         * Typographic hand-card aspect ratio for a tile (height / width ≈ 1.36).
         * Used only to convert a measured *width* step between tiles into a card
         * height when no height sample is available.
         */
        private const val HAND_CARD_ASPECT = 1.36f

        /**
         * Drops and re-arm padding (px at 1080p, scaled). A badge is never pushed off
         * the matching tile: if its top would leave the frame it shrinks to the room
         * above the tile, and if it still does not fit it is skipped and reported.
         */
        private const val REFERENCE_FRAME_HEIGHT = 1080f
        private const val EDGE_INSET_REF = 2f
        private const val MIN_BADGE_HEIGHT_REF = 20f

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

        /**
         * Canonical tile key shared by the protocol hand, the AI recommendation,
         * and the game view so a hint can be paired with the on-screen tile it
         * points at.
         *
         * Two independent encodings must be folded into one key:
         *
         *  - Honors: the protocol hand and recommendations spell them as single
         *    letters (`E S W N P F C`) while the game view reports `1z..7z`. `P` is
         *    the white dragon (白) and `F` the green dragon (發), matching Lua
         *    `tile()`.
         *  - Red fives: `0m/0p/0s` (zero encoding) and `5mr/5pr/5sr` (r-suffix)
         *    are the same aka five; the r-suffix form is the canonical output and
         *    must not be rebuilt into a corrupt `55r` key.
         */
        internal fun canonicalTile(tile: String): String {
            val trimmed = tile.trim()
            if (trimmed.isEmpty() || trimmed == "null" || trimmed == "?") return trimmed
            HONOR_TO_Z[trimmed]?.let { return it }
            if (trimmed.matches(Regex("^0([mps])$"))) return "5" + trimmed[1] + "r"
            return trimmed
        }

        internal data class BadgePlacement(
            /**
             * handIndex -> projected tile anchor in overlay-local pixels: the tile's
             * `x` centre and the raw projected `y`. The anchor deliberately carries
             * **no** lift: the vertical offset between that anchor and the visual top
             * edge of the card is applied later, once the badge view has been measured
             * (see [computeBadgeTop]).
             */
            val placed: Map<Int, Pair<Int, Int>>,
            val ambiguous: Int,
            val missing: Int,
            val pairingTrace: String,
            /**
             * Calibrated card height in overlay-local pixels for this render, derived
             * from the coordinates themselves; null when the sample is too weak to
             * trust (a single visible tile with no usable frame geometry).
             */
            val cardHeightPx: Int? = null,
        ) {
            /** Raw projected `y` of the newest anchor, for the vertical trace. */
            fun anchorY(): Int? = placed.values.maxOfOrNull { it.second }
        }

        /**
         * Estimates the on-screen height of one hand card from the coordinate sample
         * itself. Used for the vertical trace (`BADGE_VERTICAL cardH=…`) so the real
         * card size on any device is visible next to the placement; the anchor→tile-top
         * offset itself is frame-normalized (see
         * [TILE_TOP_OFFSET_OF_FRAME_HEIGHT]) because this estimate proved too small on
         * the reference camera (two tiles on nearly the same `ny` row).
         *
         * Two independent estimates, best first:
         *
         *  1. `dy`: tiles that report a *lower* `ny` than the first tile in the sample
         *     are drawn (or picked up, i.e. the drawn tile) above the resting row; the
         *     mean difference approximates one card height in source pixels.
         *  2. `dx`: the median horizontal step between adjacent tiles is the card pitch
         *     (no overlap in the live game), i.e. one card width on screen.
         *
         * The chosen estimate is clamped to a plausible band for a hand card
         * ([CARD_HEIGHT_MIN_OF_FRAME]..[CARD_HEIGHT_MAX_OF_FRAME] of the render
         * height) so a malformed sample can never report an absurd card size, and the
         * source→overlay scale is applied so the result is in overlay-local pixels.
         *
         * Returns null when neither estimate is available.
         */
        internal fun estimateCardHeightPx(
            views: List<HookedTile>,
            mapping: HandCoordinateMapper.Mapping,
        ): Int? {
            if (!HandCoordinateMapper.isUsable(mapping) || views.isEmpty()) return null
            val scaleY = mapping.viewportH.toDouble() / mapping.sourceH
            val referenceNy = views[0].normY
            val shorter = views.drop(1).map { referenceNy - it.normY }.filter { it > 0.0 }
            val raw = when {
                shorter.isNotEmpty() -> shorter.average() * mapping.sourceH
                views.size >= 2 -> {
                    val steps = views.zipWithNext { a, b -> kotlin.math.abs(b.normX - a.normX) }
                        .filter { it > 0.0 }
                        .sorted()
                    if (steps.isEmpty()) return null
                    val pitch = steps[steps.size / 2] * mapping.sourceW
                    pitch / HAND_CARD_ASPECT
                }
                else -> return null
            }
            val minH = CARD_HEIGHT_MIN_OF_FRAME.toDouble() * mapping.sourceH
            val maxH = CARD_HEIGHT_MAX_OF_FRAME.toDouble() * mapping.sourceH
            val clamped = raw.coerceIn(minH, maxH)
            return kotlin.math.round(clamped * scaleY).toInt()
        }

        /**
         * The card-sized geometry of one badge card, in overlay-local pixels.
         *
         * [width] / [height] are the *card face* size the badge is drawn at, never a
         * shrink-wrapped text box: the screenshot shows hand tiles ~95 px wide and
         * ~152 px tall at 1080p, so a badge is one card wide and one card tall and sits
         * directly above (never on) the tile it hints.
         *
         * [source] is a short, bounded tag (`pitch`, `fallback`, `mixed`) recorded in
         * the device trace so a log can be checked without re-deriving the sample.
         */
        internal data class BadgeSize(val width: Int, val height: Int, val source: String)

        /**
         * Median of [values], or null for an empty input. Even counts average the two
         * middle samples. Used to make one outlier (the drawn tile, a mis-projected
         * neighbour) irrelevant instead of dragging a mean.
         */
        internal fun medianOf(values: List<Float>): Float? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
        }

        /**
         * Derives the on-screen card width (tile pitch) in source pixels from the
         * coordinate sample.
         *
         * Steps between *adjacent* tiles only: `views` arrives sorted by x, so a
         * neighbouring step is one resting pitch. The rightmost tile is the drawn tile
         * and loosens its neighbour by ~1.3 card widths; that step is ~30 px wider than
         * the rest, so it is rejected before the median. The median itself then makes
         * any remaining single outlier harmless.
         *
         * Returns null when no step can be trusted, so the caller can fall back to the
         * resolution-normalized [TILE_PITCH_OF_FRAME_WIDTH].
         */
        internal fun deriveTilePitchSourcePx(views: List<HookedTile>): Pair<Float, Int>? {
            if (views.size < 2) return null
            val steps = ArrayList<Float>(views.size - 1)
            for (i in 1 until views.size) {
                val dx = (views[i].normX - views[i - 1].normX).toFloat()
                if (dx > 0f && dx.isFinite()) steps.add(dx)
            }
            if (steps.isEmpty()) return null
            val raw = medianOf(steps) ?: return null
            val tolerance = raw * TILE_PITCH_TOLERANCE
            val kept = steps.filter { kotlin.math.abs(it - raw) <= tolerance }
            val pitch = medianOf(kept.ifEmpty { steps }) ?: return null
            // Zero-pitch fallback only if the sample is so broken that nothing survived.
            return if (pitch > 0f) pitch to kept.size else null
        }

        /**
         * Full badge geometry for one render pass: *card sized*, not text sized.
         *
         * Width: the resting card pitch from the coordinate sample
         * ([deriveTilePitchSourcePx]) when it is usable, otherwise the tile's own frame
         * fraction. Both are clamped to a plausible card-pitch band so a distorted
         * sample (sanma hand, zoomed camera, drawn tile) cannot produce a sliver or a
         * banner.
         *
         * The pitch is a *normalized* nx difference, exactly like the mapping: the
         * overlay already spans the viewport one-to-one, so it is converted with the
         * frame width only. Multiplying by the source width as well (raw source px)
         * would size the badge at the camera's logical resolution instead of the
         * screen — the first attempt of this change produced 71 px badges instead of
         * 95 px that way.
         *
         * Height: the visible card face ([TILE_FACE_HEIGHT_OF_FRAME] of the frame
         * height), scaled with the frame; a sample-derived estimate is only used to
         * sanity-band the result on unusual vertical resolutions. The badge keeps its
         * bottom edge on the tile top ([computeBadgeTop]) and grows *upwards*, so this
         * never changes the camera mapping or the tile pairing.
         *
         * Returns null only for a degenerate frame, in which case the caller keeps the
         * shrink-wrapped measurement.
         */
        internal fun deriveBadgeSizePx(
            views: List<HookedTile>,
            mapping: HandCoordinateMapper.Mapping?,
            frameWidth: Int,
            frameHeight: Int,
        ): BadgeSize? {
            if (frameWidth <= 0 || frameHeight <= 0) return null
            val minPitch = MIN_TILE_PITCH_OF_FRAME_WIDTH * frameWidth
            val maxPitch = MAX_TILE_PITCH_OF_FRAME_WIDTH * frameWidth

            val derived = deriveTilePitchSourcePx(views)
            val usable = mapping != null && HandCoordinateMapper.isUsable(mapping)

            val width: Float
            val source: String
            if (derived != null && usable) {
                // `nx` is viewport-normalized: px = nx * viewport width. The overlay
                // content frame normally *is* that viewport, so this factor is 1; it
                // only corrects a frame whose width drifted from the mapped viewport.
                val viewportCorrection = mapping!!.viewportW.toFloat() / frameWidth
                width = (derived.first * frameWidth * viewportCorrection.coerceIn(0.5f, 2f))
                    .coerceIn(minPitch, maxPitch)
                source = if (derived.second >= 2) "pitch" else "mixed"
            } else {
                width = TILE_PITCH_OF_FRAME_WIDTH * frameWidth
                source = "fallback"
            }

            // Height: the visible card face, anchored to the frame height the screenshot
            // was taken at. On very short frames the face would eat the row above the
            // hand, so it is limited by the height the coordinate sample can support
            // (or, without a sample, by the same fraction band).
            val minH = BADGE_HEIGHT_MIN_OF_FRAME * frameHeight
            val maxH = BADGE_HEIGHT_MAX_OF_FRAME * frameHeight
            val faceH = TILE_FACE_HEIGHT_OF_FRAME * frameHeight
            val height = faceH.coerceIn(minH, maxH)

            return BadgeSize(
                width = kotlin.math.round(width).toInt().coerceAtLeast(1),
                height = kotlin.math.round(height).toInt().coerceAtLeast(1),
                source = source,
            )
        }

        /**
         * Vertical placement of one badge, all in overlay-local pixels.
         *
         * The reference framing puts the badge *flush on the tile's top edge*:
         * `badgeBottom == tileTop - gap`, so `badgeTop = anchorY - tileTopOffset -
         * badgeHeight - gap`. The previous code instead lifted the anchor by a fixed
         * fraction of the frame height and treated that lift as the *top* of the badge,
         * which ignored the badge's own height and pushed the badge down over the tile
         * face (device log: anchorY 997 → top 961 with the tile top at ~922).
         *
         * `tileTopOffsetPx` is the measured anchor→tile-top distance
         * ([tileTopOffsetPx] of the frame height), i.e. 75 px at 1080p.
         */
        internal data class BadgeVertical(
            val top: Int,
            val height: Int,
            val tileTop: Int,
        )

        internal fun computeBadgeTop(
            anchorY: Int,
            tileTopOffsetPx: Int,
            badgeHeightPx: Int,
            gapPx: Int,
            frameHeight: Int,
        ): BadgeVertical? {
            if (frameHeight <= 0) return null
            val tileTop = anchorY - tileTopOffsetPx
            val desiredTop = tileTop - badgeHeightPx - gapPx
            val maxHeight = tileTop - gapPx
            if (maxHeight <= 0) return null
            val height = minOf(badgeHeightPx, maxHeight)
            // Keep the whole badge inside the frame: if the row sits high enough that
            // the badge would leave the top edge, shrink it to the available room
            // rather than moving it down onto the tile.
            val top = desiredTop.coerceIn(0, frameHeight - height)
            return BadgeVertical(top, height, tileTop)
        }

        /**
         * Anchor→tile-top distance in overlay-local px for a frame of [frameHeight] px,
         * derived from [TILE_TOP_OFFSET_OF_FRAME_HEIGHT]: 75 px at 1080p.
         */
        internal fun tileTopOffsetPx(frameHeight: Int): Int =
            (frameHeight * TILE_TOP_OFFSET_OF_FRAME_HEIGHT).roundToInt()

        internal fun computeBadgePlacement(
            hints: List<DiscardHint>,
            views: List<HookedTile>,
            mapping: HandCoordinateMapper.Mapping,
        ): BadgePlacement {
            val placed = HashMap<Int, Pair<Int, Int>>(hints.size)
            if (hints.isEmpty() || views.isEmpty()) {
                return BadgePlacement(placed, 0, 0, "")
            }
            if (!HandCoordinateMapper.isUsable(mapping)) {
                return BadgePlacement(placed, 0, 0, "")
            }

            // Bounded hint→view pairing trace so a real device can answer "which hint
            // went missing/ambiguous and why" without guessing. Only the tile keys and
            // their view counts are recorded — ordinary gameplay state, not credentials.
            val viewCounts = views.groupingBy { canonicalTile(it.tile) }.eachCount()
            var ambiguous = 0
            var missing = 0
            val pairingTrace = StringBuilder()
            // Consume each view at most once so two identical recommended tiles never
            // share the same on-screen anchor. views is sorted by x, so the first
            // unused match is the leftmost remaining instance of that tile type.
            val usedViews = HashSet<Int>()

            for (hint in hints) {
                val key = canonicalTile(hint.tile)
                val count = viewCounts[key] ?: 0
                // Ambiguity is only relevant for the tile we actually want to point at.
                if (count > 1) {
                    ambiguous++
                } else {
                    var view: HookedTile? = null
                    var idx = -1
                    for ((i, v) in views.withIndex()) {
                        if (i in usedViews) continue
                        if (canonicalTile(v.tile) == key) { view = v; idx = i; break }
                    }
                    if (view == null) {
                        missing++
                    } else {
                        usedViews.add(idx)
                        val local = HandCoordinateMapper.toLocalPx(view.normX, view.normY, mapping)
                        if (local != null) {
                            placed[hint.handIndex] = local.first to local.second
                        } else {
                            missing++
                        }
                    }
                }
                if (pairingTrace.length < 112) {
                    if (pairingTrace.isNotEmpty()) pairingTrace.append(' ')
                    pairingTrace.append(hint.tile).append('=').append(key).append(':').append(count)
                }
            }
            return BadgePlacement(placed, ambiguous, missing, pairingTrace.toString(),
                estimateCardHeightPx(views, mapping))
        }

        /**
         * Builds the [HandCoordinateMapper.Mapping] from measured geometry. The source
         * viewport comes from the payload; the game viewport is the overlay window's
         * full physical bounds (full-bleed match to Unity's render surface); the frame
         * origin is the overlay content's on-screen location.
         *
         * Pure and testable, and it never touches WindowManager: callers pass the
         * already-read window bounds so the decision itself can be exercised with a
         * not-yet-measured frame, a zero-size source, or missing window metrics.
         */
        internal fun buildMapping(
            sourceW: Int,
            sourceH: Int,
            frameWidth: Int,
            frameHeight: Int,
            frameOriginX: Int,
            frameOriginY: Int,
            viewportLeft: Int,
            viewportTop: Int,
            viewportW: Int,
            viewportH: Int,
        ): MappingResult {
            if (sourceW <= 0 || sourceH <= 0) {
                return MappingResult(null, FrameFailure.SOURCE_MISSING, null)
            }
            // An unattached frame reports 0x0 (and is not laid out), which is normal on
            // the first render pass and must be retried after layout — not treated as
            // a fatal fault.
            if (frameWidth <= 0 || frameHeight <= 0) {
                return MappingResult(null, FrameFailure.FRAME_UNMEASURED, null)
            }
            // A zero-width viewport means WindowManager could not report bounds and the
            // origin+size fallback was also empty; there is no trustworthy surface.
            if (viewportW <= 0 || viewportH <= 0) {
                return MappingResult(null, FrameFailure.METRICS_INVALID, null)
            }
            val mapping = HandCoordinateMapper.Mapping(
                sourceW = sourceW,
                sourceH = sourceH,
                viewportLeft = viewportLeft,
                viewportTop = viewportTop,
                viewportW = viewportW,
                viewportH = viewportH,
                frameOriginX = frameOriginX,
                frameOriginY = frameOriginY,
            )
            if (!HandCoordinateMapper.isUsable(mapping)) {
                return MappingResult(null, FrameFailure.METRICS_INVALID, mapping)
            }
            return MappingResult(mapping, null, mapping)
        }
    }

    internal data class MappingResult(
        val mapping: HandCoordinateMapper.Mapping?,
        val failure: FrameFailure?,
        /** Best-effort geometry even when unusable, so the trace shows the real numbers. */
        val attempted: HandCoordinateMapper.Mapping?,
    )

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

    internal data class DiscardHint(
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

    /** Unity source viewport (Screen.width/height) the coords were normalized against. */
    private var sourceW: Int = 0
    private var sourceH: Int = 0

    /** Changes whenever the rendered order snapshot would differ. */
    private var lastOrderSignature: String = ""

    /**
     * Bumped on every [render] / [detach] so deferred work can detect that a newer
     * render superseded it and bail instead of pinning margins to a dead view.
     */
    private var renderGeneration: Long = 0L

    /** Hints of the newest render, replayed when a layout wait completes. */
    private var pendingHints: List<DiscardHint> = emptyList()

    /** Exactly one layout wait may be armed at a time; [cancelAwait] clears it. */
    private var pendingAwaitingLayout: Boolean = false
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    /** Last reported shape of the incoming coordinate payload (wrapped vs legacy). */
    private var lastSourceNote: String = ""

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
        updateHookedCoords(coords, 0, 0)
    }

    /**
     * Stores a captured coordinate set plus the Unity source viewport it was
     * normalized against. `sourceW`/`sourceH` come from the hand_geometry payload
     * (Unity Screen.width/height); when they are absent the overlay keeps the last
     * established values so a transient geometry record does not wipe the mapping.
     */
    fun updateHookedCoords(coords: JSONArray, sourceW: Int, sourceH: Int) {
        // The wrapper payload `{screenW, screenH, coords}` carries the Unity source
        // viewport. A legacy bare array (no wrapper) arrives with 0/0; keeping the
        // previous values is only useful when they are real. Record the arrival once
        // per change so a stale cached Lua asset (still sending the bare array, thus
        // leaving the mapping without a source) is visible in the diagnostics rather
        // than being misread as a Kotlin measurement fault.
        val incoming = sourceW > 0 && sourceH > 0
        if (incoming) {
            this.sourceW = sourceW
            this.sourceH = sourceH
        }
        val noteKey = "${incoming}:${if (incoming) "${sourceW}x${sourceH}" else "none"}"
        if (noteKey != lastSourceNote) {
            lastSourceNote = noteKey
            GeometryTrace.note(
                "HOOK_SOURCE",
                "reason=${if (incoming) "wrapped" else "legacy_bare_array"};" +
                    "sourceW=${if (incoming) sourceW else this.sourceW};" +
                    "sourceH=${if (incoming) sourceH else this.sourceH};coords=${coords.length()}"
            )
        }
        val filtered = filterCoords(coords)
        val list = filtered.kept
        if (list.isEmpty()) {
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
            return
        }

        if (filtered.rejected > 0 || list.size != hookedTiles.size) {
            GeometryTrace.note(
                "HOOK_COORDS",
                "kept=${list.size};rejected=${filtered.rejected}" +
                    if (filtered.sample.isNotEmpty()) ";sample=${filtered.sample}" else ""
            )
        }

        // The Lua side already holds back non-monotonic captures (a draw/discard
        // animation sliding a tile to a wrong x) and re-sends once the hand settles,
        // so a capture reaching here is normally already uniformly ordered. Keep a
        // defensive monotonicity check as a second line of protection: sort by x so
        // pairing is position-consistent, and reject a still-non-monotonic capture
        // rather than anchoring a badge to a wrong coordinate. The previous stable
        // anchors are kept so an existing badge does not jump or vanish; the next
        // settled capture from Lua will replace them.
        val sorted = sortAndValidate(list)
        if (sorted == null) {
            GeometryTrace.note(
                "COORD_UNSTABLE",
                "kept=${list.size};rejected=${filtered.rejected};hand=${hand.size}"
            )
            renderHints()
            return
        }

        hookedTiles = sorted
        traceOrders()
        renderHints()
    }

    /**
     * Sorts a capture by normalized x, but first rejects a capture whose *raw* x
     * sequence is not strictly monotonic. When the game is at rest, `_handpai`
     * order matches left-to-right *or* right-to-left depending on the hand camera
     * that actually rendered the hand (the orthographic hand camera mirrors the
     * world x), so a settled hand is strictly increasing or strictly decreasing in
     * x. A raw sequence that flips direction is the signature of a draw/discard
     * animation still sliding a tile (one tile reporting a wrong intermediate x).
     * Rejecting that sample — instead of sorting it into place, which cannot fix a
     * wrong coordinate value — is what keeps a badge off the wrong tile. Returns
     * null to signal an unstable capture.
     */
    private fun sortAndValidate(list: List<HookedTile>): List<HookedTile>? {
        if (list.size < 2) return list
        var ascending: Boolean? = null
        for (i in 1 until list.size) {
            val delta = list[i].normX - list[i - 1].normX
            if (delta == 0.0) return null
            val up = delta > 0.0
            if (ascending == null) {
                ascending = up
            } else if (up != ascending) {
                return null
            }
        }
        return list.sortedBy { it.normX }
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
        sourceW = 0
        sourceH = 0
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

            // A recommendation whose tile is absent from the protocol hand cannot be
            // anchored to any on-screen tile: drop it before it reaches placement.
            // The `hand` may be empty only transiently (result without a hand field);
            // in that case there is no index to pair against either, so skip the hint
            // rather than carrying a sentinel handIndex=-1 into placement.
            var targetIndex = -1
            for (hIdx in hand.indices) {
                if (hIdx !in usedIndices && canonicalTile(hand[hIdx]) == normalizedTile) {
                    targetIndex = hIdx
                    usedIndices.add(hIdx)
                    break
                }
            }
            if (targetIndex < 0) continue

            val rawWeight = row.optDouble("weight", Double.NaN).takeIf { it.isFinite() }
                ?: (row.optDouble("policyProbability", 0.0) * 100).takeIf { it.isFinite() } ?: 0.0

            hints.add(DiscardHint(kind, normalizedTile, rawWeight.coerceIn(0.0, 100.0), targetIndex))
        }
        return hints
    }

    /**
     * Pure, testable placement math: pairs each hinted hand index against the
     * on-screen tile views and maps their Unity-normalized coordinates through the
     * supplied [mapping] to overlay-local pixel anchors. It never touches the
     * overlay window, so it can be exercised with a mapping built from a zero-size
     * frame as long as the *measured* mapping itself is valid — which is exactly the
     * "initial frame reports 0 but the mapper is usable after layout" case that the
     * overlay's asynchronous render path must handle.
     *
     * The protocol hand (AI `tehai`) and the game view list (`_handpai`, after the
     * Lua side drops melds and the wildcard) are two independent orderings. They
     * are known to carry the same multiset of tiles, but their relative order is
     * not guaranteed. Only the *hinted* tile type needs to be unique; a duplicated
     * tile that is not being recommended cannot move a badge, so it never
     * suppresses one (with hand `1m 1m 9s`, a hint on `9s` still lands correctly).
     * Without any hook coordinates there is no reliable anchor, so nothing is
     * drawn. The old calibrated-row fallback has been removed rather than kept as
     * a wrong-but-plausible default.
     */
    /**
     * Reads the frame's measured size and on-screen origin and delegates the
     * decision to the pure [buildMapping]. Returns a structured failure instead of a
     * bare null, so a hidden badge always carries its real cause.
     */
    private fun measureMapping(frame: FrameLayout): MappingResult {
        val loc = IntArray(2)
        frame.getLocationOnScreen(loc)
        val frameOriginX = loc[0]
        val frameOriginY = loc[1]

        // Prefer the window's current metrics (physical px, API 30+): the bounds of
        // a MATCH_PARENT + NO_LIMITS + IN_SCREEN overlay equal the full display, i.e.
        // the game's full-bleed viewport. Fall back to the frame itself extended by
        // its origin so a cutout inset is still accounted for on older APIs.
        val bounds = runCatching { windows?.currentWindowMetrics?.bounds }.getOrNull()
        val viewportLeft = bounds?.left ?: 0
        val viewportTop = bounds?.top ?: 0
        val viewportW = bounds?.width() ?: (frameOriginX + frame.width)
        val viewportH = bounds?.height() ?: (frameOriginY + frame.height)

        return buildMapping(
            sourceW = sourceW,
            sourceH = sourceH,
            frameWidth = frame.width,
            frameHeight = frame.height,
            frameOriginX = frameOriginX,
            frameOriginY = frameOriginY,
            viewportLeft = viewportLeft,
            viewportTop = viewportTop,
            viewportW = viewportW,
            viewportH = viewportH,
        )
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
            // No hints: cancel the pending placement attempt (if any) by bumping the
            // generation, then tear the window down. No layout listener survives.
            renderGeneration++
            pendingHints = emptyList()
            detach()
            return
        }
        pendingHints = hints
        val manager = windows ?: return
        val frame = root ?: FrameLayout(context).apply {
            isClickable = false
            isFocusable = false
        }.also { root = it }

        frame.removeAllViews()

        // Attach (or re-lay-out) the overlay root to the window FIRST, so the frame
        // gets a real measured size on the next layout pass. Previously the mapping
        // was measured here, before the root was ever added to the window manager,
        // so `frame.width/height` were 0 and `measureMapping` returned null —
        // producing `BADGE_HIDDEN reason=no_origin` and an immediate detach before
        // the deferred placement callback could ever run.
        //
        // The placement attempt below therefore has two entry points:
        //  * immediately, when the frame already carries a real measured size that
        //    predates this content change (every update after the first layout) —
        //    this keeps badges responsive and never cancels itself, because nothing
        //    remote to this attempt can bump the generation;
        //  * otherwise once the frame is laid out (first attach, rotation, window
        //    re-create), armed via attach-time listeners that run the *same* attempt.
        // At most one layout wait is armed at a time (`pendingAwaitingLayout`), and it
        // always retries with the newest hints, so an attempt can no longer be
        // orphaned by its own render.
        if (root?.parent == null) {
            runCatching { manager.addView(frame, layout) }
        } else {
            runCatching { manager.updateViewLayout(frame, layout) }
        }

        if (!isMeasured(frame)) {
            awaitLayout(frame)
        }
        schedulePlacement(frame, hints)
    }

    /**
     * Runs (or re-arms) the placement attempt for the current generation. Callable
     * from [render] and from a layout listener; it is a no-op for a superseded
     * generation, so a stale listener can never pin margins on a newer render.
     */
    private fun schedulePlacement(frame: FrameLayout, hints: List<DiscardHint>) {
        val gen = renderGeneration
        if (root !== frame) return

        // Retry synchronously when the frame already carries a usable measurement.
        // This is the common steady-state path: no generation bump, no queued callback
        // that its own render could cancel, and no layout listener left behind.
        if (isMeasured(frame)) {
            cancelAwait()
            if (placeBadges(frame, hints, gen)) return
        }
        // Nothing measured yet (first attach / rotation / window re-create) or the
        // mapping is transiently incomplete: make sure a layout wait is armed so the
        // attempt is retried as soon as the window pass has laid the frame out.
        if (!pendingAwaitingLayout) awaitLayout(frame)
        else pendingHints = hints
    }

    private fun isMeasured(frame: FrameLayout) = frame.width > 0 && frame.height > 0

    /**
     * Arms a one-shot layout wait for [frame]. OnPreDraw runs after the window pass
     * has measured/laid out the view but before it is drawn, so `width`/`height`
     * are already final there; OnGlobalLayout covers a container that reports a
     * later size change. Both are removed by [cancelAwait].
     */
    private fun awaitLayout(frame: FrameLayout) {
        if (pendingAwaitingLayout) return
        pendingAwaitingLayout = true
        val gen = renderGeneration

        preDrawListener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (root !== frame || gen != renderGeneration) {
                    cancelAwait()
                    return true
                }
                if (!isMeasured(frame)) return true
                cancelAwait()
                placeBadges(frame, pendingHints, gen)
                return true
            }
        }
        globalLayoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (root !== frame || gen != renderGeneration || !isMeasured(frame)) return
                cancelAwait()
                placeBadges(frame, pendingHints, gen)
            }
        }
        val observer = frame.viewTreeObserver
        observer.addOnPreDrawListener(preDrawListener)
        observer.addOnGlobalLayoutListener(globalLayoutListener)
    }

    /** Removes both layout listeners exactly once and clears the pending flag. */
    private fun cancelAwait() {
        val observer = root?.viewTreeObserver?.takeIf { it.isAlive }
        preDrawListener?.let { l ->
            if (observer != null) runCatching { observer.removeOnPreDrawListener(l) }
        }
        globalLayoutListener?.let { l ->
            if (observer != null) {
                @Suppress("DEPRECATION")
                runCatching { observer.removeOnGlobalLayoutListener(l) }
            }
        }
        preDrawListener = null
        globalLayoutListener = null
        pendingAwaitingLayout = false
    }

    /**
     * The single placement attempt. Returns true when it drew badges, false when the
     * geometry is still unavailable (the caller then keeps waiting for layout).
     *
     * Every failure is reported with its own reason and the actual numbers:
     * `source_missing` (dims never arrived from Lua), `frame_unmeasured` (0x0 frame),
     * `metrics_invalid` (WindowManager bounds + fallback both unusable). The overlay
     * text panel stays up in all cases; nothing detaches here.
     */
    private fun placeBadges(frame: FrameLayout, hints: List<DiscardHint>, gen: Long): Boolean {
        if (root !== frame || gen != renderGeneration) return true
        val result = measureMapping(frame)
        val mapping = result.mapping
        if (mapping == null) {
            val attempted = result.attempted
            GeometryTrace.note(
                "BADGE_HIDDEN",
                "reason=${result.failure?.reason ?: "unmapped"};hints=${hints.size};" +
                    "views=${hookedTiles.size};sourceW=$sourceW;sourceH=$sourceH;" +
                    "frame=${frame.width}x${frame.height};" +
                    "frameOrigin=${attempted?.frameOriginX ?: -1},${attempted?.frameOriginY ?: -1};" +
                    "viewport=${attempted?.viewportW ?: 0}x${attempted?.viewportH ?: 0}" +
                    "@${attempted?.viewportLeft ?: 0},${attempted?.viewportTop ?: 0}"
            )
            // Still unmeasured: keep waiting for the layout pass. Anything else is a
            // genuine mapping fault that a layout pass cannot fix.
            return result.failure != FrameFailure.FRAME_UNMEASURED
        }

        val frameWidth = frame.width.takeIf { it > 0 } ?: display.widthPixels
        val frameHeight = frame.height.takeIf { it > 0 } ?: display.heightPixels

        val placement = if (hookedTiles.isEmpty()) {
            BadgePlacement(emptyMap(), 0, 0, "")
        } else {
            computeBadgePlacement(hints, hookedTiles, mapping)
        }

        if (placement.ambiguous > 0 || placement.missing > 0) {
            GeometryTrace.note(
                "BADGE_PARTIAL",
                "ambiguous=${placement.ambiguous};missing=${placement.missing};" +
                    "placed=${placement.placed.size};hand=${hand.size};" +
                    "views=${hookedTiles.size};overlayW=$frameWidth;overlayH=$frameHeight;" +
                    "sourceW=${mapping.sourceW};sourceH=${mapping.sourceH};" +
                    "frameOrigin=${mapping.frameOriginX},${mapping.frameOriginY}" +
                    if (placement.pairingTrace.isNotEmpty()) ";pairs=${placement.pairingTrace}" else ""
            )
        }

        val cardHeight = placement.cardHeightPx
        val tileTopOffset = tileTopOffsetPx(frameHeight)
        val gap = dp(BADGE_GAP_DP)
        val edgeInset = pxFromReference(EDGE_INSET_REF, frameHeight)
        val minBadgeHeight = pxFromReference(MIN_BADGE_HEIGHT_REF, frameHeight)

        val badgeTargets = ArrayList<Triple<LinearLayout, Pair<Int, Int>, DiscardHint>>()
        var skippedHeight = 0
        // Measure each badge synchronously with real constraints *before* adding it to
        // the frame: a freshly attached view still reports width = height = 0, and the
        // vertical placement needs the real badge height to put its bottom edge on the
        // tile. Measuring first is what lets the top be computed in the same pass,
        // instead of a `post`-style second pass that a newer render could cancel.
        val widthSpec = View.MeasureSpec.makeMeasureSpec(frameWidth, View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(frameHeight, View.MeasureSpec.AT_MOST)
        // One card-sized badge per hint: the size is derived once for the render (the
        // hand is one row of equal cards) and every badge is drawn at that size instead
        // of shrinking to its text. Null only for a degenerate frame.
        val badgeSize = deriveBadgeSizePx(hookedTiles, mapping, frameWidth, frameHeight)
        var textOverflow = 0
        for ((rank, hint) in hints.withIndex()) {
            val target = placement.placed[hint.handIndex] ?: continue
            val badgeView = createBadgeCard(hint, rank)
            if (badgeSize != null) {
                val cardW = badgeSize.width
                val cardH = badgeSize.height
                // Text style is fitted to the card box; the view is measured against
                // the card size so wrapping/clipping can be detected, then forced to
                // exactly that size.
                badgeView.minimumWidth = cardW
                badgeView.minimumHeight = cardH
                badgeView.measure(
                    View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(cardH, View.MeasureSpec.EXACTLY),
                )
                if (badgeView.measuredWidth > cardW || badgeView.measuredHeight > cardH) {
                    textOverflow++
                }
            } else {
                badgeView.measure(widthSpec, heightSpec)
            }
            if (badgeView.measuredHeight <= 0) {
                skippedHeight++
                continue
            }
            val lp = if (badgeSize != null) {
                FrameLayout.LayoutParams(badgeSize.width, badgeSize.height)
            } else {
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }
            frame.addView(badgeView, lp)
            badgeTargets.add(Triple(badgeView, target, hint))
        }

        if (textOverflow > 0) {
            GeometryTrace.note(
                "BADGE_SIZE",
                "reason=text_overflow;count=$textOverflow;cardW=${badgeSize?.width ?: 0};" +
                    "cardH=${badgeSize?.height ?: 0}",
            )
        }

        if (skippedHeight > 0) {
            GeometryTrace.note(
                "BADGE_SKIPPED",
                "reason=measured_zero;skipped=$skippedHeight;placed=${placement.placed.size}"
            )
        }

        if (badgeTargets.isEmpty() && skippedHeight == 0) {
            // No trustworthy tile anchor: show the floating text panel only rather
            // than drawing a badge over the wrong tile.
            GeometryTrace.note(
                "BADGE_HIDDEN",
                "reason=no_anchor;hints=${hints.size};views=${hookedTiles.size}" +
                    ";placed=${placement.placed.size};ambiguous=${placement.ambiguous};" +
                    "missing=${placement.missing};sourceW=${mapping.sourceW};sourceH=${mapping.sourceH}"
            )
            return true
        }

        // One bounded record per render with the *final* pixel target of every
        // badge plus the hand index and frame bounds it was computed against.
        val placementTrace = StringBuilder()
        // Vertical calibration actually used, so a device log can be checked against
        // the screenshot without re-deriving it (cardH / tileTop offset / gap).
        var usedOffset = 0
        for ((badgeView, target, _) in badgeTargets) {
            if (badgeView.parent !== frame) continue
            val (centerX, anchorY) = target
            val badgeWidth = badgeView.measuredWidth.takeIf { it > 0 } ?: badgeView.width
            val badgeHeight = badgeView.measuredHeight.takeIf { it > 0 } ?: badgeView.height
            // Bottom edge sits on the tile's top edge (`tileTop - gap`), never on the
            // tile face: the badge's own height is subtracted here, not baked into a
            // fixed lift. Falls back to the calibrated card height when this render had
            // no coordinate sample strong enough to estimate one.
            val probe = computeBadgeTop(
                anchorY = anchorY,
                tileTopOffsetPx = tileTopOffset,
                badgeHeightPx = badgeHeight,
                gapPx = gap,
                frameHeight = frameHeight,
            )
            if (probe == null) {
                badgeView.visibility = View.GONE
                skippedHeight++
                continue
            }
            usedOffset = tileTopOffset
            if (probe.height < badgeHeight && probe.height < minBadgeHeight) {
                // Not enough room above the tile for a legible badge: skip rather than
                // cover the tile it is supposed to point at.
                badgeView.visibility = View.GONE
                skippedHeight++
                continue
            }

            val desiredLeft = (centerX - badgeWidth / 2)
                .coerceIn(edgeInset, (frameWidth - badgeWidth - edgeInset).coerceAtLeast(0))

            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            params.leftMargin = desiredLeft
            params.topMargin = probe.top
            params.height = probe.height
            badgeView.layoutParams = params
        }
        for ((badgeView, target, hint) in badgeTargets) {
            if (badgeView.parent !== frame) continue
            if (badgeView.visibility != View.VISIBLE) continue
            val lp = badgeView.layoutParams as FrameLayout.LayoutParams
            if (placementTrace.length < 200) {
                if (placementTrace.isNotEmpty()) placementTrace.append(' ')
                placementTrace.append(hint.tile)
                    .append('@').append(lp.leftMargin + (badgeView.measuredWidth / 2))
                    .append(',').append(lp.topMargin)
                    .append(':').append(hint.handIndex)
            }
        }
        if (placementTrace.isNotEmpty()) {
            GeometryTrace.note(
                "BADGE_PLACED",
                "placed=${badgeTargets.count { it.first.visibility == View.VISIBLE }};" +
                    "frame=${frame.width}x${frame.height};" +
                    "hand=${hand.size};views=${hookedTiles.size};targets=${placementTrace};" +
                    "sourceW=${mapping.sourceW};sourceH=${mapping.sourceH};" +
                    "frameOrigin=${mapping.frameOriginX},${mapping.frameOriginY};" +
                    "viewport=${mapping.viewportW}x${mapping.viewportH}" +
                    "@${mapping.viewportLeft},${mapping.viewportTop};" +
                    "badge=${badgeSize?.width ?: 0}x${badgeSize?.height ?: 0}(${badgeSize?.source ?: "text"})"
            )
            // Vertical calibration, so a device log can be checked against the
            // screenshot without re-deriving it: anchorY is the raw projected tile y,
            // off the frame-normalized anchor->tile-top distance actually applied,
            // cardH the card height estimated from this coordinate sample (reported,
            // not used for the offset), badgeH the measured badge height. The badge
            // top must equal anchor - off - badgeH - gap.
            val sampleTop = (badgeTargets.firstOrNull { it.first.visibility == View.VISIBLE }
                ?.first?.layoutParams as? FrameLayout.LayoutParams)?.topMargin ?: -1
            val sampleBadgeH = badgeTargets.firstOrNull { it.first.visibility == View.VISIBLE }
                ?.first?.measuredHeight ?: 0
            val sampleBadgeW = badgeTargets.firstOrNull { it.first.visibility == View.VISIBLE }
                ?.first?.measuredWidth ?: 0
            GeometryTrace.note(
                "BADGE_VERTICAL",
                "anchor=${placement.anchorY() ?: -1};cardH=${cardHeight ?: 0};off=$usedOffset;" +
                    "badgeH=$sampleBadgeH;badgeW=$sampleBadgeW;gap=$gap;top=$sampleTop;" +
                    "size=${badgeSize?.source ?: "text"};" +
                    "skipped=$skippedHeight;frameH=$frameHeight"
            )
        }
        return true
    }

    /**
     * One hint badge, drawn as a **card-sized** box (see [deriveBadgeSizePx]) rather
     * than a shrink-wrapped text bubble.
     *
     * The card box comes from the layout pass; this view only fits its two lines of
     * text into it, so the badge can never grow past the card it hints:
     *  - the two lines are weighted equally and the frame clips the overflow when the
     *    derived card is short, so a long probability never spills out of the badge;
     *  - text sizes scale with the card height (a 152 px card at 1080p gets ~14 sp),
     *    so the hint stays legible on other resolutions;
     *  - long tile names (`立·赤5筒`) are single-line truncated; the card is ~95 px
     *    wide, so nothing wraps onto a second unexpected line.
     */
    private fun createBadgeCard(hint: DiscardHint, rank: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = false
        isFocusable = false
        clipChildren = true
        clipToPadding = true
        setPadding(dp(2), dp(1), dp(2), dp(1))

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
        val weightText =
            if (hint.weight > 0.0) String.format(Locale.ROOT, "%.1f%%", hint.weight) else "—"

        addView(TextView(context).apply {
            text = actionText
            textSize = BADGE_ACTION_TEXT_SP
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })

        addView(TextView(context).apply {
            text = weightText
            textSize = BADGE_WEIGHT_TEXT_SP
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
            isSingleLine = true
            maxLines = 1
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })
    }

    private fun tileDisplayName(tile: String): String {
        // `tile` arrives already canonicalised: honor letters become `1z..7z`.
        val honors = mapOf(
            "1z" to "东", "2z" to "南", "3z" to "西", "4z" to "北",
            "5z" to "白", "6z" to "发", "7z" to "中",
            "E" to "东", "S" to "南", "W" to "西", "N" to "北", "P" to "白", "F" to "发", "C" to "中",
        )
        honors[tile]?.let { return it }
        if (tile.length < 2) return tile
        val suit = when (tile[1]) { 'm' -> "万"; 'p' -> "筒"; 's' -> "索"; else -> "" }
        return (if (tile.endsWith("r")) "赤" else "") + tile[0] + suit
    }

    private fun dp(value: Int) = (value * display.density).roundToInt()

    /**
     * Scales a value calibrated on the 1080p reference frame to this overlay's height,
     * so paddings and minimum sizes keep their visual proportion on other resolutions.
     */
    private fun pxFromReference(referencePx: Float, frameHeight: Int): Int =
        (referencePx * (frameHeight.toFloat() / REFERENCE_FRAME_HEIGHT)).roundToInt()

    private fun detach() {
        val frame = root ?: run {
            cancelAwait()
            return
        }
        root = null
        renderGeneration++
        pendingHints = emptyList()
        // Remove the layout listeners *before* the view leaves the window, so no
        // observer callback can fire against a detached frame and nothing leaks.
        cancelAwait()
        runCatching { windows?.removeView(frame) }
    }
}
