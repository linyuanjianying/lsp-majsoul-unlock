package com.yefeng.majmax.hookprobe.manager

import kotlin.math.roundToInt

/**
 * Maps Unity-normalized hand coordinates to overlay-local pixel positions.
 *
 * The game reports each tile as `(nx, ny)` normalized against Unity's
 * `Screen.width` / `Screen.height` — the *source* viewport. The overlay is a
 * separate MATCH_PARENT window whose content frame can be smaller than the full
 * display (display cutout insets) and whose on-screen origin is not necessarily
 * (0, 0). Multiplying `nx` by the overlay's own width was wrong on two counts:
 *
 *  1. it used the overlay's compressed content width instead of the source width
 *     (e.g. 2256 vs 2376, a ~5% / 60–120 px error), and
 *  2. it never subtracted the overlay's on-screen origin, so the whole row drifted
 *     by the cutout inset and the error changed per frame/orientation.
 *
 * The correct transform per axis is:
 *
 *     xGlobal = nx * sourceW * scaleX + viewportLeft   (source -> display)
 *     xLocal  = xGlobal - frameOriginX                 (display -> overlay local)
 *
 * where `scaleX = viewportW / sourceW` (1.0 for a full-bleed render) and
 * `viewportLeft` is the game viewport's left edge on the display (0 for full-bleed,
 * > 0 when letterboxed). `frameOriginX` is the overlay root's on-screen x, measured
 * via `getLocationOnScreen`, never assumed to be zero.
 *
 * Coordinates are deliberately *not* clamped to [0, 1]: they are viewport-global,
 * so a value outside the source bounds is a genuine off-screen fault and must be
 * surfaced rather than silently clamped back into the hand. The only rejection is
 * a mapping whose dimensions cannot be established (returns null -> no badge).
 */
internal object HandCoordinateMapper {

    /** Everything needed to transform one coordinate, all in physical pixels. */
    data class Mapping(
        val sourceW: Int,
        val sourceH: Int,
        val viewportLeft: Int,
        val viewportTop: Int,
        val viewportW: Int,
        val viewportH: Int,
        val frameOriginX: Int,
        val frameOriginY: Int,
    )

    /** True when the mapping is complete enough to trust; false means "no badge". */
    fun isUsable(m: Mapping): Boolean =
        m.sourceW > 0 && m.sourceH > 0 && m.viewportW > 0 && m.viewportH > 0

    /**
     * Maps Unity-normalized `(nx, ny)` (ny already top-down) to overlay-local
     * pixels, or null when the source/viewport dimensions make the transform
     * invalid. Returns unrounded doubles so callers keep full precision; the
     * caller rounds once.
     */
    fun toLocal(nx: Double, ny: Double, m: Mapping): Pair<Double, Double>? {
        if (!isUsable(m)) return null
        val scaleX = m.viewportW.toDouble() / m.sourceW
        val scaleY = m.viewportH.toDouble() / m.sourceH
        val xGlobal = nx * m.sourceW * scaleX + m.viewportLeft
        val yGlobal = ny * m.sourceH * scaleY + m.viewportTop
        return (xGlobal - m.frameOriginX) to (yGlobal - m.frameOriginY)
    }

    /** Convenience: rounds a mapped local coordinate pair to integer pixels. */
    fun toLocalPx(nx: Double, ny: Double, m: Mapping): Pair<Int, Int>? {
        val local = toLocal(nx, ny, m) ?: return null
        return local.first.roundToInt() to local.second.roundToInt()
    }
}
