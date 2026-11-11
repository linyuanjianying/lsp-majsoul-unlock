package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.yefeng.majmax.hookprobe.helper.GameMenuText
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Fixed read-only HUD. The game host and system window both pass every touch through.
 *
 * Text follows the manager's UI language and only the *rendered* strings are translated.
 * Where that language comes from depends on which of the two surfaces this is:
 *
 *  - **In the game** ([host] != null) the host stamps a tag from the hint snapshot
 *    ([setLanguageTag]). That tag is authoritative for this run: the process is the game
 *    process, whose stored preference is not the manager's live one, so it is never
 *    overridden by a local preference read.
 *  - **As a system overlay** ([host] == null) this lives in the manager process, which the
 *    service never feeds a tag to. The stored choice *is* the authority there, so
 *    [update] re-reads it on every repaint and a switch made on the manager page reaches an
 *    already-open window without a rebuild or a second timer.
 *
 * The machine values the panel is built from ([dataFor], [displayLines]) are unchanged, and
 * so are the position, width and font sizes; a longer translation wraps inside the same box
 * instead of resizing it.
 */
internal class RecommendationPanelOverlay(private val context: Context, private val host: ViewGroup? = null) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var signature = ""
    private var lastResult: JSONObject? = null
    private var renderedWidth = 0
    private var renderedHeight = 0

    /**
     * Language the text is currently drawn in. Seeded once from the manager preference, then
     * moved by an arriving tag (game) or by the re-read preference (overlay). An empty tag is
     * "no news", not "back to Chinese".
     */
    private var language: ManagerLanguage = ManagerLanguage.SIMPLIFIED
    private var languageSeeded = false

    /**
     * True once a tag arrived from the host. From then on this panel is fed by the game and
     * must not re-derive the language locally: the game process' preferences are not the
     * manager's live selection, so a local read could silently undo the received tag.
     */
    private var hostTagSeen = false

    /**
     * Called with the `uiLanguage` tag of the latest reply / hint snapshot.
     *
     * `host = true` marks the tag as coming from the game process (the hint snapshot),
     * which then owns the copy for the rest of the run. The default is the service telling
     * this panel about the manager's language, which must never lock out the host.
     */
    @JvmOverloads
    fun setLanguageTag(tag: String?, host: Boolean = false) {
        val current = if (languageSeeded) language else seedLanguage()
        val next = GameMenuText.languageOf(tag, current)
        if (host && !tag.isNullOrBlank()) hostTagSeen = true
        if (next == language && languageSeeded) return
        language = next
        languageSeeded = true
        val shown = lastResult ?: return
        // Only redraw an already visible panel; a hidden one is redrawn by its next update.
        if (root?.visibility == View.VISIBLE) update(shown, true)
    }

    /**
     * Re-reads the manager's stored language for the *system overlay* case.
     *
     * Does nothing for the in-game panel (a host exists, or a host tag already arrived):
     * there the tag from the game owns the copy. A changed language repaints the panel that
     * is already on screen through the normal [update] path, so the repaint also happens
     * when the recommendation itself is unchanged.
     */
    private fun refreshOverlayLanguage() {
        if (host != null || hostTagSeen) return
        val next = runCatching {
            val choice: ManagerLanguageChoice = ManagerLanguageSettings.readChoice(context)
            choice.effective(ManagerSystemLanguage.current())
        }.getOrNull() ?: return
        if (next == language && languageSeeded) return
        language = next
        languageSeeded = true
        val shown = lastResult ?: return
        // A window removed by a rotation/resize has no parent: the next activity update
        // repaints it anyway, so there is nothing to redraw here.
        if (root?.visibility == View.VISIBLE && root?.parent != null) update(shown, true)
    }

    private fun seedLanguage(): ManagerLanguage = runCatching {
        // The stored choice, with "follow the system" resolved here: this overlay is
        // created in the manager process but the hint snapshot corrects it on the very
        // first tick, so the seed only prevents a Chinese flash.
        val choice: ManagerLanguageChoice = ManagerLanguageSettings.readChoice(context)
        choice.effective(ManagerSystemLanguage.current())
    }.getOrDefault(ManagerLanguage.SIMPLIFIED)
        .also { language = it; languageSeeded = true }

    private fun ensureLanguage() {
        if (!languageSeeded) seedLanguage()
    }

    fun update(result: JSONObject?, enabled: Boolean) {
        // The system overlay is never handed a tag, so it follows the stored preference
        // here; the in-game panel keeps whatever the host told it.
        refreshOverlayLanguage()
        ensureLanguage()
        val active = result?.optString("status") in listOf("live", "demo")
        if (!enabled || !active) { root?.visibility = View.INVISIBLE; return }
        val value = result ?: return
        lastResult = value
        val data = value.optJSONObject("recommendationPanel") ?: dataFor(value)
        val lines = displayLines(data, language)
        val targetWidth = root?.width?.takeIf { it > 0 } ?: host?.width?.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val targetHeight = root?.height?.takeIf { it > 0 } ?: host?.height?.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        // The language is part of the signature: a switch re-renders even when the
        // recommendation itself is byte-identical.
        val next = "${targetWidth}x${targetHeight}:${language.name}:$lines"
        root?.visibility = View.VISIBLE
        if (next == signature && root != null) return
        signature = next
        renderedWidth = targetWidth
        renderedHeight = targetHeight
        val existing = root
        val frame = existing ?: object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: android.view.MotionEvent) = false
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                // The screen origin is reliable only once the hook frame is laid out.
                if (host != null) positionPanel(this)
                super.onLayout(changed, left, top, right, bottom)
            }
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (root === this && visibility == View.VISIBLE && (w != renderedWidth || h != renderedHeight)) {
                    signature = ""
                    update(lastResult, true)
                }
            }
        }.apply { isClickable = false; isFocusable = false }
        root = frame
        val density = context.resources.displayMetrics.density
        val scale = targetHeight / 1080f
        val font = (26f * scale / context.resources.displayMetrics.scaledDensity).coerceAtLeast(10f)
        val pane = (frame.getChildAt(0) as? LinearLayout) ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * scale).toInt(), (9 * scale).toInt(), (10 * scale).toInt(), (9 * scale).toInt())
            background = GradientDrawable().apply { setColor(0xDE20212B.toInt()); cornerRadius = 8 * density; setStroke(1, 0x887A8392.toInt()) }
            isClickable = false; isFocusable = false
        }
        pane.setPadding((10 * scale).toInt(), (9 * scale).toInt(), (10 * scale).toInt(), (9 * scale).toInt())
        while (pane.childCount > lines.size) pane.removeViewAt(pane.childCount - 1)
        lines.forEachIndexed { index, line ->
            val view = (pane.getChildAt(index) as? TextView) ?: TextView(context).apply {
                isClickable = false; isFocusable = false
                pane.addView(this)
            }
            if (view.text.toString() != line.text) view.text = line.text
            val size = if (line.small) font * .77f else font
            if (view.textSize != size * context.resources.displayMetrics.scaledDensity) view.textSize = size
            // A translated line is longer than its Chinese source; it wraps inside the
            // same measured box instead of widening the panel or shrinking the font.
            if (view.maxLines != WRAP_LINES) view.maxLines = WRAP_LINES
            if (view.ellipsize != null) view.ellipsize = null
            view.setTextColor(if (line.gold) 0xFFFFC14D.toInt() else 0xFF78C8FF.toInt())
            view.typeface = Typeface.create(Typeface.DEFAULT, if (line.gold) Typeface.BOLD else Typeface.NORMAL)
            view.setPadding(0, (3 * scale).toInt(), 0, (3 * scale).toInt())
        }
        if (pane.parent == null) frame.addView(pane, FrameLayout.LayoutParams(-2, -2))
        positionPanel(frame)
        // Attach a fully populated panel; subsequent updates reuse this same window.
        if (existing == null) {
            if (host != null) host.addView(frame, FrameLayout.LayoutParams(-1, -1))
            else windows?.addView(frame, WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START })
        }
    }

    private fun positionPanel(frame: FrameLayout) {
        val pane = frame.getChildAt(0) as? LinearLayout ?: return
        val w = frame.width.takeIf { it > 0 } ?: host?.width?.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val h = frame.height.takeIf { it > 0 } ?: host?.height?.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        if (w <= 0 || h <= 0) return
        // Fit the title, with only a small trailing inset; keep the left edge fixed.
        val title = pane.getChildAt(0) as? TextView
        val titleWidth = title?.paint?.measureText(title.text.toString()) ?: (w * .12f)
        val width = (titleWidth + pane.paddingLeft + pane.paddingRight + h * .008f)
            .toInt().coerceIn((w * .10f).toInt().coerceAtLeast(1), (w * .22f).toInt().coerceAtLeast(1))
        val widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY)
        val free = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        pane.measure(widthSpec, free)
        // Hook host coordinates can start after a display inset. Calibrate against
        // the full game window, then translate into this frame's local coordinates.
        // Reference (2376x1080): panel left=519, bottom=696, above our avatar.
        val origin = IntArray(2)
        val gameBounds = if (host != null && android.os.Build.VERSION.SDK_INT >= 30)
            windows?.currentWindowMetrics?.bounds else null
        val gameWidth = gameBounds?.width() ?: context.resources.displayMetrics.widthPixels
        val gameHeight = gameBounds?.height() ?: context.resources.displayMetrics.heightPixels
        if (host != null) frame.getLocationOnScreen(origin)
        val bottom = if (host != null)
            (gameHeight * .645f).toInt() + (gameBounds?.top ?: 0) - origin[1]
        else (h * .645f).toInt()
        val available = h * .62f
        if (pane.measuredHeight > available) {
            val ratio = available / pane.measuredHeight
            for (i in 0 until pane.childCount) (pane.getChildAt(i) as? TextView)?.let {
                it.textSize = it.textSize / context.resources.displayMetrics.scaledDensity * ratio
                it.setPadding(0, 0, 0, 0)
            }
            pane.measure(widthSpec, free)
        }
        val left = if (host != null)
            ((gameWidth * (519f / 2376f)).toInt() + (gameBounds?.left ?: 0) - origin[0])
                .coerceIn(0, (w - width).coerceAtLeast(0))
        else (w * (.287f - .22f / 2f)).toInt()
        val top = (bottom - pane.measuredHeight).coerceAtLeast(0)
        val current = pane.layoutParams as? FrameLayout.LayoutParams
        if (current?.width != width || current.leftMargin != left || current.topMargin != top) {
            pane.layoutParams = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = left; topMargin = top
            }
        }
    }

    fun close() {
        root?.let { if (host != null) (it.parent as? ViewGroup)?.removeView(it) else runCatching { windows?.removeView(it) } }
        root = null; signature = ""; lastResult = null
    }

    companion object {
        internal data class DisplayLine(val text: String, val gold: Boolean, val small: Boolean)

        /** Lines wrap rather than clip once a translation is longer than the source. */
        private const val WRAP_LINES = 2

        /**
         * Lines for the panel in the manager's current UI language (default Chinese).
         *
         * Kept as the single entry point for callers that do not track a language; the
         * overload below is the one the live panel uses.
         */
        internal fun displayLines(data: JSONObject): List<DisplayLine> = displayLines(data, null)

        /**
         * Same rows, same order, same flags; only the rendered strings change. The
         * numbers, tile codes and the decision itself are never translated — a tile
         * score fragment is re-rendered by [GameMenuText] from the very same values.
         *
         * `null` or Chinese returns the original copy byte for byte.
         */
        internal fun displayLines(data: JSONObject, language: ManagerLanguage?): List<DisplayLine> {
            val lang = language ?: ManagerLanguage.SIMPLIFIED
            // Chinese is drawn exactly as stored, in either script.
            val zh = lang == ManagerLanguage.SIMPLIFIED
            fun t(text: String): String = if (zh) text else GameMenuText.text(text, lang)
            fun face(tile: String): String = if (zh) tileName(tile) else GameMenuText.tileName(tile, lang)
            val lines = mutableListOf<DisplayLine>()
            fun line(text: String, gold: Boolean = false, small: Boolean = false) {
                lines.add(DisplayLine(t(text), gold, small))
            }
            line("推荐切牌 · 决策概率", true)
            val rows = data.optJSONArray("rows") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val probability = row.optDouble("probabilityPercent", Double.NaN)
                val percent = if (probability.isFinite()) String.format(Locale.ROOT, "%.1f%%", probability) else "—"
                val cut = when (lang) {
                    ManagerLanguage.ENGLISH -> "Discard "
                    ManagerLanguage.JAPANESE -> "打 "
                    ManagerLanguage.SIMPLIFIED, ManagerLanguage.TRADITIONAL -> "切"
                }
                line("$cut${face(row.optString("tile"))}    $percent", i == 0)
            }
            val scores = data.optJSONArray("scores") ?: JSONArray()
            if (rows.length() == 0 && scores.length() == 0) line("等待出牌机会", small = true)
            if (scores.length() > 0) {
                val label = data.optString("scoreLabel", "和牌番点")
                line(if (zh) label else scoreLabel(label, lang), true, true)
                for (i in 0 until scores.length()) {
                    val score = scores.getJSONObject(i)
                    val win = face(score.optString("tile"))
                    val won = when (lang) {
                        ManagerLanguage.ENGLISH -> "Win $win"
                        ManagerLanguage.JAPANESE -> "和了 $win"
                        ManagerLanguage.SIMPLIFIED, ManagerLanguage.TRADITIONAL -> "和$win"
                    }
                    line(won, true, true)
                    fun scoreText(key: String): String {
                        val v = score.optJSONObject(key)
                        if (v == null) return if (zh) "无役" else t("无役")
                        return if (zh) {
                            "${if (v.optBoolean("yakuman")) "役满" else "${v.optInt("han")}番${v.optInt("fu")}符"} ${v.optInt("points")}点"
                        } else {
                            scoreValue(v, lang)
                        }
                    }
                    // The separator differs per language ("/" is a fraction bar in
                    // English), so translated lines are composed per language; the
                    // Chinese line keeps its exact original shape.
                    val ron = scoreText("ron")
                    val tsumo = scoreText("tsumo")
                    if (zh) {
                        line("荣和 $ron / 自摸 $tsumo", small = true)
                    } else {
                        // The scores come out of [scoreValue] already rendered for this
                        // language, so only the labels around them are translated here: a
                        // Chinese-shape line for Traditional, English/Japanese wording otherwise.
                        when (lang) {
                            ManagerLanguage.ENGLISH -> line("Ron $ron · Tsumo $tsumo", small = true)
                            ManagerLanguage.JAPANESE -> line("ロン $ron · ツモ $tsumo", small = true)
                            // Traditional keeps the Chinese line shape with converted wording;
                            // the scores beside it are already Traditional from [scoreValue].
                            ManagerLanguage.TRADITIONAL ->
                                line("榮和 $ron / 自摸 $tsumo", small = true)
                            ManagerLanguage.SIMPLIFIED -> {}
                        }
                    }
                    if (score.optJSONObject("riichi_ron") != null || score.optJSONObject("riichi_tsumo") != null) {
                        val ronR = scoreText("riichi_ron")
                        val tsumoR = scoreText("riichi_tsumo")
                        if (zh) {
                            line("立直 荣$ronR / 摸$tsumoR", small = true)
                        } else {
                            when (lang) {
                                ManagerLanguage.ENGLISH -> line("Riichi · ron $ronR · tsumo $tsumoR", small = true)
                                ManagerLanguage.JAPANESE -> line("リーチ ロン$ronR · ツモ$tsumoR", small = true)
                                ManagerLanguage.TRADITIONAL ->
                                    line("立直 榮$ronR / 摸$tsumoR", small = true)
                                ManagerLanguage.SIMPLIFIED -> {}
                            }
                        }
                    }
                }
                line("基础估算；不含里宝/一发/本场/供托/和牌赤5", small = true)
                if (data.optInt("players") == 3) line("拔北 ×${data.optInt("kitaCount", 0)}：已计入三麻番数", small = true)
            } else {
                val shanten = data.optInt("shanten", -99)
                line(if (shanten > 0) "${shanten}向听 · 听牌后显示番点" else if (shanten == 0) "已听牌 · 暂无可用番点" else "等待手牌分析", small = true)
            }
            return lines
        }

        /** One score object as a phrase ("2番30符 2000点" / "2 han 30 fu, 2000 pts"). */
        private fun scoreValue(v: JSONObject, language: ManagerLanguage): String {
            val points = v.optInt("points")
            return if (v.optBoolean("yakuman")) {
                when (language) {
                    ManagerLanguage.ENGLISH -> "yakuman $points pts"
                    ManagerLanguage.JAPANESE -> "役満 ${points}点"
                    // Chinese shape, Traditional wording; the point value is never converted.
                    ManagerLanguage.TRADITIONAL -> "役滿 ${points}點"
                    ManagerLanguage.SIMPLIFIED -> "役满 ${points}点"
                }
            } else {
                val han = v.optInt("han")
                val fu = v.optInt("fu")
                when (language) {
                    ManagerLanguage.ENGLISH -> "$han han $fu fu, $points pts"
                    ManagerLanguage.JAPANESE -> "${han}翻 ${fu}符 ${points}点"
                    ManagerLanguage.TRADITIONAL -> "${han}番${fu}符 ${points}點"
                    ManagerLanguage.SIMPLIFIED -> "${han}番${fu}符 ${points}点"
                }
            }
        }

        /**
         * The stored `scoreLabel` ("当前听牌番点" plus a furiten suffix) re-rendered in
         * [language]. Only the two shapes [dataFor] can produce are translated; anything
         * else is returned as stored.
         */
        private fun scoreLabel(label: String, language: ManagerLanguage): String {
            if (label == "和牌番点" || label == "当前听牌番点") return GameMenuText.text(label, language)
            val furitenSuffix = "（振听不可荣和）"
            if (label.endsWith(furitenSuffix)) {
                val base = label.removeSuffix(furitenSuffix)
                return GameMenuText.text(base, language) + GameMenuText.text(furitenSuffix, language)
            }
            return label
        }

        fun dataFor(result: JSONObject): JSONObject {
            val recommendations = result.optJSONArray("recommendations") ?: JSONArray()
            val analysis = result.optJSONObject("analysis")
            val hand13 = analysis?.optJSONObject("hand13")
            val hand14 = analysis?.optJSONObject("hand14")
            val shanten = sequenceOf(hand13, hand14)
                .mapNotNull { it?.optInt("shanten", -99)?.takeUnless { value -> value == -99 } }
                .firstOrNull() ?: -99
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
