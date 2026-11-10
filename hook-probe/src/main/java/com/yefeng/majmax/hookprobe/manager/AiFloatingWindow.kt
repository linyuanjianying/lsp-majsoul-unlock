package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.yefeng.majmax.hookprobe.helper.GameMenuText
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * System floating window, styled like an assistant panel: a translucent light
 * grey-white rounded card with a blue capsule navigation ("助手" / "设置"), two
 * switch cards ("无人值守" / "自动下一局" / "推荐与番点"), the live recommendation block, dark text,
 * pale blue separators and a red destructive stop action. Collapsed it is a small
 * round bubble; tapping it re-opens the panel, long-pressing it toggles unattended
 * mode, dragging moves it and the position/width/opacity are remembered.
 *
 * Behaviour contracts kept intact (see [AiOverlayService]):
 *  - the constructor callbacks/dependencies are unchanged ([stop], [toggleAuto],
 *    [setRematch], [rematch]), so the service keeps owning unattended mode
 *    ([AutoDiscardController]) and the persisted opt-in ([AutoRematchSettings],
 *    mirrored by [AutoRematchState]) — this class never writes preferences for them;
 *  - unattended mode changes only through its labelled control ([toggleAuto]);
 *    *on* only unfolds the panel (and shows the stop control) instead of silently
 *    stopping the mode, so a manual pause (which also stops manual override) is always
 *    an explicit, labelled action;
 *  - the rematch switch keeps its listener-free restore: the checked state, its tint
 *    and captions are written through [applyRematch] (CompoundButton.setChecked does
 *    not notify listeners), so a 200 ms service tick never issues a write;
 *  - every state render is idempotent and never fires a callback, so the 200 ms
 *    [updateAuto]/[updateRematch] ticks cannot toggle anything.
 */
internal class AiFloatingWindow(private val context: Context, private val stop: () -> Unit,
    private val toggleAuto: () -> Unit, private val setRematch: (Boolean) -> Boolean?,
    private val rematch: () -> RematchState) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val preferences = context.getSharedPreferences("ai-window", Context.MODE_PRIVATE)
    private val style = FloatingStyle(context)

    /** Panel section chosen by the capsule navigation: 0 = 助手, 1 = 设置. */
    private var tab = 0
    private var opacity = preferences.getInt("opacity", 78).coerceIn(25, 95)
    private var width = preferences.getInt("width", 300).coerceIn(220, 420)
    private var collapsed = preferences.getBoolean("collapsed", true)
    private var auto = AutoDiscardStatus()
    private var rematchState = RematchState()
    private var result = JSONObject().put("status", "waiting").put("message", "等待牌局连接")

    private var root: LinearLayout? = null
    private var bubble: View? = null
    private var bubbleCaption: TextView? = null
    private var bubbleBadge: TextView? = null
    private var title: TextView? = null
    private var subtitle: TextView? = null
    private var body: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var tabButton: TextView? = null
    private var settingsButton: TextView? = null
    private var settingsPane: LinearLayout? = null
    private var opacityCaption: TextView? = null
    private var widthCaption: TextView? = null
    private var autoSwitch: Switch? = null
    private var autoName: TextView? = null
    private var autoState: TextView? = null
    private var autoDetail: TextView? = null
    private var rematchSwitch: Switch? = null
    private var rematchName: TextView? = null
    private var rematchStateLabel: TextView? = null
    private var rematchDetail: TextView? = null
    private var appliedPanelEnabled: Boolean? = null
    private var panelSwitch: Switch? = null
    private var panelName: TextView? = null
    private var panelStateLabel: TextView? = null
    private var panelDetail: TextView? = null
    /** Title-bar drag bookkeeping, shared by the grab handle and the capsule tabs. */
    private var dragging = false

    /**
     * UI language every label of this window is drawn in.
     *
     * Seeded from the manager preference (this window lives in the manager process, so the
     * stored choice is authoritative) and refreshed by [refreshLanguage], which the service
     * calls from the tick it already runs. No timer, listener or extra view is created: a
     * change re-applies the copy to the views that already exist, through the same
     * render/apply paths every other repaint uses.
     */
    private var language: ManagerLanguage = runCatching {
        // Seeded from the stored choice (this window lives in the manager process, so the
        // preference is authoritative); the service tick re-checks it in [refreshLanguage].
        ManagerLanguageSettings.readChoice(context).effective(ManagerSystemLanguage.current())
    }.getOrDefault(ManagerLanguage.SIMPLIFIED)

    /** Name under which each slider caption was created, so a switch can re-label it. */
    private var opacitySliderName = "背景不透明度"
    private var widthSliderName = "窗口宽度"
    private var opacityCaptionText: String? = null
    private var widthCaptionText: String? = null

    private val layout = WindowManager.LayoutParams(
        dp(width), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = preferences.getInt("x", dp(16))
        y = preferences.getInt("y", dp(76))
    }

    fun show() { rebuild() }

    fun update(value: JSONObject) {
        result = value
        renderHeader()
        if (!collapsed && tab == 0) renderAssistant()
        root?.post { clampPosition(); root?.let { runCatching { windows.updateViewLayout(it, layout) } } }
    }

    fun close() {
        root?.let { runCatching { windows.removeView(it) } }
        root = null
        bubble = null
        bubbleCaption = null
        bubbleBadge = null
        title = null
        subtitle = null
        body = null
        scroll = null
        tabButton = null
        settingsButton = null
        settingsPane = null
        opacityCaption = null
        widthCaption = null
        opacityCaptionText = null
        widthCaptionText = null
        autoSwitch = null
        autoName = null
        autoState = null
        autoDetail = null
        rematchSwitch = null
        rematchName = null
        rematchStateLabel = null
        rematchDetail = null
        panelSwitch = null
        appliedPanelEnabled = null
        panelName = null
        panelStateLabel = null
        panelDetail = null
    }

    fun configurationChanged() { rebuild() }

    fun updateAuto(value: AutoDiscardStatus) {
        if (auto == value) {
            // The tick also carries the language check: an unchanged status still has to
            // follow a switch made on the manager page. One repaint, no extra timer.
            refreshLanguage()
            return
        }
        auto = value
        // Language first: a switch made on the manager page has to reach the copy of
        // this very repaint, not the next one.
        val switched = refreshLanguage()
        if (!switched) renderState()
    }

    /**
     * Follows the persisted opt-in (and its gate) from the service tick. Re-renders only
     * when the real state changed, so a tap that is still being written is not
     * overwritten by a repaint; when it did change, the switch, its captions and the
     * disabled state are all refreshed together. The refresh writes [Switch.isChecked]
     * directly, which does not notify the listener.
     */
    fun updateRematch(value: RematchState) {
        refreshLanguage()
        // The panel card is read back from the single preference; this also re-labels
        // it in the current language. `applyPanel` skips an unchanged value, so the
        // caption is refreshed explicitly for a language switch.
        applyPanel(RecommendationPanelSettings.read(context))
        if (rematchState == value) return
        rematchState = value
        applyRematch()
    }

    // ---- language ------------------------------------------------------------

    /** The language the manager page currently shows, resolved from the stored choice. */
    private fun currentLanguage(): ManagerLanguage = runCatching {
        ManagerLanguageSettings.readChoice(context).effective(ManagerSystemLanguage.current())
    }.getOrDefault(ManagerLanguage.SIMPLIFIED)

    private fun t(zh: String): String = GameMenuText.text(zh, language)

    /**
     * Adopts the manager's language and repaints the views that already exist.
     *
     * Driven by the service tick so the open window follows a switch without a restart,
     * and without a second timer. A collapsed window only has the bubble and the header
     * captions, which [renderHeader] already refreshes; an open one is re-rendered
     * through [renderHeader] + the active pane, both of which are idempotent and never
     * fire a callback, so the 200 ms tick cannot toggle anything.
     *
     * Returns true when the language actually changed.
     */
    fun refreshLanguage(): Boolean {
        val next = currentLanguage()
        if (next == language) return false
        language = next
        // Make the value-gated appliers write again: their text is copy too.
        appliedPanelEnabled = null
        renderHeader()
        if (root != null && !collapsed) showTab()
        // The auto-rematch detail is written by its own applier; force one pass.
        if (rematchDetail != null || rematchStateLabel != null) applyRematch()
        if (root == null) return true
        // The settings captions carry a value ("窗口宽度 · 300 dp"), so they are redrawn
        // from the stored number rather than merely re-labelled.
        paintSliderCaptions()
        return true
    }

    /**
     * Redraws the slider captions in the current language. The value comes from the same
     * source the slider was created with, so nothing is recomputed or re-saved here.
     */
    private fun paintSliderCaptions() {
        opacityCaption?.let { caption -> caption.text = sliderLabel(opacitySliderName, opacityCaptionText) }
        widthCaption?.let { caption -> caption.text = sliderLabel(widthSliderName, widthCaptionText) }
    }

    /** "<name> · <suffix>", where the suffix is whatever the slider last rendered. */
    private fun sliderLabel(nameZh: String, suffix: String?): String {
        val raw = suffix ?: return t(nameZh)
        // The suffix is "78%" or "300 dp": the numeric part is language neutral.
        val cut = raw.lastIndexOf(" · ")
        val value = if (cut >= 0) raw.substring(cut) else ""
        return t(nameZh) + value
    }

    // ---- shared helpers ------------------------------------------------------
    private fun dp(value: Int) = style.dp(value)
    private fun screenWidth() = context.resources.displayMetrics.widthPixels
    private fun screenHeight() = context.resources.displayMetrics.heightPixels
    private fun isDemo() = result.optString("status") == "demo"
    private fun statusIsGame() = result.optString("status") == "live" || isDemo()

    private fun caption() = t(when {
        statusIsGame() && isDemo() -> "本地自检 · 样例"
        statusIsGame() -> "雀魂 · 本地 AI"
        else -> "雀魂 · 本地助手"
    })

    private fun subCaption() = t(when {
        auto.enabled && isDemo() -> "自检中，不会操作游戏"
        auto.enabled -> "无人值守运行中"
        statusIsGame() && isDemo() -> "样例数据，仅供自检"
        statusIsGame() -> "对局已同步，等待操作"
        else -> "等待牌局连接"
    })

    private fun autoSummary() = t(if (auto.enabled) "开" else "关")
    private fun rematchSummary() = t(if (!rematchState.enabled) "关" else if (rematchState.active) "开" else "待开启")

    private fun switchOn(checked: Boolean, description: String) = Switch(context).apply {
        this.isChecked = checked
        isSaveEnabled = false
        contentDescription = description
        showText = false
        setPadding(0, 0, 0, 0)
        minimumWidth = dp(46)
        minimumHeight = dp(32)
        refreshDrawableState()
    }

    /** Blue when the mode is live, soft blue when on but gated, grey when off. */
    private fun switchColors(toggle: Switch?, checked: Boolean, active: Boolean = checked) {
        if (toggle == null) return
        toggle.thumbTintList = ColorStateList.valueOf(if (checked) Color.WHITE else Color.rgb(250, 251, 253))
        toggle.trackTintList = ColorStateList.valueOf(
            when {
                !checked -> style.trackOff
                active -> style.accent
                else -> Color.rgb(150, 176, 214)
            })
    }

    private fun seekColors(bar: SeekBar) {
        bar.thumbTintList = ColorStateList.valueOf(style.accent)
        bar.progressTintList = ColorStateList.valueOf(style.accent)
        bar.progressBackgroundTintList = ColorStateList.valueOf(style.trackOff)
    }

    // ---- lifecycle -----------------------------------------------------------
    private fun rebuild() {
        // Keep the current selection on a geometry change, but restore a stale
        // "settings" tab is nonsense while collapsed: collapse always shows the bubble.
        close()
        dragging = false
        // Never let a throwing state supplier tear down the window rebuild itself.
        rematchState = runCatching { rematch() }.getOrNull() ?: rematchState
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = style.panelBackground(opacity, collapsed)
        }
        if (collapsed) buildBubble(panel) else buildPanel(panel)
        root = panel
        layout.width = if (collapsed) dp(52)
            else dp(width).coerceAtMost(screenWidth() - dp(12)).coerceAtMost(maxPanelWidth())
        clampPosition()
        windows.addView(panel, layout)
        // The measured height is unknown until the first layout pass, so clamp and
        // persist the anchor once more with the real size.
        panel.post { clampPosition(); runCatching { windows.updateViewLayout(panel, layout) } }
    }

    /** Panel never covers the whole screen: 92 % of the short side, hard cap 420 dp. */
    private fun maxPanelHeight() = (screenHeight() * 0.72f).roundToInt().coerceAtLeast(dp(180))
    private fun maxPanelWidth() = dp(420).coerceAtMost((screenWidth() * 0.92f).roundToInt())

    // ---- collapsed bubble ----------------------------------------------------
    private fun buildBubble(panel: LinearLayout) {
        val circle = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = style.shape(style.bubbleColor(opacity), style.bubbleRadius().coerceAtMost(dp(26).toFloat()),
                0xFF485565.toInt(), 1)
            contentDescription = bubbleDescription()
            isClickable = true
            isFocusable = true
        }
        val caption = style.text(t("雀"), 15f,
            if (auto.enabled) 0xFFE6B783.toInt() else 0xFFD6E1ED.toInt(), true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
        }
        bubbleCaption = caption
        circle.addView(caption, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        val badge = style.text(autoSummary(), 9f, 0xFFA8B6C7.toInt(), true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
        bubbleBadge = badge
        circle.addView(badge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(circle, LinearLayout.LayoutParams(dp(52), dp(52)))
        bubble = circle
        makeDraggable(circle, bubbleClick())
    }

    private fun bubbleDescription(): String = t("点击展开助手面板，选择无人值守和自动下一局开关，可拖动")

    /** Opening the bubble never changes either persisted control. */
    private fun bubbleClick(): () -> Unit = {
        collapsed = false
        tab = 0
        save()
        rebuild()
    }

    // ---- expanded panel ------------------------------------------------------
    private fun buildPanel(panel: LinearLayout) {
        panel.addView(buildHeader())
        panel.addView(style.divider().apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(2)
            }
        })
        val scroller = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Bounded so a long recommendation list scrolls inside the window
                // instead of pushing the panel past the screen edge.
                val max = maxPanelHeight()
                super.onMeasure(widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            clipToPadding = false
        }
        scroll = scroller
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(10))
        }
        body = content
        scroller.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        showTab()
    }

    private fun buildHeader(): LinearLayout {
        val heading = style.row(48).apply {
            setPadding(dp(12), dp(4), dp(8), dp(2))
        }
        val labelGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = style.text(caption(), 13f, style.ink, true).apply {
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        this.title = title
        val copy = style.text(subCaption(), 10f, style.muted).apply {
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitle = copy
        labelGroup.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        labelGroup.addView(copy, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        heading.addView(labelGroup, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // Dashed grab handle: the whole title group drags the window.
        val grip = style.text("⋮⋮", 12f, style.muted, true).apply {
            gravity = Gravity.CENTER
            contentDescription = t("拖动悬浮窗")
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(40))
        }
        heading.addView(grip)
        makeDraggable(grip) { }
        makeDraggable(labelGroup) { }

        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }
        val assistant = tabChip(t("助手")) { tab = 0; dragging = false; showTab() }
        val settings = tabChip(t("设置")) { tab = 1; dragging = false; showTab() }
        tabButton = assistant
        settingsButton = settings
        tabs.addView(assistant, LinearLayout.LayoutParams(0, dp(30), 1f))
        tabs.addView(View(context), LinearLayout.LayoutParams(dp(4), dp(1)))
        tabs.addView(settings, LinearLayout.LayoutParams(0, dp(30), 1f))
        heading.addView(tabs, LinearLayout.LayoutParams(dp(132), dp(32)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        val close = style.text(t("收起"), 11f, style.muted, true).apply {
            gravity = Gravity.CENTER
            contentDescription = t("收起成浮标")
            setPadding(dp(6), dp(6), dp(6), dp(6))
            isClickable = true
            background = style.pill(Color.TRANSPARENT, dp(12).toFloat())
            setOnClickListener { collapsed = true; save(); rebuild() }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(40))
        }
        heading.addView(close)
        styleHeaderTabs()
        return heading
    }

    /** One navigation capsule. It also drags, so a press that moves never selects. */
    private fun tabChip(value: String, select: () -> Unit) = style.text(value, 11.5f, style.tabText, true)
        .apply {
            gravity = Gravity.CENTER
            contentDescription = if (value == t("助手")) t("助手分区") else t("设置分区")
            isClickable = true
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(30), 1f)
            makeDraggable(this) { select() }
        }

    /** Selected capsule = solid blue; the other one stays a transparent track. */
    private fun styleHeaderTabs() {
        val on = style.tinted(style.accent, dp(15).toFloat())
        val off = style.tinted(Color.TRANSPARENT, dp(15).toFloat())
        tabButton?.let { button ->
            val selected = tab == 0
            button.background = if (selected) on else off
            button.setTextColor(if (selected) style.onAccent else style.tabText)
            button.contentDescription = if (selected) t("助手分区，已选中") else t("助手分区") +
                t("，切换后查看推荐与对局状态")
        }
        settingsButton?.let { button ->
            val selected = tab == 1
            button.background = if (selected) on else off
            button.setTextColor(if (selected) style.onAccent else style.tabText)
            button.contentDescription = if (selected) t("设置分区，已选中") else
                t("设置分区") + t("，切换后调整宽度、透明度和开关")
        }
    }

    private fun renderHeader() {
        bubbleCaption?.text = t("雀")
        bubbleCaption?.setTextColor(if (auto.enabled) 0xFFE6B783.toInt() else 0xFFD6E1ED.toInt())
        bubbleBadge?.text = autoSummary()
        bubbleBadge?.setTextColor(0xFFA8B6C7.toInt())
        bubble?.contentDescription = bubbleDescription()
        title?.text = caption()
        subtitle?.text = subCaption()
        subtitle?.setTextColor(if (auto.enabled && !isDemo()) style.accentText else style.muted)
        if (!collapsed) styleHeaderTabs()
    }

    /** Switches the visible pane. Idempotent so ticks can call it safely. */
    private fun showTab() {
        val scroller = scroll ?: return
        scroller.scrollTo(0, 0)
        if (tab == 0) {
            settingsPane = null
            if (body == null) {
                val content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(2), dp(10), dp(10))
                }
                body = content
                scroller.removeAllViews()
                scroller.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            renderAssistant()
        } else {
            renderSettings()
        }
        styleHeaderTabs()
    }

    // ---- 助手 pane ------------------------------------------------------------
    private fun renderAssistant() {
        val content = body ?: return
        content.removeAllViews()
        content.addView(autoCard())
        content.addView(style.spacer(8))
        content.addView(rematchCard())
        content.addView(style.spacer(8))
        content.addView(recommendationPanelCard())
        content.addView(style.spacer(10))
        renderStatus(content)
    }

    /**
     * Unattended-mode switch. Tapping it calls [toggleAuto] only; its checked state,
     * tint and caption are read back from [auto] on every render, so the 200 ms tick is
     * the single authority and the row can never drift from the controller.
     */
    private fun autoCard(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = style.card(opacity)
            setPadding(dp(12), dp(8), dp(10), dp(10))
        }
        card.addView(style.text(t("自动控制"), 10f, style.muted, true))
        val name = style.text(t("无人值守"), 14f, style.ink, true).apply { maxLines = 1 }
        autoName = name
        val row = style.row(44)
        row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val stateLabel = style.text(autoSummary(), 11f, style.muted, true).apply {
            maxLines = 1
            includeFontPadding = false
        }
        autoState = stateLabel
        row.addView(stateLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        val toggle = switchOn(auto.enabled, t("无人值守模式开关"))
        autoSwitch = toggle
        toggle.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            // Only a user-driven change reaches here (restores write isChecked), and the
            // controller is the single owner: ask it to toggle, then re-read its state.
            val wantsOn = checked
            if (wantsOn != auto.enabled) toggleAuto()
            renderState()
        }
        row.addView(toggle, LinearLayout.LayoutParams(dp(52), dp(32)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        val detail = style.text("", 11f, style.muted).apply { maxLines = 3 }
        autoDetail = detail
        card.addView(detail)
        val hint = style.text(t(if (auto.enabled) "点开关即可随时暂停；手动出牌会关闭此模式" else
            "开启后按 AI 首选自动出牌，可随时停止"), 10f, style.faint).apply {
            maxLines = 2
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(hint)
        renderAutoCard()
        return card
    }

    /**
     * Single re-render entry point for both service ticks. Order matters: the header is
     * painted first (it has no switch), then the cards read the very same [auto] state,
     * so neither renderer can recurse into the other.
     */
    private fun renderState() {
        renderHeader()
        relabelCards()
        renderAutoCard()
    }

    /**
     * Re-labels every text this window owns that is not covered by [applyPanel] /
     * [applyRematch] / [renderHeader]. Only text and content descriptions are written:
     * no view is added or removed and no switch state or callback is touched, so it is
     * safe on the 200 ms tick and on a language switch alike.
     */
    private fun relabelCards() {
        autoName?.text = t("无人值守")
        autoSwitch?.contentDescription = t("无人值守模式开关")
        rematchName?.text = t("自动下一局")
        rematchSwitch?.contentDescription = t("整场结束后自动匹配（实验性）")
        panelName?.text = t("推荐与番点")
        panelSwitch?.contentDescription = t("推荐与番点面板开关")
    }

    private fun renderAutoCard() {
        val toggle = autoSwitch ?: return
        // Never write through setChecked: its listener would call toggleAuto() again.
        // Setting isChecked directly keeps the listener untouched.
        applyChecked(toggle, auto.enabled)
        toggleColorsHold()
        switchColors(autoSwitch, auto.enabled, auto.enabled && !isDemo())
        autoName?.setTextColor(if (auto.enabled) style.accentText else style.ink)
        autoState?.text = autoSummary()
        autoState?.setTextColor(if (auto.enabled) style.accentText else style.muted)
        // The controller's own message: the manager's status-line table knows its known
        // phrases, and anything else (an engine or unknown diagnostic) is shown exactly as
        // produced — never rewritten.
        autoDetail?.text = if (auto.message.isBlank()) t("无人值守模式已关闭")
            else ManagerLanguageText.trim(auto.message, language)
        autoDetail?.setTextColor(if (auto.enabled) style.inkSoft else style.muted)
        autoDetail?.setPadding(0, dp(2), 0, 0)
    }

    /**
     * The rematch card mirrors the original contract exactly: the persisted opt-in drives
     * the thumb, [rematchState.active] only changes the explanation, and the write goes
     * through [setRematch] (preference only, never the service or unattended mode).
     */
    private fun rematchCard(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = style.card(opacity)
            setPadding(dp(12), dp(8), dp(10), dp(10))
        }
        card.addView(style.text(t("牌局续接"), 10f, style.muted, true))
        val name = style.text(t("自动下一局"), 14f, style.ink, true).apply { maxLines = 1 }
        rematchName = name
        val row = style.row(44)
        row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val stateLabel = style.text(rematchSummary(), 11f, style.muted, true).apply {
            maxLines = 1
            includeFontPadding = false
        }
        rematchStateLabel = stateLabel
        row.addView(stateLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        val toggle = switchOn(rematchState.enabled, t("整场结束后自动匹配（实验性）"))
        rematchSwitch = toggle
        toggle.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            onRematchTap(checked)
        }
        row.addView(toggle, LinearLayout.LayoutParams(dp(52), dp(32)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        val detail = style.text("", 11f, style.muted).apply { maxLines = 3 }
        rematchDetail = detail
        card.addView(detail)
        val hint = style.text(t("整场结束后调用游戏的结算与“下一局”，不新建或伪造请求"), 10f, style.faint).apply {
            maxLines = 2
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(hint)
        applyRematch()
        return card
    }

    /**
     * 推荐与番点面板开关（点击穿透固定面板上的推荐 / 番点内容）。
     *
     * 它自成一体：只在用户点按时实时读写 [RecommendationPanelSettings]，从不启停
     * 服务、不改无人值守 / 自动下一局、不切换显示模式，也不影响猫头提示。与
     * [autoCard] / [rematchCard] 一样，`update` 重建本卡片时会重新读取已保存的值，
     * 因此管理器界面、游戏内控制与这里始终一致。
     */
    private fun recommendationPanelCard(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = style.card(opacity)
            setPadding(dp(12), dp(8), dp(10), dp(10))
        }
        card.addView(style.text(t("固定面板"), 10f, style.muted, true))
        val name = style.text(t("推荐与番点"), 14f, style.ink, true).apply { maxLines = 1 }
        panelName = name
        val row = style.row(44)
        row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val stored = runCatching { RecommendationPanelSettings.read(context) }.getOrDefault(true)
        val stateLabel = style.text(panelSummary(stored), 11f, style.muted, true).apply {
            maxLines = 1
            includeFontPadding = false
        }
        panelStateLabel = stateLabel
        row.addView(stateLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        val toggle = switchOn(stored, t("推荐与番点面板开关"))
        panelSwitch = toggle
        appliedPanelEnabled = null
        toggle.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            onPanelTap(checked)
        }
        row.addView(toggle, LinearLayout.LayoutParams(dp(52), dp(32)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        val detail = style.text("", 11f, style.muted).apply { maxLines = 3 }
        panelDetail = detail
        card.addView(detail)
        val hint = style.text(t("只控制点击穿透固定面板的内容；猫头提示与其他开关各自独立"),
            10f, style.faint).apply {
            maxLines = 2
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(hint)
        applyPanel(stored)
        return card
    }

    /** One tap = one preference write; the thumb always snaps to the *stored* value. */
    private fun onPanelTap(checked: Boolean) {
        val saved = runCatching { RecommendationPanelSettings.save(context, checked) }
            .getOrDefault(checked)
        // A user tap already moved the thumb, even if persistence returned the old value.
        appliedPanelEnabled = null
        applyPanel(saved)
    }

    /**
     * Keeps switch, caption and explanation in one place so they can never disagree.
     * Returns false when nothing was written (already applied).
     */
    private fun applyPanel(enabled: Boolean): Boolean {
        if (panelSwitch == null || appliedPanelEnabled == enabled) return false
        appliedPanelEnabled = enabled
        panelSwitch?.let { applyChecked(it, enabled) }
        switchColors(panelSwitch, enabled, enabled)
        panelSwitch?.refreshDrawableState()
        panelName?.setTextColor(if (enabled) style.accentText else style.ink)
        panelStateLabel?.text = panelSummary(enabled)
        panelStateLabel?.setTextColor(if (enabled) style.accentText else style.muted)
        panelDetail?.text = t(if (enabled) "固定面板显示建议牌与番点；可随时在此关闭"
            else "已隐藏建议牌与番点，其他功能不受影响")
        panelDetail?.setTextColor(if (enabled) style.inkSoft else style.muted)
        panelDetail?.setPadding(0, dp(2), 0, 0)
        // The card's title and switch label are the same copy: written here, and reached
        // again on a language switch because `refreshLanguage` clears the marker above.
        panelName?.text = t("推荐与番点")
        panelSwitch?.contentDescription = t("推荐与番点面板开关")
        return true
    }

    private fun panelSummary(enabled: Boolean) = t(if (enabled) "开" else "关")

    /**
     * Sets the thumb without notifying the listener. [CompoundButton.setChecked] only
     * dispatches `onCheckedChanged` when the value actually changes, and this restore
     * path always runs *after* the listener already fired (or from a tick), so no write
     * is ever triggered here.
     */
    private fun applyChecked(toggle: Switch, checked: Boolean) {
        if (toggle.isChecked != checked) toggle.isChecked = checked
    }

    /** Clears a pressed/dragged tint state left over from the last interaction. */
    private fun toggleColorsHold() {
        autoSwitch?.refreshDrawableState()
        rematchSwitch?.refreshDrawableState()
    }

    /** Keeps switch, caption and explanation in one place so they can never disagree. */
    private fun applyRematch() {
        rematchSwitch?.let { applyChecked(it, rematchState.enabled) }
        switchColors(rematchSwitch, rematchState.enabled, rematchState.active)
        rematchSwitch?.refreshDrawableState()
        rematchName?.setTextColor(if (rematchState.enabled) style.accentText else style.ink)
        rematchStateLabel?.text = rematchSummary()
        rematchStateLabel?.setTextColor(when {
            !rematchState.enabled -> style.muted
            rematchState.active -> style.accentText
            else -> style.warn
        })
        rematchDetail?.text = rematchText()
        rematchDetail?.setTextColor(if (rematchState.active || !rematchState.enabled) style.muted else style.warn)
        rematchDetail?.setPadding(0, dp(2), 0, 0)
    }

    private fun rematchText(): String = t(when {
        rematchState.demo -> "模型自检中不会自动匹配"
        rematchState.enabled -> "仅调用游戏的“下一局”原按钮；可随时在此关闭"
        else -> "默认关闭。与无人值守独立，开启后自动续局"
    })

    /**
     * One tap = one preference write. The switch first snaps to the *stored* result of
     * that write (never to the requested value), so a failed or unavailable write is
     * visible immediately instead of leaving a lying switch; the next service tick then
     * replaces it with the authoritative state.
     */
    private fun onRematchTap(checked: Boolean) {
        val saved = runCatching { setRematch(checked) }.getOrNull()
        // A null write result means "no service owns this preference right now": keep the
        // last known value instead of painting the unchecked request as if it stuck.
        rematchState = if (saved != null) rematchState.copy(enabled = saved) else runCatching { rematch() }
            .getOrNull() ?: rematchState
        applyRematch()
    }

    /** Live recommendation block: the content the user opens the panel for. */
    private fun renderStatus(parent: LinearLayout) {
        val status = result.optString("status")
        // A status line the manager produced is translated when it is a known phrase;
        // an engine/unknown diagnostic is shown exactly as received.
        val message = GameMenuText.text(result.optString("message", "等待牌局").ifBlank { "等待牌局" }, language)
        val messageColor = when {
            status == "demo" -> style.warn
            auto.enabled -> style.inkSoft
            else -> style.muted
        }
        parent.addView(style.text(message, 11.5f, messageColor).apply { maxLines = 3 })
        if (status != "live" && status != "demo") {
            parent.addView(style.text(t("先开启助手，再启动雀魂进入对局。中途开启或重连后，需等待完整牌局同步。"),
                11f, style.faint).apply {
                maxLines = 3
                setPadding(0, dp(6), 0, 0)
            })
            return
        }
        parent.addView(style.text(
            localizeRound(result.optString("round"), result.optInt("players", 4)),
            11f, style.muted).apply { setPadding(0, dp(4), 0, 0) })
        val analysis = result.optJSONObject("analysis") ?: return
        if (result.optBoolean("modelExperimental")) parent.addView(style.text(t("实验性 Mortal v4 · 特征含近似计算"),
            10f, style.faint).apply { setPadding(0, dp(4), 0, 0) })
        val rows = result.optJSONArray("recommendations") ?: JSONArray()
        parent.addView(style.spacer(6))
        if (rows.length() > 0) parent.addView(recommendationHeader())
        if (rows.length() == 0) {
            parent.addView(style.text(t("等待操作机会"), 18f, style.accentText, true).apply {
                setPadding(dp(4), dp(8), dp(4), dp(2))
            })
            analysis.optJSONObject("hand13")?.let {
                parent.addView(style.text(handDescription(it), 11f, style.muted).apply {
                    setPadding(dp(4), 0, dp(4), 0)
                })
            }
        }
        val shown = minOf(3, rows.length())
        for (i in 0 until shown) {
            val row = rows.getJSONObject(i)
            val tile = row.optString("tile")
            val kind = row.optString("kind")
            val name = actionName(kind, tile)
            val weight = row.optDouble("weight", -1.0)
            parent.addView(recommendationRow(name,
                if (weight > 0.0) String.format(Locale.ROOT, "%.1f%%", weight * 100) else "", i))
            if (kind == "discard" || kind == "riichi") {
                val index = tileIndex(tile)
                val risks = analysis.optJSONArray("mixed_risk")
                val risk = if (index >= 0 && risks != null && index < risks.length()) number(risks.optDouble(index)) else "—"
                val hand = discardResult(analysis, tile)
                if (i == 0) parent.addView(style.text(mainDetail(hand, risk), 10.5f, style.muted).apply {
                    setPadding(dp(8), 0, dp(4), dp(2))
                })
                else {
                    val chance = if (hand?.optInt("shanten") == 0) GameMenuText.text(
                        "估计和率 ${number(hand.optDouble("avg_agari_rate"))}%", language)
                        else GameMenuText.text("${hand?.optInt("shanten") ?: "—"}向听", language)
                    parent.addView(style.text(
                        "$chance · ${GameMenuText.text("风险 $risk", language)}", 10.5f, style.muted).apply {
                        setPadding(dp(8), 0, dp(4), dp(2))
                    })
                }
            }
            if (i < shown - 1) parent.addView(style.divider().apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(2); bottomMargin = dp(2); leftMargin = dp(4); rightMargin = dp(4)
                }
            })
        }
        if (shown > 0) parent.addView(style.divider())
        val defence = analysis.optString("best_defence_discard").takeUnless { it == "null" || it.isBlank() }
        val mixed = analysis.optJSONArray("mixed_risk") ?: JSONArray()
        if (defence != null && rows.length() > 0 && (0 until mixed.length()).any { mixed.optDouble(it) > 0.0 }) {
            parent.addView(style.text(
                "${GameMenuText.text("🛡️ 防守参考", language)}  ${tileName(defence)}", 11f, style.inkSoft).apply {
                setPadding(0, dp(2), 0, 0)
            })
        }
        val opponents = analysis.optJSONArray("opponents") ?: JSONArray()
        val riichiSeats = (0 until opponents.length()).map { opponents.getJSONObject(it) }
            .filter { it.optBoolean("is_riichi") }
            .map { relativeSeat(it.optInt("seat"), result.optInt("seat"), result.optInt("players", 4)) }
        if (riichiSeats.isNotEmpty()) parent.addView(style.text(
            "${GameMenuText.text("⚠️ 已立直：", language)}${riichiSeats.joinToString(seatJoiner())}",
            11f, style.warn))
        parent.addView(style.text(t("和牌率为听牌后的估计；风险指数越低越好，含打点权重，并非实际放铳概率。"),
            9.5f, style.faint).apply { setPadding(0, dp(6), 0, 0) })
        val duration = if (status == "demo") result.optLong("loadAndInferenceMs") else result.optLong("elapsedMs")
        parent.addView(style.text(
            GameMenuText.text("手机本地计算 · ", language) + "$duration ms" +
                (if (status == "demo") GameMenuText.text("（含模型加载）", language) else ""),
            9.5f, style.faint).apply { setPadding(0, dp(2), 0, 0) })
    }

    private fun recommendationHeader() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        addView(style.text(t("🎯 推荐切牌"), 12f, style.muted, true))
        addView(style.spacer(4))
    }

    /**
     * One recommendation line. Rank 1 is a filled blue pill, rank 2 a pale blue pill and
     * rank 3 a plain line, so the ordering reads at a glance without colour-only cues
     * (the rank is also written as a number).
     */
    private fun recommendationRow(name: String, chance: String, rank: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(3), dp(4), dp(3))
        val filled = rank == 0
        background = when (rank) {
            0 -> style.pill(style.accent, dp(12).toFloat())
            1 -> style.pill(style.accentSoft, dp(12).toFloat())
            else -> style.pill(Color.TRANSPARENT, dp(12).toFloat())
        }
        addView(style.text(if (rank == 0) t("首选") else "${rank + 1}", 10f,
            if (filled) style.onAccent else style.accentText, true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        addView(style.text(name, 14f, if (filled) style.onAccent else style.ink, true).apply {
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (chance.isNotBlank()) addView(style.text(chance, 12f,
            if (filled) style.onAccent else style.accentText, true).apply {
            gravity = Gravity.END
            maxLines = 1
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
    }

    // ---- 设置 pane ------------------------------------------------------------
    private fun renderSettings() {
        val content = body ?: return
        content.removeAllViews()
        if (settingsPane == null || content.childCount == 0) {
            settingsPane = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            content.removeAllViews()
            content.addView(settingsPane)
        }
        val pane = settingsPane ?: return
        pane.removeAllViews()
        pane.addView(style.text(t("窗口外观"), 10f, style.muted, true).apply { setPadding(dp(2), 0, 0, dp(2)) })
        opacitySliderName = "背景不透明度"
        addSlider(pane, t(opacitySliderName), 25, 95, opacity, { "$it%" }, live = true) { value ->
            opacity = value; root?.background = style.panelBackground(opacity, collapsed); save()
        }.also { opacityCaption = it; opacityCaptionText = it.text?.toString() }
        widthSliderName = "窗口宽度"
        addSlider(pane, t(widthSliderName), 220, 420, width, { "$it dp" }, live = false) { value ->
            width = value
            layout.width = dp(width).coerceAtMost(screenWidth() - dp(12)).coerceAtMost(maxPanelWidth())
            clampPosition()
            root?.let { panel -> windows.updateViewLayout(panel, layout) }
            save()
        }.also { widthCaption = it; widthCaptionText = it.text?.toString() }
        pane.addView(style.divider())
        pane.addView(style.text(t("助手操作"), 10f, style.muted, true).apply { setPadding(dp(2), 0, 0, dp(4)) })
        pane.addView(style.text(t("停止助手会关闭无人值守模式并隐藏悬浮窗；拖动标题栏移动，点“收起”变成浮标。"),
            10.5f, style.faint).apply { setPadding(dp(2), 0, dp(2), dp(8)) })
        val stopAction = style.text(t("停止助手"), 13f, style.danger, true).apply {
            gravity = Gravity.CENTER
            contentDescription = t("关闭 AI 助手")
            isClickable = true
            background = style.tinted(style.dangerTrack, dp(14).toFloat(), Color.argb(90, 214, 64, 66),
                Color.argb(60, 214, 64, 66))
            setOnClickListener { stop() }
        }
        pane.addView(stopAction, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
    }

    /** Returns the caption so a stale reference is never kept after a rebuild. */
    private fun addSlider(parent: LinearLayout, name: String, min: Int, max: Int, value: Int,
        format: (Int) -> String, live: Boolean = true, change: (Int) -> Unit): TextView {
        val caption = style.text("$name · ${format(value)}", 11f, style.muted)
        parent.addView(caption)
        val bar = SeekBar(context).apply {
            this.max = max - min
            progress = value - min
            contentDescription = name
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val rendered = "$name · ${format(progress + min)}"
                        caption.text = rendered
                        // Remember what was rendered so a language switch can keep the
                        // value and only swap the label.
                        if (name == t(opacitySliderName)) opacityCaptionText = rendered
                        else widthCaptionText = rendered
                        if (live) change(progress + min)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (!live && seekBar != null) change(seekBar.progress + min)
                }
            })
        }
        seekColors(bar)
        parent.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
        return caption
    }

    /**
     * Drag support shared by the bubble and the panel header. Movement only starts past
     * [ViewConfiguration.getScaledTouchSlop], and a press that moved never counts as a
     * click, so dragging the panel cannot select a tab, unfold the bubble or toggle a
     * mode by accident. The manager's touch area stays inside this window: every view
     * here is a child of the single overlay view, and the window itself is
     * FLAG_NOT_TOUCH_MODAL, so touches outside its bounds reach the game unchanged.
     */
    private fun makeDraggable(view: View, click: () -> Unit) {
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var moved = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        view.setOnClickListener { click() }
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = layout.x
                    originY = layout.y
                    moved = false
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) moved = true
                    if (moved) {
                        dragging = true
                        layout.x = originX + (event.rawX - downX).roundToInt()
                        layout.y = originY + (event.rawY - downY).roundToInt()
                        clampPosition()
                        root?.let { windows.updateViewLayout(it, layout) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val wasMoved = moved
                    moved = false
                    dragging = false
                    if (wasMoved) save() else target.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    moved = false
                    dragging = false
                    save()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Keeps the window fully reachable: the saved anchor survives rotation and density
     * changes in the preferences, but is clamped to the current display so a window
     * dragged to an edge in portrait cannot land off-screen in landscape.
     */
    private fun clampPosition() {
        val display = context.resources.displayMetrics
        val height = root?.height?.takeIf { it > 0 } ?: if (collapsed) dp(52) else dp(200)
        layout.x = layout.x.coerceIn(0, (display.widthPixels - layout.width).coerceAtLeast(0))
        layout.y = layout.y.coerceIn(0, (display.heightPixels - height - dp(32)).coerceAtLeast(0))
    }

    private fun save() {
        preferences.edit().putInt("x", layout.x).putInt("y", layout.y).putInt("opacity", opacity)
            .putInt("width", width).putBoolean("collapsed", collapsed).apply()
    }

    private fun discardResult(analysis: JSONObject, tile: String): JSONObject? {
        val hand = analysis.optJSONObject("hand14") ?: return null
        for (group in listOf("maintain", "backwards")) {
            val choices = hand.optJSONArray(group) ?: continue
            for (i in 0 until choices.length()) {
                val candidate = choices.getJSONObject(i)
                if (candidate.optString("discard").removeSuffix("r") == tile.removeSuffix("r")) return candidate.optJSONObject("result")
            }
        }
        return null
    }

    private fun handDescription(hand: JSONObject): String {
        val shanten = hand.optInt("shanten", 99)
        val stage = t(when { shanten == 0 -> "听牌"; shanten < 0 -> "和牌形"; else -> "${shanten}向听" })
        val chance = if (shanten == 0) GameMenuText.text(
            "估计和牌率 ${number(hand.optDouble("avg_agari_rate"))}%", language)
            else GameMenuText.text("和牌率 —（未听牌）", language)
        val waits = GameMenuText.text("进张 ${hand.optInt("waits_total")} 枚", language)
        val furiten = if (hand.optBoolean("is_furiten")) " · " + t("振听") else ""
        return "$stage · $waits\n$chance$furiten"
    }

    private fun mainDetail(hand: JSONObject?, risk: String): String {
        if (hand == null) return GameMenuText.text("放铳风险指数 $risk", language)
        val shanten = hand.optInt("shanten", 99)
        val stage = t(when { shanten == 0 -> "听牌"; shanten < 0 -> "和牌形"; else -> "${shanten}向听" })
        val waits = GameMenuText.text("进张 ${hand.optInt("waits_total")} 枚", language)
        val rate = if (shanten == 0) GameMenuText.text(
            "和率 ${number(hand.optDouble("avg_agari_rate"))}%", language) else null
        val furiten = if (hand.optBoolean("is_furiten")) t("振听") else null
        return listOfNotNull(stage, waits, rate, furiten,
            GameMenuText.text("风险 $risk", language)).joinToString(" · ")
    }

    private fun number(value: Double) = if (value.isFinite()) String.format(Locale.ROOT, "%.1f", value) else "—"

    private fun relativeSeat(seat: Int, own: Int, players: Int): String = when ((seat - own + players) % players) {
        1 -> "下家"; 2 -> if (players == 3) "上家" else "对家"; else -> "上家"
    }

    // ---- copy for the current language --------------------------------------

    /**
     * One recommendation as a phrase. Chinese keeps the original wording byte for byte;
     * the other languages use the mahjong words the app's players use (ポン/チー/カン/
     * リーチ/北抜き and the English discard/call/win forms).
     */
    private fun actionName(kind: String, tile: String): String {
        val face = tileName(tile)
        val blank = tile.isBlank()
        return when (language) {
            ManagerLanguage.SIMPLIFIED -> when (kind) {
                "discard" -> "切 $face"
                "riichi" -> if (blank) "立直" else "立直 · 切 $face"
                "pon" -> "碰 $face"
                "chi" -> "吃 $face"
                "kan", "ankan", "kakan" -> "杠 $face"
                "hora" -> "和牌"
                "kita" -> "拔北"
                "abort" -> "九种九牌流局"
                else -> "跳过"
            }
            ManagerLanguage.ENGLISH -> when (kind) {
                "discard" -> "Discard $face"
                "riichi" -> if (blank) "Riichi" else "Riichi · discard $face"
                "pon" -> "Pon $face"
                "chi" -> "Chi $face"
                "kan", "ankan", "kakan" -> "Kan $face"
                "hora" -> "Win"
                "kita" -> "Kita"
                "abort" -> "Nine terminals draw"
                else -> "Pass"
            }
            ManagerLanguage.JAPANESE -> when (kind) {
                "discard" -> "打 $face"
                "riichi" -> if (blank) "リーチ" else "リーチ・打 $face"
                "pon" -> "ポン $face"
                "chi" -> "チー $face"
                "kan", "ankan", "kakan" -> "カン $face"
                "hora" -> "和了"
                "kita" -> "北抜き"
                "abort" -> "九種九牌流局"
                else -> "パス"
            }
        }
    }

    /** Separator between several riichi seats, in the current language. */
    private fun seatJoiner(): String = if (language == ManagerLanguage.ENGLISH) ", " else "、"

    /**
     * The round line: the engine's shorthand ("E1", "S2") plus the player count.
     *
     * The letters are the engine's own notation, translated only for display; an
     * unrecognised remainder is shown as received.
     */
    private fun localizeRound(round: String, players: Int): String {
        val text = if (language == ManagerLanguage.SIMPLIFIED) {
            round.replace("E", "东").replace("S", "南").replace("W", "西").replace("N", "北")
        } else {
            GameMenuText.roundLabel(round, language)
        }
        return text + " · " + GameMenuText.text("$players 人", language)
    }

    private fun tileName(tile: String): String {
        if (language == ManagerLanguage.SIMPLIFIED) {
            val honors = mapOf("E" to "东", "S" to "南", "W" to "西", "N" to "北", "P" to "白", "F" to "发", "C" to "中")
            honors[tile]?.let { return it }
            if (tile.length < 2) return "—"
            val suit = when (tile[1]) { 'm' -> "万"; 'p' -> "筒"; 's' -> "索"; else -> "" }
            return (if (tile.endsWith("r")) "赤" else "") + tile[0] + suit
        }
        // Same codes, rendered by the shared helper so the panel and this window agree.
        val face = GameMenuText.tileName(tile, language)
        return if (tile.length < 2 && face == tile) "—" else face
    }

    private fun tileIndex(tile: String): Int {
        val honor = listOf("E", "S", "W", "N", "P", "F", "C").indexOf(tile)
        if (honor >= 0) return 27 + honor
        if (tile.length < 2) return -1
        val rank = tile[0].digitToIntOrNull()?.takeIf { it in 1..9 } ?: return -1
        return when (tile[1]) { 'm' -> rank - 1; 'p' -> rank + 8; 's' -> rank + 17; else -> -1 }
    }
}
