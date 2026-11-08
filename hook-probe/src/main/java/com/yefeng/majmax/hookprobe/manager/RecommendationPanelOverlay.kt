package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Fixed read-only HUD. The game host and system window both pass every touch through. */
internal class RecommendationPanelOverlay(private val context: Context, private val host: ViewGroup? = null) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var signature = ""

    fun update(result: JSONObject?, enabled: Boolean) {
        val active = result?.optString("status") in listOf("live", "demo")
        if (!enabled || !active) { close(); return }
        val value = result ?: return
        val data = value.optJSONObject("recommendationPanel") ?: dataFor(value)
        val next = data.toString()
        if (next == signature && root != null) return
        signature = next
        val frame = root ?: object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: android.view.MotionEvent) = false
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                post { positionPanel(this) }
            }
        }.apply { isClickable = false; isFocusable = false }.also {
            root = it
            if (host != null) host.addView(it, FrameLayout.LayoutParams(-1, -1))
            else windows?.addView(it, WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START })
        }
        frame.removeAllViews()
        val density = context.resources.displayMetrics.density
        val scale = ((frame.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels) / 1080f)
        val font = (26f * scale / context.resources.displayMetrics.scaledDensity).coerceAtLeast(10f)
        val pane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * scale).toInt(), (9 * scale).toInt(), (10 * scale).toInt(), (9 * scale).toInt())
            background = GradientDrawable().apply { setColor(0xDE20212B.toInt()); cornerRadius = 8 * density; setStroke(1, 0x887A8392.toInt()) }
            isClickable = false; isFocusable = false
        }
        fun line(text: String, gold: Boolean = false, small: Boolean = false) {
            pane.addView(TextView(context).apply {
                this.text = text
                textSize = if (small) font * 0.77f else font
                setTextColor(if (gold) 0xFFFFC14D.toInt() else 0xFF78C8FF.toInt())
                typeface = Typeface.create(Typeface.DEFAULT, if (gold) Typeface.BOLD else Typeface.NORMAL)
                setPadding(0, (3 * scale).toInt(), 0, (3 * scale).toInt())
                isClickable = false; isFocusable = false
            })
        }
        line("推荐切牌 · 决策概率", true)
        val rows = data.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val probability = row.optDouble("probabilityPercent", Double.NaN)
            line("切${tileName(row.optString("tile"))}    ${if (probability.isFinite()) String.format(Locale.ROOT, "%.1f%%", probability) else "—"}", i == 0)
        }
        val scores = data.optJSONArray("scores") ?: JSONArray()
        if (rows.length() == 0 && scores.length() == 0) line("等待出牌机会", small = true)
        if (data.optInt("shanten", -99) == 0 && scores.length() > 0) {
            pane.removeViews(1, rows.length())
        }
        if (scores.length() > 0) {
            line(data.optString("scoreLabel", "和牌番点"), true, true)
            for (i in 0 until scores.length()) {
                val score = scores.getJSONObject(i)
                line("和${tileName(score.optString("tile"))}", true, true)
                fun scoreText(key: String): String {
                    val v = score.optJSONObject(key) ?: return "无役"
                    return "${if (v.optBoolean("yakuman")) "役满" else "${v.optInt("han")}番${v.optInt("fu")}符"} ${v.optInt("points")}点"
                }
                line("荣和 ${scoreText("ron")} / 自摸 ${scoreText("tsumo")}", small = true)
                if (score.optJSONObject("riichi_ron") != null || score.optJSONObject("riichi_tsumo") != null) {
                    line("立直 荣${scoreText("riichi_ron")} / 摸${scoreText("riichi_tsumo")}", small = true)
                }
            }
            line("基础估算；不含里宝/一发/本场/供托/和牌赤5", small = true)
            if (data.optInt("players") == 3) line("拔北 ×${data.optInt("kitaCount", 0)}：已计入三麻番数", small = true)
        } else {
            val shanten = data.optInt("shanten", -99)
            line(if (shanten > 0) "${shanten}向听 · 听牌后显示番点" else if (shanten == 0) "已听牌 · 暂无可用番点" else "等待手牌分析", small = true)
        }
        frame.addView(pane, FrameLayout.LayoutParams(1, -2))
        frame.post { if (root === frame) positionPanel(frame) }
    }

    private fun positionPanel(frame: FrameLayout) {
        val pane = frame.getChildAt(0) as? LinearLayout ?: return
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return
        // Fit the title, with only a small trailing inset; keep the left edge fixed.
        val title = pane.getChildAt(0) as? TextView
        val titleWidth = title?.paint?.measureText(title.text.toString()) ?: (w * .12f)
        val width = (titleWidth + pane.paddingLeft + pane.paddingRight + h * .008f)
            .toInt().coerceIn((w * .10f).toInt().coerceAtLeast(1), (w * .22f).toInt().coerceAtLeast(1))
        val widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY)
        val free = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        pane.measure(widthSpec, free)
        // Screenshot calibration: own avatar centre x=.287, top y=.659.
        // Anchor the panel BOTTOM above it, independent of the number of rows.
        val bottom = (h * .645f).toInt()
        val available = h * .62f
        if (pane.measuredHeight > available) {
            val ratio = available / pane.measuredHeight
            for (i in 0 until pane.childCount) (pane.getChildAt(i) as? TextView)?.let {
                it.textSize = it.textSize / context.resources.displayMetrics.scaledDensity * ratio
                it.setPadding(0, 0, 0, 0)
            }
            pane.measure(widthSpec, free)
        }
        pane.layoutParams = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (w * (.287f - .22f / 2f)).toInt()
            topMargin = (bottom - pane.measuredHeight).coerceAtLeast(0)
        }
    }

    fun close() {
        root?.let { if (host != null) (it.parent as? ViewGroup)?.removeView(it) else runCatching { windows?.removeView(it) } }
        root = null; signature = ""
    }

    companion object {
        fun dataFor(result: JSONObject): JSONObject {
            val recommendations = result.optJSONArray("recommendations") ?: JSONArray()
            val analysis = result.optJSONObject("analysis")
            val hand13 = analysis?.optJSONObject("hand13")
            val hand14 = analysis?.optJSONObject("hand14")
            val shanten = hand13?.optInt("shanten") ?: hand14?.optInt("shanten") ?: -99
            val rows = JSONArray()
            if (shanten != 0) {
                val seen = mutableSetOf<String>()
                for (i in 0 until recommendations.length()) {
                    if (rows.length() >= 3) break
                    val row = recommendations.optJSONObject(i) ?: continue
                    if (row.optString("kind") !in listOf("discard", "riichi")) continue
                    if (seen.add(row.optString("tile"))) {
                        val normalized = JSONObject(row.toString())
                        probabilityPercent(row)?.let { normalized.put("probabilityPercent", it) }
                        rows.put(normalized)
                    }
                }
            }
            var hand = if (shanten == 0) hand13 else null
            var label = "当前听牌番点"
            return JSONObject().put("rows", rows)
                .put("scores", if (shanten == 0) hand?.optJSONArray("win_scores") ?: JSONArray() else JSONArray())
                .put("shanten", shanten)
                .put("kitaCount", result.optInt("kitaCount", 0))
                .put("scoreLabel", label + if (hand?.optBoolean("is_furiten") == true) "（振听不可荣和）" else "")
                .put("players", result.optInt("players", 4))
        }
        internal fun probabilityPercent(row: JSONObject): Double? {
            val weight = row.optDouble("weight", Double.NaN)
            if (weight.isFinite()) return weight.coerceIn(0.0, 100.0)
            val policy = row.optDouble("policyProbability", Double.NaN)
            return policy.takeIf { it.isFinite() }?.times(100.0)?.coerceIn(0.0, 100.0)
        }
        private fun tileName(tile: String): String {
            val honors = mapOf("E" to "东", "S" to "南", "W" to "西", "N" to "北", "P" to "白", "F" to "发", "C" to "中")
            honors[tile]?.let { return it }
            if (tile.length >= 2) return "${if (tile[0] == '0') "赤5" else tile[0]}${when(tile[1]) { 'm' -> "万"; 'p' -> "筒"; 's' -> "索"; else -> "" }}"
            return tile
        }
    }
}
