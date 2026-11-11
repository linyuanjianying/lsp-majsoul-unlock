package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.PixelFormat
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * System floating window, styled like the in-game assistant card: an opaque deep navy
 * panel (`#111a2c`) with a thin gold-orange hairline (`#b68a48`) and a large corner
 * radius, deep grey-blue cards (`#20283a`), light grey-white text, gold-orange accents
 * (`#f6ad35`) and a teal "running" green (`#35d3ad`).
 *
 * Layout:
 *  - title row: running dot + "雀魂助手" + status pill, then `-` (collapse) and `×`
 *    (close) as two circular buttons on the same line;
 *  - one full-width tab bar with three sections — 控制 / 状态 / 设置 — the selected one
 *    painted gold with dark ink, the others grey text on the dark track;
 *  - 控制: unattended switch + one-line caption, auto-rematch switch + ⓘ that opens a
 *    *help page inside this overlay window* (never a dialog on a service context), the
 *    recommendation-panel (推荐与番点) switch, and the red outlined stop button;
 *  - 状态: one row per live fact (name · state dot · caption), the verbose details
 *    collapsed behind an expander;
 *  - 设置: sliders whose value sits in a small gold tag at the right.
 *
 * Collapsed it walks the four-shape state machine of [FloatingShapeMachine]: a green-dot
 * status capsule (“助手运行中”), the full navy\/gold ball, the parked half-tucked ball at
 * 65 % alpha, and the expanded panel. The capsule never shows the four-cell icon; after
 * 3 s idle it becomes the full ball, after another 3 s the ball parks itself in the
 * nearest edge, a tap on the parked ball wakes it back to the capsule, the next tap opens
 * the panel. A drag goes straight back to the full ball and re-arms the timer.
 *
 * Behaviour contracts kept intact (see [AiOverlayService]):
 *  - the constructor callbacks/dependencies are unchanged ([stop], [toggleAuto],
 *    [setRematch], [rematch]), so the service keeps owning unattended mode
 *    ([AutoDiscardController]) and the persisted opt-in ([AutoRematchSettings], mirrored
 *    by [RematchState]) — this class never writes preferences for them;
 *  - unattended mode changes only through its labelled control ([toggleAuto]); every
 *    other route that pauses it (stop button, long-press on the ball) stays visible and
 *    labelled, so no capability is lost;
 *  - the rematch and recommendation-panel switches keep their listener-free restore: the
 *    checked state, tint and captions are written through `apply*`, and a 200 ms service
 *    tick never issues a write;
 *  - every state render is idempotent and never fires a callback, so the 200 ms
 *    [updateAuto]/[updateRematch] ticks cannot toggle anything;
 *  - the window keeps its TYPE_APPLICATION_OVERLAY parameters, its stored size/position
 *    preference keys and the FLAG_NOT_TOUCH_MODAL contract, so touches outside the
 *    window still reach the game.
 *
 * All new copy goes through [UiLanguage.text] (zh, en, ja) so every rendering (including the
 * Traditional Chinese one derived from the Chinese text) can
 * never drift apart; every interactive surface is at least 44 dp.
 */
internal class AiFloatingWindow(private val context: Context, private val stop: () -> Unit,
    private val toggleAuto: () -> Unit, private val setRematch: (Boolean) -> Boolean?,
    private val rematch: () -> RematchState) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val preferences = context.getSharedPreferences("ai-window", Context.MODE_PRIVATE)
    private val style = FloatingStyle(context)

    /** Panel section chosen by the tab bar: 0 = 控制, 1 = 状态, 2 = 设置. */
    private var tab = 0

    /**
     * The in-window help page (opened by the ⓘ next to 自动下一局). It is a pane of the
     * same overlay view, so it needs no Dialog theme, no activity token and no extra
     * window: the TYPE_APPLICATION_OVERLAY window simply shows different content, which
     * is the only safe construction for a Service context.
     */
    private var helpOpen = false

    /** Details expander on the 状态 page; remembered while the window lives. */
    private var detailsOpen = false

    /**
     * Panel opacity: 100 on a fresh install (the design is an opaque deep-navy card), the
     * stored value otherwise. Keyed on "has the user ever moved the slider", not on the raw
     * value, so an existing 78 is preserved while a device that never touched it gets the
     * opaque default.
     */
    private var opacity = (if (preferences.contains("opacity")) preferences.getInt("opacity", 100)
        else 100).coerceIn(25, 100)
    private var width = preferences.getInt("width", 300).coerceIn(220, 420)

    /**
     * Shape machine of the collapsed surface — the single owner of “which of the four
     * shapes is on screen”. It survives rebuilds (rotation, language switch, panel fold)
     * because a rebuild re-renders [FloatingShapeMachine.shape] instead of resetting it.
     */
    private val machine = FloatingShapeMachine(loadInitialShape())

    /** Main-thread handler for the idle fold steps; exactly one callback at a time. */
    private val handler = Handler(Looper.getMainLooper())
    private val idleStep = Runnable { onIdleStep() }


    private var auto = AutoDiscardStatus()
    private var rematchState = RematchState()
    private var result = JSONObject().put("status", "waiting").put("message", "等待牌局连接")

    private var root: LinearLayout? = null
    private var bubble: View? = null
    private var bubbleDot: View? = null
    private var bubbleCaption: TextView? = null
    /** Status capsule: dot + live text, shown instead of the four-cell icon. */
    private var capsule: View? = null
    private var capsuleDot: View? = null
    private var capsuleText: TextView? = null
    private var titleDot: View? = null
    private var title: TextView? = null
    private var statusPill: TextView? = null
    private var body: LinearLayout? = null
    private var scroll: ScrollView? = null
    private val tabButtons = arrayOfNulls<TextView>(3)
    private var settingsPane: LinearLayout? = null
    private var opacityTag: TextView? = null
    private var widthTag: TextView? = null
    private var opacityLabel: TextView? = null
    private var widthLabel: TextView? = null
    private var autoSwitch: Switch? = null
    private var autoName: TextView? = null
    private var autoCaption: TextView? = null
    private var rematchSwitch: Switch? = null
    private var rematchName: TextView? = null
    private var rematchCaption: TextView? = null
    private var panelSwitch: Switch? = null
    private var panelName: TextView? = null
    private var panelCaption: TextView? = null
    private var appliedPanelEnabled: Boolean? = null
    /** Handle of the grab target inside the title row (drags the window). */
    private var dragHandle: View? = null
    /** Dot + title + pill group; also the header's drag target. */
    private var labelGroup: LinearLayout? = null

    /** Title-bar drag bookkeeping, shared by the grab handle and the ball. */
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
        ManagerLanguageSettings.readChoice(context).effective(ManagerSystemLanguage.current())
    }.getOrDefault(ManagerLanguage.SIMPLIFIED)

    /**
     * The overlay window itself.
     *
     * `FLAG_NOT_TOUCH_MODAL` keeps touches outside the bounds with the game, and nothing
     * clamps the *window* to the display, so the parked ball may legitimately sit at a
     * negative x (its half outside the screen is simply not drawn) and stays reachable by
     * the visible half. `FLAG_NOT_FOCUSABLE` keeps the IME and the game's own focus intact.
     */
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

    /** True while the expanded panel is the visible shape. */
    private fun panelOpen() = machine.shape == FloatingShape.PANEL

    fun update(value: JSONObject) {
        result = value
        renderHeader()
        if (panelOpen() && tab == 0) renderControl()
        if (panelOpen() && tab == 1 && !helpOpen) renderStatusPane()
        root?.post { clampPosition(); root?.let { runCatching { windows.updateViewLayout(it, layout) } } }
    }

    /**
     * Detaches the window and drops the idle callback. The *shape* is deliberately kept:
     * a close that is immediately followed by [show]/[configurationChanged] (rotation, a
     * language switch, the service rebuilding its overlay surface) must come back exactly
     * where it was — a half-tucked parked ball stays parked, an open panel stays open.
     */
    fun close() {
        cancelIdle()
        root?.let { runCatching { windows.removeView(it) } }
        root = null
        bubble = null
        bubbleDot = null
        bubbleCaption = null
        capsule = null
        capsuleDot = null
        capsuleText = null
        titleDot = null
        title = null
        statusPill = null
        body = null
        scroll = null
        tabButtons.fill(null)
        settingsPane = null
        opacityTag = null
        widthTag = null
        opacityLabel = null
        widthLabel = null
        autoSwitch = null
        autoName = null
        autoCaption = null
        rematchSwitch = null
        rematchName = null
        rematchCaption = null
        panelSwitch = null
        panelName = null
        panelCaption = null
        appliedPanelEnabled = null
        dragHandle = null
        labelGroup = null
    }

    fun configurationChanged() {
        // Re-render the *current* shape: the machine (and therefore the capsule → ball →
        // parked progress) is kept across a rotation, a resize or a language switch, and
        // the idle timer is re-armed by the rebuild rather than reset to the first step.
        rebuild()
    }

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

    /** One string as (Chinese, English, Japanese); the language picks, Traditional deriving
     * its copy from the Chinese text. */
    private fun t(zh: String, en: String = zh, ja: String = zh): String = language.tr(zh, en, ja)

    /** The exact string [UiLanguage.text] would return for a service-side caller. */
    private fun ui(zh: String, en: String, ja: String) = UiLanguage.text(zh, en, ja)

    /**
     * Adopts the manager's language and repaints the views that already exist.
     *
     * Driven by the service tick so the open window follows a switch without a restart,
     * and without a second timer. A collapsed window re-renders through the same paths
     * (the ball is rebuilt only when its dot colour has to change, which needs no
     * rebuild either); an open one re-renders the active pane, which is idempotent and
     * never fires a callback, so the 200 ms tick cannot toggle anything.
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
        if (root != null && panelOpen()) {
            showTab()
            applyRematch()
            applyPanel(RecommendationPanelSettings.read(context))
        }
        return true
    }

    // ---- shared helpers ------------------------------------------------------
    private fun dp(value: Int) = style.dp(value)
    private fun screenWidth() = context.resources.displayMetrics.widthPixels
    private fun screenHeight() = context.resources.displayMetrics.heightPixels
    private fun isDemo() = result.optString("status") == "demo"
    private fun statusIsGame() = result.optString("status") == "live" || isDemo()

    /** True while the assistant is doing something the user should see as "live". */
    private fun running() = auto.enabled && !isDemo()

    /**
     * The capsule's single line of live state: “助手运行中” while unattended runs, the
     * self-test wording while demo data is shown, and a plain standby/offline word
     * otherwise. Short by design — the capsule is only ~60 dp tall and lives on the edge
     * of the table.
     */
    private fun capsuleLabel() = when {
        isDemo() -> t("助手自检中", "Assistant self-test", "アシスタント自動テスト")
        auto.enabled -> t("助手运行中", "Assistant running", "アシスタント実行中")
        statusIsGame() -> t("助手待命", "Assistant standby", "アシスタント待機")
        else -> t("助手未连接", "Assistant offline", "アシスタント未接続")
    }

    /** Colour of any live-state dot (capsule, ball, header). */
    private fun liveDotColor() = if (running()) style.good else Color.rgb(96, 108, 128)

    private fun assistantTitle() = ui("雀魂助手", "Majsoul Assistant", "雀魂アシスタント")

    /** One short word for the header pill: the single most important state. */
    private fun pillText() = when {
        isDemo() -> t("自检中", "Self-test", "セルフテスト")
        auto.enabled -> t("运行中", "Running", "実行中")
        statusIsGame() -> t("待命", "Standby", "待機中")
        else -> t("未连接", "Offline", "未接続")
    }

    /** Longer subtitle used for the ball and the status rows. */
    private fun subCaption() = when {
        auto.enabled && isDemo() -> t("自检中，不会操作游戏", "Self-test — game untouched", "セルフテスト中・操作なし")
        auto.enabled -> t("无人值守运行中", "Unattended running", "無人運転中")
        statusIsGame() && isDemo() -> t("样例数据，仅供自检", "Sample data, self-test only", "サンプルデータ")
        statusIsGame() -> t("对局已同步，等待操作", "Synced, waiting for a turn", "同期済み・操作待ち")
        else -> t("等待牌局连接", "Waiting for a game", "対局接続待ち")
    }

    private fun onOff(checked: Boolean) = t(if (checked) "开" else "关", if (checked) "On" else "Off",
        if (checked) "オン" else "オフ")

    private fun rematchSummary() = when {
        !rematchState.enabled -> onOff(false)
        rematchState.active -> onOff(true)
        else -> t("待开启", "Armed", "待機")
    }

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

    /** Orange when the mode is live, soft orange when on but gated, slate when off. */
    private fun switchColors(toggle: Switch?, checked: Boolean, active: Boolean = checked) {
        if (toggle == null) return
        toggle.thumbTintList = ColorStateList.valueOf(if (checked) style.onAccent else Color.rgb(206, 214, 226))
        toggle.trackTintList = ColorStateList.valueOf(
            when {
                !checked -> style.trackOff
                active -> style.accent
                else -> Color.rgb(150, 118, 62)
            })
    }

    private fun seekColors(bar: SeekBar) {
        bar.thumbTintList = ColorStateList.valueOf(style.accent)
        bar.progressTintList = ColorStateList.valueOf(style.accent)
        bar.progressBackgroundTintList = ColorStateList.valueOf(style.trackOff)
    }

    /**
     * Small circular chrome button: collapse, close, info.
     *
     * The circle is drawn at [size] dp but the view itself is [FloatingStyle.touchTarget]
     * dp, centred, so the 44 dp touch target never depends on the icon size; callers that
     * put it in a row simply give it the same 44 dp box.
     */
    private fun chrome(glyph: String, size: Int = 32): TextView =
        style.text(glyph, 14f, style.inkSoft, true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(0, 0, 0, 0)
            minimumWidth = dp(style.touchTarget)
            minimumHeight = dp(style.touchTarget)
            background = style.chromeButton(size)
            layoutParams = LinearLayout.LayoutParams(dp(style.touchTarget), dp(style.touchTarget))
        }

    // ---- lifecycle -----------------------------------------------------------
    /**
     * (Re)attaches the window in the shape the machine currently holds.
     *
     * [close] first drops the attached view **and the idle callback**, so a rebuild can
     * never leave a stale `onIdleStep` posted against a detached root; the machine is the
     * one thing that is *not* reset, which is what makes a rotation or a language switch
     * keep the state (capsule / ball / parked / open panel) the user is in.
     */
    private fun rebuild() {
        close()
        dragging = false
        // Never let a throwing state supplier tear down the window rebuild itself.
        rematchState = runCatching { rematch() }.getOrNull() ?: rematchState
        val current = machine.shape
        val ball = current != FloatingShape.PANEL
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = if (ball) style.circle(style.bubbleColor(shapeOpacity()), style.panelStroke(), 1)
                else style.panelBackground(opacity, false)
            // A collapsed ball hangs on one side only: it needs a soft shadow to stay
            // legible over a bright board, and the expanded panel gets a much smaller one
            // so the dark card keeps its flat look.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                elevation = style.dp(if (ball) 3f else 8f).toFloat()
            }
        }
        when (current) {
            FloatingShape.PANEL -> buildPanel(panel)
            FloatingShape.CAPSULE -> buildCapsule(panel)
            FloatingShape.BUBBLE -> buildBubble(panel, parked = false)
            FloatingShape.PARKED -> buildBubble(panel, parked = true)
        }
        root = panel
        layout.width = when (current) {
            FloatingShape.PANEL -> dp(width).coerceAtMost(screenWidth() - dp(12)).coerceAtMost(maxPanelWidth())
            FloatingShape.CAPSULE -> WindowManager.LayoutParams.WRAP_CONTENT
            FloatingShape.BUBBLE, FloatingShape.PARKED -> dp(52)
        }
        // Every non-panel step is placed fresh: a capsule is never clipped, a full ball is
        // whole on the side it is closest to, and a parked ball is exactly half outside.
        snapIntoPlace(restore = current != FloatingShape.BUBBLE && current != FloatingShape.PARKED)
        windows.addView(panel, layout)
        // A WRAP_CONTENT capsule has no width before it is measured. A ball and a parked
        // ball must keep their placed x (posting a second snap pass would re-toggle the
        // half-hidden anchor), so only the panel and the capsule are re-clamped once the
        // real size is known.
        panel.post {
            if (root !== panel || machine.shape != current) return@post
            if (current == FloatingShape.PANEL || current == FloatingShape.CAPSULE)
                snapIntoPlace(restore = false)
            runCatching { windows.updateViewLayout(panel, layout) }
        }
        scheduleIdle()
    }

    /** Panel never covers the whole screen: 72 % of the screen height, hard cap 420 dp. */
    private fun maxPanelHeight() = (screenHeight() * 0.72f).roundToInt().coerceAtLeast(dp(180))
    private fun maxPanelWidth() = dp(420).coerceAtMost((screenWidth() * 0.92f).roundToInt())

    // ---- idle state machine --------------------------------------------------
    /** The persisted shape for a fresh window: collapsed starts as the status capsule. */
    private fun loadInitialShape(): FloatingShape =
        if (preferences.getBoolean("collapsed", true)) FloatingShape.CAPSULE else FloatingShape.PANEL

    /**
     * (Re)arms the single idle callback for the current shape.
     *
     * Exactly one runnable exists at any moment: [cancelIdle] always removes the previous
     * post before the next one is queued (and [close] — therefore every [rebuild] — removes
     * it as well), so a long session cannot accumulate callbacks against a detached view.
     * The expanded panel ([FloatingShape.PANEL]) deliberately posts nothing: opening the
     * settings never folds the panel away under the user's finger.
     */
    private fun scheduleIdle() {
        cancelIdle()
        val delay = machine.idleDelay ?: return
        if (root == null) return
        handler.postDelayed(idleStep, delay)
    }

    /** Drops the pending fold step; called from [close], on drag start and before re-arming. */
    private fun cancelIdle() {
        handler.removeCallbacks(idleStep)
    }

    /**
     * One fold step: capsule → full ball, then full ball → parked (half tucked, 65 %).
     *
     * The step is a no-op while the user is touching the window ([dragging]) or the window
     * is detached; whatever comes next re-arms the timer, so the fold never stalls.
     */
    private fun onIdleStep() {
        if (root == null || dragging) return
        if (!machine.wantsIdleTimer) return
        val before = machine.shape
        val after = machine.apply(FloatingAction.IDLE)
        if (after == before) return
        applyShapeChange()
    }

    /** One user action through the machine; re-snaps only when the shape really changed. */
    private fun dispatch(action: FloatingAction) {
        val before = machine.shape
        val after = machine.apply(action)
        if (after != before) applyShapeChange()
    }

    /**
     * Rebuilds for [FloatingShapeMachine.shape]. The anchor is preserved (a parked ball
     * wakes where it hangs, a capsule parks on the side it is closest to), and an opened
     * panel is placed fully on screen.
     */
    private fun applyShapeChange() {
        save()
        rebuild()
    }

    /** Opacity of the current collapsed shape: the parked ball keeps its own 65 %. */
    private fun shapeOpacity(): Int =
        if (machine.shape == FloatingShape.PARKED) PARKED_ALPHA_PERCENT else opacity

    // ---- collapsed shapes: ball + status capsule ------------------------------
    /**
     * The full ball ([FloatingShape.BUBBLE]) and the parked ball ([FloatingShape.PARKED]):
     * a 52 dp deep-navy disc with a gold rim, the “雀” mark and a teal live dot.
     *
     * Both shapes are the same widget; [parked] only changes the painted alpha (65 %
     * instead of the slider value). The window keeps its 52 dp size, a tap walks the
     * machine (ball → panel, parked → capsule → panel), a long press keeps the original
     * unattended toggle. A parked ball is half outside the screen, so its visible
     * remainder is still a full touch target — see [snapIntoPlace]/[clampPosition].
     */
    private fun buildBubble(panel: LinearLayout, parked: Boolean) {
        // The one circle is drawn by the window root itself; the inner ball only hosts the
        // "雀" mark and the dot, so no two opaque discs are stacked (they would double the
        // alpha and hide the 65 % parked look).
        val ball = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            background = null
            contentDescription = bubbleDescription(parked)
            isClickable = true
            isFocusable = true
        }
        val caption = style.text("雀", 17f, style.ink, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
        }
        bubbleCaption = caption
        ball.addView(caption, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        val dot = style.statusDot(running())
        bubbleDot = dot
        ball.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(2)
        })
        panel.addView(ball, LinearLayout.LayoutParams(dp(52), dp(52)))
        bubble = ball
        makeDraggable(ball, bubbleClick(), bubbleLongPress())
    }

    /**
     * The status capsule ([FloatingShape.CAPSULE]): a green live dot and one line of state
     * (“助手运行中”), no four-cell icon and no chrome buttons. It is what the panel folds
     * into, and what a tap on the parked ball brings back, so the live state stays readable
     * right before the panel is opened.
     */
    private fun buildCapsule(panel: LinearLayout) {
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, dp(14), 0)
            background = style.pill(style.bubbleColor(shapeOpacity()), dp(19).toFloat())
            contentDescription = capsuleDescription()
            isClickable = true
            isFocusable = true
            minimumHeight = dp(38)
        }
        val dot = style.statusDot(running())
        capsuleDot = dot
        pill.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(7) })
        val label = style.text(capsuleLabel(), 12f, style.ink, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            isSingleLine = true
        }
        capsuleText = label
        pill.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(38)))
        capsule = pill
        makeDraggable(pill, { dispatch(FloatingAction.TAP) }, bubbleLongPress())
    }

    private fun bubbleDescription(parked: Boolean = false): String = if (parked)
        t("雀魂助手已贴边收起，点击显示运行状态，再点开设置面板，长按切换无人值守，可拖动",
            "Majsoul Assistant is parked on the edge. Tap to show the live state, tap again to open settings, long-press toggles unattended, drag to move",
            "雀魂アシスタントは端に格納中。タップで状態表示、もう一度タップで設定を展開、長押しで無人運転切替、ドラッグで移動")
    else t(
        "雀魂助手，运行状态；点击展开面板，长按切换无人值守，可拖动",
        "Majsoul Assistant, live status. Tap to open, long-press to toggle unattended, drag to move",
        "雀魂アシスタント。タップで展開、長押しで無人運転切替、ドラッグで移動")

    private fun capsuleDescription() = t(
        "${capsuleLabel()}；点击打开设置面板，长按切换无人值守，可拖动",
        "${capsuleLabel()}. Tap to open settings, long-press toggles unattended, drag to move",
        "${capsuleLabel()}。タップで設定を展開、長押しで無人運転切替、ドラッグで移動")

    /**
     * Folds the open panel into the *status capsule* (not straight into a ball): the user
     * has just closed the settings, so the next thing they need is the live state, and the
     * capsule shows it without occupying the table.
     */
    private fun collapseToBall() {
        helpOpen = false
        dispatch(FloatingAction.COLLAPSE)
    }

    /**
     * One tap on the ball/capsule. The machine decides: the full ball opens the panel, a
     * parked ball only wakes to the capsule, the capsule opens the panel. Neither path
     * touches a persisted control.
     */
    private fun bubbleClick(): () -> Unit = {
        // An opened panel always starts on the control tab, fully on screen.
        if (machine.shape != FloatingShape.PANEL) {
            tab = 0
            helpOpen = false
        }
        dispatch(FloatingAction.TAP)
    }

    /** Long press on the ball/capsule: the same labelled toggle as the 无人值守 switch. */
    private fun bubbleLongPress(): () -> Boolean = {
        toggleAuto()
        renderHeader()
        // The controller answers on its next 200 ms tick; until then the restored panel
        // would show a stale switch, so it is repainted from the controller's state too.
        if (panelOpen()) renderState()
        else renderCapsule()
        true
    }

    // ---- expanded panel ------------------------------------------------------
    private fun buildPanel(panel: LinearLayout) {
        panel.addView(buildHeader())
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
            setPadding(dp(12), dp(2), dp(12), dp(12))
        }
        body = content
        scroller.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        showTab()
    }

    /**
     * Title row + tab bar.
     *
     * Row one: drag handle, status dot, "雀魂助手", a status pill, then `-` and `×` as
     * two 32 dp circles (inside a 44 dp touch area) on the same line.
     */
    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
        }
        // Row height is the touch-target floor: every control in it is 44 dp tall, the
        // icons themselves are drawn at 26 dp inside that box.
        val top = style.row(style.touchTarget).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                dp(style.touchTarget))
        }
        // No separate grip: it only duplicated the drag surface and ate 24 dp that the
        // title needs on a 300 dp wide panel. The title group (dot + name + pill) drags.
        val labelGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = 0
        }
        labelGroup.addView(style.statusDot(running()).apply {
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(6) })
        titleDot = labelGroup.getChildAt(0)
        // Weight 1 + minWidth 0: the title is the only elastic child, so it shrinks and
        // ellipsizes first and can never push the pill or the two buttons out of the row.
        // The title text is "雀魂助手" once the pill carries the state, so it is short in
        // every language and does not have to be shrunk to 8 sp on a narrow panel.
        val heading = style.text(assistantTitle(), 13.5f, style.ink, true).apply {
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            minimumWidth = 0
        }
        title = heading
        labelGroup.addView(heading, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = dp(4) })
        val pill = style.text(pillText(), 9.5f, style.onAccent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = style.pill(style.accent, dp(9).toFloat())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // Caps how much room the pill may take before the title has to give way, so a
            // long state word can never squeeze the title to nothing.
            maxWidth = dp(84)
        }
        statusPill = pill
        labelGroup.addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        // The label group itself is the shrinking column of the row.
        top.addView(labelGroup, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            .apply { rightMargin = dp(4) })
        this.labelGroup = labelGroup
        makeDraggable(labelGroup, click = { })

        val collapse = chrome("–", 26).apply {
            textSize = 16f
            contentDescription = t("收起成状态胶囊", "Collapse to the status capsule",
                "状態カプセルに折りたたむ")
            setOnClickListener { collapseToBall() }
        }
        // "×" keeps the semantic it had before this restyle: it folds the panel into the
        // ball, which always stays tappable. It must NOT tear the window down — the service
        // owns that lifecycle (`stop()`), and removing the only visible restore path here
        // would leave the user with no way back to the panel.
        val close = chrome("×", 26).apply {
            textSize = 16f
            contentDescription = t("关闭面板并收成状态胶囊", "Close the panel into the status capsule",
                "パネルを閉じて状態カプセルに折りたたむ")
            setOnClickListener { collapseToBall() }
        }
        // 44 dp containers with 26 dp circles inside: the visible chrome stays compact, the
        // touch target is the accessibility floor rather than the icon size.
        top.addView(collapse, LinearLayout.LayoutParams(dp(style.touchTarget), dp(style.touchTarget))
            .apply { rightMargin = dp(4) })
        top.addView(close, LinearLayout.LayoutParams(dp(style.touchTarget), dp(style.touchTarget)))
        header.addView(top)

        // One full-width tab bar; the selected third is a gold slab with dark ink.
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = style.tabTrackBar()
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val labels = arrayOf(
            t("控制", "Control", "操作"),
            t("状态", "Status", "状態"),
            t("设置", "Settings", "設定"),
        )
        for (index in 0..2) {
            val chip = style.text(labels[index], 12.5f, style.tabText, true).apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener {
                    tab = index
                    helpOpen = false
                    detailsOpen = false
                    dragging = false
                    showTab()
                }
            }
            tabButtons[index] = chip
            tabs.addView(chip, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                if (index > 0) leftMargin = dp(4)
            })
        }
        header.addView(tabs, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46))
            .apply { topMargin = dp(2) })
        styleTabs()
        return header
    }

    /** Selected tab = gold fill with dark ink; the others stay grey on the track. */
    private fun styleTabs() {
        for (index in 0..2) {
            val chip = tabButtons[index] ?: continue
            val selected = tab == index
            chip.background = style.tabBackground(selected)
            chip.setTextColor(if (selected) style.onAccent else style.tabText)
            chip.contentDescription = when (index) {
                0 -> if (selected) t("控制分区，已选中", "Control tab, selected", "操作タブ・選択中")
                    else t("控制分区", "Control tab", "操作タブ")
                1 -> if (selected) t("状态分区，已选中", "Status tab, selected", "状態タブ・選択中")
                    else t("状态分区", "Status tab", "状態タブ")
                else -> if (selected) t("设置分区，已选中", "Settings tab, selected", "設定タブ・選択中")
                    else t("设置分区", "Settings tab", "設定タブ")
            }
        }
    }

    private fun renderHeader() {
        bubbleCaption?.text = "雀"
        bubbleCaption?.setTextColor(style.ink)
        bubbleDot?.background = style.circle(liveDotColor())
        bubble?.contentDescription = bubbleDescription(machine.shape == FloatingShape.PARKED)
        renderCapsule()
        titleDot?.background = style.circle(liveDotColor())
        title?.text = assistantTitle()
        statusPill?.text = pillText()
        statusPill?.setBackgroundColor(Color.TRANSPARENT)
        statusPill?.background = style.pill(
            if (isDemo()) style.warn else if (auto.enabled) style.accent else style.trackOff,
            dp(9).toFloat())
        statusPill?.setTextColor(if (auto.enabled || isDemo()) style.onAccent else style.inkSoft)
        dragHandle?.contentDescription = t("拖动悬浮窗", "Drag the window", "ウィンドウを移動")
        // The label group (dot + title + pill) is the drag target inside the header; its
        // listener is bound once in [buildHeader] and a tick only has to repaint the copy.
        labelGroup?.contentDescription = t("拖动悬浮窗", "Drag the window", "ウィンドウを移動")
        if (panelOpen()) styleTabs()
    }

    /**
     * Repaints the collapsed status capsule in place. Called by the service tick, so the
     * capsule's dot and wording follow unattended mode / the connection without a rebuild.
     */
    private fun renderCapsule() {
        capsuleDot?.background = style.circle(liveDotColor())
        capsuleText?.text = capsuleLabel()
        capsule?.contentDescription = capsuleDescription()
    }

    /** Switches the visible pane. Idempotent so ticks can call it safely. */
    private fun showTab() {
        val scroller = scroll ?: return
        val content = body ?: return
        scroller.scrollTo(0, 0)
        content.removeAllViews()
        settingsPane = null
        if (helpOpen) {
            renderHelp(content)
            styleTabs()
            return
        }
        when (tab) {
            0 -> renderControl()
            1 -> renderStatusPane()
            else -> renderSettings()
        }
        styleTabs()
    }

    // ---- 控制 pane -----------------------------------------------------------
    private fun renderControl() {
        val content = body ?: return
        if (helpOpen) { showTab(); return }
        content.removeAllViews()
        content.addView(autoCard())
        content.addView(style.spacer(8))
        content.addView(rematchCard())
        content.addView(style.spacer(8))
        content.addView(recommendationPanelCard())
        content.addView(style.spacer(12))
        content.addView(stopButton())
    }

    /**
     * Unattended-mode switch. Tapping it calls [toggleAuto] only; its checked state,
     * tint and caption are read back from [auto] on every render, so the 200 ms tick is
     * the single authority and the row can never drift from the controller.
     *
     * The long explanation lives in the accessible help page ([renderHelp]); the row
     * itself carries exactly one caption line, as the panel is meant to be readable at
     * a glance while a game is running.
     */
    private fun autoCard(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = style.card(opacity)
            setPadding(dp(12), dp(6), dp(12), dp(10))
        }
        val row = style.row(44)
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = style.text(t("无人值守", "Unattended", "無人運転"), 14f, style.ink, true).apply {
            maxLines = 2
        }
        autoName = name
        val caption = style.text(autoCaptionText(), 11f, style.muted).apply {
            maxLines = 2
            setPadding(0, dp(2), 0, 0)
        }
        autoCaption = caption
        texts.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        texts.addView(caption, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = switchOn(auto.enabled, t("无人值守模式开关", "Unattended switch", "無人運転スイッチ"))
        autoSwitch = toggle
        toggle.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            // Only a user-driven change reaches here (restores write isChecked), and the
            // controller is the single owner: ask it to toggle, then re-read its state.
            if (checked != auto.enabled) toggleAuto()
            renderState()
        }
        row.addView(toggle, LinearLayout.LayoutParams(dp(52), dp(32)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        renderAutoCard()
        return card
    }

    private fun autoCaptionText(): String = t(
        "按 AI 首选自动出牌，可随时暂停；手动出牌会关闭此模式",
        "Plays the AI's first choice; pause any time. A manual discard turns it off",
        "AI の第一候補で自動打牌。いつでも停止可。手動打牌で解除")

    /** Single re-render entry point for both service ticks. */
    private fun renderState() {
        renderHeader()
        renderAutoCard()
        if (panelOpen() && tab == 0 && !helpOpen) {
            applyRematch()
            applyPanel(RecommendationPanelSettings.read(context))
        }
        if (panelOpen() && tab == 1 && !helpOpen) renderStatusPane()
    }

    private fun renderAutoCard() {
        val toggle = autoSwitch ?: return
        // Never write through setChecked: its listener would call toggleAuto() again.
        applyChecked(toggle, auto.enabled)
        toggle.refreshDrawableState()
        switchColors(autoSwitch, auto.enabled, auto.enabled && !isDemo())
        autoName?.text = t("无人值守", "Unattended", "無人運転")
        autoName?.setTextColor(if (auto.enabled) style.ink else style.inkSoft)
        autoSwitch?.contentDescription = t("无人值守模式开关", "Unattended switch", "無人運転スイッチ")
        autoCaption?.text = if (auto.message.isNotBlank() && auto.enabled)
            ManagerLanguageText.trim(auto.message, language) else autoCaptionText()
        autoCaption?.setTextColor(if (auto.enabled && !isDemo()) style.good else style.muted)
    }

    /**
     * The rematch row mirrors the original contract exactly: the persisted opt-in drives
     * the thumb, [rematchState.active] only changes the caption, and the write goes
     * through [setRematch] (preference only, never the service or unattended mode).
     *
     * The ⓘ opens the long explanation inside this window instead of showing it inline,
     * because the explanation is several sentences and would push the useful rows out of
     * view on a phone in portrait.
     */
    private fun rematchCard(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = style.card(opacity)
            setPadding(dp(12), dp(6), dp(12), dp(10))
        }
        val row = style.row(44)
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = style.text(t("自动下一局", "Auto next game", "自動次局"), 14f, style.ink, true).apply {
            maxLines = 2
        }
        rematchName = name
        val caption = style.text(rematchCaptionText(), 11f, style.muted).apply {
            maxLines = 2
            setPadding(0, dp(2), 0, 0)
        }
        rematchCaption = caption
        texts.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        texts.addView(caption, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val info = chrome(ui("ⓘ", "ⓘ", "ⓘ"), 26).apply {
            textSize = 15f
            setTextColor(style.muted)
            background = null
            contentDescription = t("自动下一局说明", "What auto next game does", "自動次局の説明")
            setOnClickListener {
                helpOpen = true
                showTab()
            }
        }
        row.addView(info, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(2) })
        val toggle = switchOn(rematchState.enabled,
            t("整场结束后自动匹配（实验性）", "Auto rematch after a game (experimental)", "終局後の自動再戦（試験的）"))
        rematchSwitch = toggle
        toggle.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean -> onRematchTap(checked) }
        row.addView(toggle, LinearLayout.LayoutParams(dp(52), dp(32)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        applyRematch()
        return card
    }

    private fun rematchCaptionText(): String = t(
        "整场结束后调用游戏的“下一局”；与无人值守独立",
        "Calls the game's own “Next game” after a match; independent of unattended",
        "終局後にゲームの「次局」を押す。無人運転とは独立")

    /**
     * 推荐与番点面板开关（点击穿透固定面板上的推荐 / 番点内容）。
     *
     * 它自成一体：只在用户点按时实时读写 [RecommendationPanelSettings]，从不启停
     * 服务、不改无人值守 / 自动下一局、不切换显示模式，也不影响猫头提示。与
     * [autoCard] / [rematchCard] 一样，`update` 重建本卡片时会重新读取已保存的值，
     * 因此管理器界面、游戏内控制与这里始终一致。推荐策略、牌上提示与
     * [RecommendationPanelOverlay] 的位置都不在这里改动。
     */
    private fun recommendationPanelCard(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = style.card(opacity)
            setPadding(dp(12), dp(6), dp(12), dp(10))
        }
        val row = style.row(44)
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = style.text(t("推荐与番点", "Hints & yaku", "候補と翻"), 14f, style.ink, true).apply {
            maxLines = 2
        }
        panelName = name
        val caption = style.text(panelCaptionText(), 11f, style.muted).apply {
            maxLines = 2
            setPadding(0, dp(2), 0, 0)
        }
        panelCaption = caption
        texts.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        texts.addView(caption, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val stored = runCatching { RecommendationPanelSettings.read(context) }.getOrDefault(true)
        val toggle = switchOn(stored, t("推荐与番点面板开关", "Hints & yaku panel switch", "候補と翻パネル"))
        panelSwitch = toggle
        appliedPanelEnabled = null
        toggle.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean -> onPanelTap(checked) }
        row.addView(toggle, LinearLayout.LayoutParams(dp(52), dp(32)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
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
        panelName?.text = t("推荐与番点", "Hints & yaku", "候補と翻")
        panelName?.setTextColor(if (enabled) style.ink else style.inkSoft)
        panelCaption?.text = panelCaptionText(enabled)
        panelCaption?.setTextColor(if (enabled) style.muted else style.faint)
        panelSwitch?.contentDescription = t("推荐与番点面板开关", "Hints & yaku panel switch", "候補と翻パネル")
        return true
    }

    private fun panelCaptionText(): String =
        panelCaptionText(runCatching { RecommendationPanelSettings.read(context) }.getOrDefault(true))

    private fun panelCaptionText(enabled: Boolean): String = if (enabled) t(
        "固定面板显示建议牌与番点，可随时在此关闭",
        "The fixed panel shows suggested tiles and yaku; you can hide it here any time",
        "固定パネルに候補牌と翻を表示。ここでいつでも非表示にできます")
    else t(
        "已隐藏建议牌与番点，其他功能不受影响",
        "Suggested tiles and yaku are hidden; everything else keeps working",
        "候補牌と翻は非表示。他の機能は影響を受けません")

    /** Sets the thumb without notifying the listener. */
    private fun applyChecked(toggle: Switch, checked: Boolean) {
        if (toggle.isChecked != checked) toggle.isChecked = checked
    }

    /** Keeps switch, caption and explanation in one place so they can never disagree. */
    private fun applyRematch() {
        rematchSwitch?.let { applyChecked(it, rematchState.enabled) }
        switchColors(rematchSwitch, rematchState.enabled, rematchState.active)
        rematchSwitch?.refreshDrawableState()
        rematchName?.text = t("自动下一局", "Auto next game", "自動次局")
        rematchName?.setTextColor(if (rematchState.enabled) style.ink else style.inkSoft)
        rematchCaption?.text = if (rematchState.enabled) rematchSummary() + " · " + rematchCaptionText()
            else t("整场结束后调用游戏“下一局”，默认关闭",
                "Calls the game's “Next game” after a match; off by default",
                "終局後にゲームの「次局」を押す。既定は無効")
        rematchCaption?.setTextColor(when {
            !rematchState.enabled -> style.muted
            rematchState.active -> style.good
            else -> style.warn
        })
        rematchSwitch?.contentDescription =
            t("整场结束后自动匹配（实验性）", "Auto rematch after a game (experimental)", "終局後の自動再戦（試験的）")
    }

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

    /**
     * The destructive action, outlined in red rather than filled: it has to be findable
     * without competing with the switches, and it cannot be removed — it and the ball's
     * long press are the two ways to stop the assistant.
     */
    private fun stopButton(): TextView = style.text(t("停止助手", "Stop assistant", "アシスト停止"), 13.5f,
        style.danger, true).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = t("停止助手：关闭无人值守并收起悬浮窗",
            "Stop the assistant: turns unattended off and collapses the panel",
            "アシスト停止：無人運転を解除しウィンドウを折りたたむ")
        background = style.dangerOutline()
        setOnClickListener { stop() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46))
    }

    // ---- in-window help page -------------------------------------------------
    /**
     * The long explanations, moved off the control rows into a page of this overlay.
     *
     * A `Dialog` from a Service context is the usual source of
     * `BadTokenException`/`WindowManager.BadTokenException` here: the service has no
     * window token and an unbounded, unattached dialog can outlive the overlay. Rebuilding
     * the same window's content needs neither a token nor a theme, keeps one touch surface
     * and is announced as ordinary content for TalkBack.
     */
    private fun renderHelp(parent: LinearLayout) {
        parent.addView(style.text(t("使用帮助", "Help", "使い方"), 14f, style.ink, true))
        parent.addView(style.spacer(6))
        parent.addView(helpBlock(t("无人值守", "Unattended", "無人運転"), t(
            "开启后，助手按 AI 的第一推荐自动出牌、碰杠并立直。开关随时可以关掉；只要手动打出一张牌，模式也会自动关闭。它只影响出牌操作，不会修改卡组、牌谱或账号数据。",
            "When it is on, the assistant plays the AI's first choice, calls pon/kan and riichi for you. Turn it off any time from its switch; a manual discard also switches it off. It only touches play actions — it never edits your deck, logs or account data.",
            "オンにすると、AI の第一候補で自動打牌し、ポン・カン・リーチも行います。スイッチでいつでも解除でき、手動で打牌すると自動的に解除されます。打牌操作のみで、デッキ・牌譜・アカウントは変更しません。")))
        parent.addView(helpBlock(t("自动下一局", "Auto next game", "自動次局"), t(
            "整场对局结束后，调用游戏自带的“下一局”按钮继续匹配，不会新建房间或伪造请求。它与无人值守彼此独立：关掉无人值守时，自动下一局仍保持你上次的选择。若模型自检中，不会触发自动匹配。",
            "After a match ends it presses the game's own “Next game” button to keep matching. It never creates a room or fabricates requests, and it is independent of unattended mode: turning unattended off keeps your last choice here. It does not fire while the model is self-testing.",
            "対局終了後、ゲーム標準の「次局」を押して再マッチします。独自の部屋作成やリクエスト偽装は行いません。無人運転とは独立しており、モデルのセルフテスト中は動作しません。")))
        parent.addView(helpBlock(t("推荐与番点", "Hints & yaku", "候補と翻"), t(
            "只控制点击穿透固定面板上显示的内容：建议牌、番点与防守参考。关掉后其他功能照常，猫头提示与顶部控制各自独立。",
            "Only controls the contents of the click-through panel: suggested tiles, yaku and the defence hint. Turning it off leaves everything else running; the cat hint and the top control are separate.",
            "クリック透過の固定パネルの内容（候補牌・翻・防守参考）だけを制御します。オフでも他の機能は動作し、猫のヒントと上部コントロールは独立しています。")))
        parent.addView(helpBlock(t("浮标与停止", "Ball & stopping", "ボールと停止"), t(
            "点“–”把面板收成状态胶囊（绿点 + 运行状态）；点胶囊打开面板。停留 3 秒，胶囊会变成完整圆球，再停 3 秒会吸附到最近的屏幕边缘并半露（约 65% 不透明）。点一下半露的球先恢复胶囊状态，再点一下才打开面板。拖动圆球会移动窗口，松手后回到完整圆球。长按可直接切换无人值守。“停止助手”会关闭无人值守并隐藏悬浮窗。",
            "“–” folds the panel into a status capsule (green dot + live state); tap it to open the panel. After 3 s the capsule becomes the full ball, after 3 s more the ball parks half-off the nearest edge at about 65 % opacity. A tap on the parked ball first shows the capsule again; the next tap opens the panel. Dragging moves the window and leaves the full ball behind. Long-press toggles unattended. “Stop assistant” turns unattended off and hides the window.",
            "「–」でパネルを状態カプセル（緑の点＋実行状態）に折りたたみ、タップで再展開します。3 秒でカプセルが通常のボールになり、さらに 3 秒で最寄りの端に半分収まります（約 65% 不透明）。半収まりのボールは、1 回目のタップで状態表示に戻り、もう一度タップで設定が開きます。ドラッグで移動し、離すと通常のボールに戻ります。長押しで無人運転を切替。「アシスト停止」は無人運転を解除しウィンドウを隠します。")))
        parent.addView(style.spacer(10))
        val back = style.text(t("返回控制", "Back to controls", "操作に戻る"), 13f, style.onAccent, true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = style.tinted(style.accent, style.capsuleRadius())
            setOnClickListener {
                helpOpen = false
                tab = 0
                showTab()
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46))
        }
        parent.addView(back)
    }

    private fun helpBlock(title: String, body: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = style.card(opacity)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(style.text(title, 12.5f, style.accent, true))
        addView(style.text(body, 11.5f, style.inkSoft).apply { maxLines = 12 }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    // ---- 状态 pane ------------------------------------------------------------
    /**
     * Compact facts first, verbose detail behind the expander.
     *
     * Each row is name · state dot · caption, so state is never conveyed by colour alone
     * (the caption says it in words). The recommendation block, defence hint, riichi
     * warning and timing keep exactly the data they had before; only the always-visible
     * part shrank, and "详细信息" brings the full explanation back.
     */
    private fun renderStatusPane() {
        val content = body ?: return
        if (helpOpen) { showTab(); return }
        content.removeAllViews()
        val status = result.optString("status")
        content.addView(factRow(ui("状态", "Status", "状態"), subCaption(),
            if (auto.enabled && !isDemo()) style.good else if (statusIsGame()) style.accent else style.muted,
            if (auto.enabled || statusIsGame()) style.good else null))
        content.addView(factRow(ui("连接", "Connection", "接続"),
            if (statusIsGame()) ui("对局同步", "In a game", "対局同期中")
                else ui("等待牌局", "Waiting", "対局待ち"),
            if (statusIsGame()) style.inkSoft else style.muted,
            if (statusIsGame()) style.accent else null))
        content.addView(factRow(ui("模式", "Mode", "モード"), onOff(auto.enabled),
            style.inkSoft, if (auto.enabled) style.good else null))
        content.addView(factRow(ui("续局", "Next game", "次局"), rematchSummary(),
            style.inkSoft, if (rematchState.active) style.accent else null))
        content.addView(style.spacer(8))
        content.addView(detailsExpander())
        if (!detailsOpen) return
        content.addView(style.spacer(6))
        renderDetails(content)
    }

    /** One "name · dot · caption" line; the dot is decorative, the caption is the truth. */
    private fun factRow(name: String, value: String, valueColor: Int, dotColor: Int?): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(6), dp(2), dp(6))
            minimumHeight = dp(style.touchTarget)
            // Name column is capped so a long English/Japanese label wraps instead of
            // squeezing the value out of the row.
            addView(style.text(name, 11.5f, style.muted, true).apply { maxLines = 2 },
                LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))
            if (dotColor != null) addView(View(context).apply {
                background = style.circle(dotColor)
            }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(6) })
            addView(style.text(value, 12.5f, valueColor).apply { maxLines = 2 },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun detailsExpander() = style.text(
        t("详细信息", "Details", "詳細"), 12.5f, style.accent, true).apply {
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(dp(10), 0, dp(10), 0)
        background = style.tinted(style.accentSoft, style.capsuleRadius())
        contentDescription = if (detailsOpen) t("收起详细信息", "Hide details", "詳細を閉じる")
            else t("展开详细信息", "Show details", "詳細を開く")
        setOnClickListener {
            detailsOpen = !detailsOpen
            renderStatusPane()
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
    }

    private fun renderDetails(parent: LinearLayout) {
        val status = result.optString("status")
        // A status line the manager produced is translated when it is a known phrase;
        // an engine/unknown diagnostic is shown exactly as received.
        val message = ManagerLanguageText.trim(
            result.optString("message", "等待牌局").ifBlank { "等待牌局" }, language)
        parent.addView(style.text(message, 11.5f, style.inkSoft).apply { maxLines = 4 })
        if (status != "live" && status != "demo") {
            parent.addView(style.text(t(
                "先开启助手，再启动雀魂进入对局。中途开启或重连后，需等待完整牌局同步。",
                "Turn the assistant on, then start Majsoul and enter a game. If you enable it mid-game or after a reconnect, wait for a full hand sync.",
                "アシストを起動してから雀魂で対局に入ってください。途中で起動・再接続した場合は完全な同期を待つ必要があります。"),
                11f, style.faint).apply {
                maxLines = 4
                setPadding(0, dp(6), 0, 0)
            })
            return
        }
        parent.addView(style.text(
            localizeRound(result.optString("round"), result.optInt("players", 4)),
            11f, style.muted).apply { setPadding(0, dp(4), 0, 0) })
        val analysis = result.optJSONObject("analysis") ?: return
        if (result.optBoolean("modelExperimental")) parent.addView(style.text(t(
            "实验性 Mortal v4 · 特征含近似计算",
            "Experimental Mortal v4 · approximate features",
            "実験的 Mortal v4・近似特徴"), 10f, style.faint).apply { setPadding(0, dp(4), 0, 0) })
        parent.addView(style.spacer(6))
        parent.addView(recommendationHeader())
        val rows = result.optJSONArray("recommendations") ?: JSONArray()
        if (rows.length() == 0) {
            parent.addView(style.text(t("等待操作机会", "Waiting for your turn", "操作機会を待機中"),
                16f, style.accent, true).apply {
                setPadding(dp(2), dp(6), dp(2), dp(2))
            })
            analysis.optJSONObject("hand13")?.let {
                parent.addView(style.text(handDescription(it), 11f, style.muted).apply {
                    setPadding(dp(2), 0, dp(2), 0)
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
                    setPadding(dp(6), 0, dp(2), dp(2))
                })
                else {
                    val chance = if (hand?.optInt("shanten") == 0) t(
                        "估计和率 ${number(hand.optDouble("avg_agari_rate"))}%",
                        "Win est. ${number(hand.optDouble("avg_agari_rate"))}%",
                        "和了率 推定 ${number(hand.optDouble("avg_agari_rate"))}%")
                        else t("${hand?.optInt("shanten") ?: "—"}向听",
                        "${hand?.optInt("shanten") ?: "—"} shanten",
                        "${hand?.optInt("shanten") ?: "—"} 向聴")
                    parent.addView(style.text("$chance · " +
                        t("风险 $risk", "Risk $risk", "リスク $risk"), 10.5f, style.muted).apply {
                        setPadding(dp(6), 0, dp(2), dp(2))
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
                "${t("🛡️ 防守参考", "🛡️ Defence hint", "🛡️ 防守参考")}  ${tileName(defence)}",
                11f, style.inkSoft).apply { setPadding(0, dp(2), 0, 0) })
        }
        val opponents = analysis.optJSONArray("opponents") ?: JSONArray()
        val riichiSeats = (0 until opponents.length()).map { opponents.getJSONObject(it) }
            .filter { it.optBoolean("is_riichi") }
            .map { relativeSeat(it.optInt("seat"), result.optInt("seat"), result.optInt("players", 4)) }
        if (riichiSeats.isNotEmpty()) parent.addView(style.text(
            t("⚠️ 已立直：", "⚠️ Riichi: ", "⚠️ リーチ：") + riichiSeats.joinToString(seatJoiner()),
            11f, style.warn))
        parent.addView(style.text(t(
            "和牌率为听牌后的估计；风险指数越低越好，含打点权重，并非实际放铳概率。",
            "Win rates are estimates after tenpai; a lower risk index is better. It includes value weights and is not a real deal-in probability.",
            "和了率は聴牌後の推定値。リスク指数は低いほど良く、打点の重みを含みますが実際の放銃率ではありません。"),
            9.5f, style.faint).apply { setPadding(0, dp(6), 0, 0) })
        val duration = if (status == "demo") result.optLong("loadAndInferenceMs") else result.optLong("elapsedMs")
        parent.addView(style.text(
            t("手机本地计算 · ", "On-device · ", "端末内計算・") + "$duration ms" +
                (if (status == "demo") t("（含模型加载）", " (incl. model load)", "（モデル読込含む）") else ""),
            9.5f, style.faint).apply { setPadding(0, dp(2), 0, 0) })
    }

    private fun recommendationHeader() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(4), dp(2), dp(4))
        addView(style.text(t("🎯 推荐切牌", "🎯 Suggested discard", "🎯 打牌候補"), 12f, style.accent, true))
        addView(style.spacer(4))
    }

    /**
     * One recommendation line. Rank 1 is a gold pill with dark ink, rank 2 a faint gold
     * pill and rank 3 a plain line, so the ordering reads at a glance without
     * colour-only cues (the rank is also written as a number).
     */
    private fun recommendationRow(name: String, chance: String, rank: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        minimumHeight = dp(style.touchTarget)
        val filled = rank == 0
        val tinted = rank == 1
        background = when (rank) {
            0 -> style.pill(style.accent, dp(12).toFloat())
            1 -> style.pill(style.accentSoft, dp(12).toFloat())
            else -> style.pill(Color.TRANSPARENT, dp(12).toFloat())
        }
        addView(style.text(if (rank == 0) t("首选", "Best", "第一候補") else "${rank + 1}", 10f,
            if (filled) style.onAccent else style.accent, true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(38), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        addView(style.text(name, 14f, if (filled) style.onAccent else style.ink, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (chance.isNotBlank()) addView(style.text(chance, 12f,
            if (filled) style.onAccent else if (tinted) style.accent else style.inkSoft, true).apply {
            gravity = Gravity.END
            maxLines = 1
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
    }

    // ---- 设置 pane ------------------------------------------------------------
    private fun renderSettings() {
        val content = body ?: return
        if (helpOpen) { showTab(); return }
        content.removeAllViews()
        if (settingsPane == null) {
            settingsPane = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            content.addView(settingsPane)
        }
        val pane = settingsPane ?: return
        pane.removeAllViews()
        pane.addView(style.section(t("窗口外观", "Window", "ウィンドウ")))
        addSlider(pane, t("背景不透明度", "Panel opacity", "背景の不透明度"), 25, 100,
            opacity, { "${it}%" }, live = true) { value ->
            opacity = value
            root?.background = if (panelOpen()) style.panelBackground(opacity, false)
                else style.circle(style.bubbleColor(shapeOpacity()), style.panelStroke(), 1)
            save()
        }.also { opacityTag = it }
        addSlider(pane, t("窗口宽度", "Window width", "ウィンドウ幅"), 220, 420,
            width, { "$it dp" }, live = false) { value ->
            width = value
            layout.width = dp(width).coerceAtMost(screenWidth() - dp(12)).coerceAtMost(maxPanelWidth())
            clampPosition()
            root?.let { panel -> windows.updateViewLayout(panel, layout) }
            save()
        }.also { widthTag = it }
        pane.addView(style.divider())
        pane.addView(style.section(t("助手操作", "Assistant", "アシスト")))
        pane.addView(style.text(t(
            "拖动标题移动窗口；点“–”或“×”收起成状态胶囊，点胶囊打开面板；停留 3 秒变完整圆球，再 3 秒贴边半露，点一下显示状态、再点打开面板。停止助手会关闭无人值守并隐藏悬浮窗。",
            "Drag the title to move the window; “–” or “×” folds it into the status capsule, tapping the capsule opens the panel. After 3 s it becomes the full ball, 3 s later it parks on the nearest edge; one tap shows the state, the next opens the panel. Stopping the assistant turns unattended off and hides the window.",
            "タイトルをドラッグして移動。「–」または「×」で状態カプセルに折りたたみ、タップで展開します。3 秒で通常のボール、さらに 3 秒で端に半分収まります。1 回目のタップで状態、もう一度で設定が開きます。アシスト停止は無人運転を解除しウィンドウを隠します。"),
            10.5f, style.faint).apply { maxLines = 5; setPadding(dp(2), 0, dp(2), dp(8)) })
        pane.addView(stopButton())
    }

    /**
     * Caption row (name + gold value tag) above the slider.
     *
     * The tag, not the caption, carries the number: the caption keeps the label so a
     * long translated label wraps on two lines instead of pushing the value off the
     * right edge. Returns the value tag so a language switch can re-apply the copy.
     */
    private fun addSlider(parent: LinearLayout, name: String, min: Int, max: Int, value: Int,
        format: (Int) -> String, live: Boolean = true,
        change: (Int) -> Unit): TextView {
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(style.touchTarget)
        }
        val caption = style.text(name, 12f, style.inkSoft).apply {
            maxLines = 2
            isSingleLine = false
        }
        head.addView(caption, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val tag = style.text(format(value), 12f, style.accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = style.valueTag()
        }
        head.addView(tag, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        parent.addView(head)
        val bar = SeekBar(context).apply {
            this.max = max - min
            progress = value - min
            contentDescription = name
            setPadding(0, 0, 0, 0)
            minimumHeight = dp(style.touchTarget)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    tag.text = format(progress + min)
                    if (live) change(progress + min)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (!live && seekBar != null) {
                        tag.text = format(seekBar.progress + min)
                        change(seekBar.progress + min)
                    }
                }
            })
        }
        seekColors(bar)
        parent.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)))
        return tag
    }

    // ---- drag / snapping ------------------------------------------------------
    /**
     * Drag support shared by the ball and the panel title. Movement only starts past
     * [ViewConfiguration.getScaledTouchSlop], and a press that moved never counts as a
     * click, so dragging the panel cannot select a tab, unfold the ball or toggle a mode
     * by accident. The manager's touch area stays inside this window: every view here is
     * a child of the single overlay view, and the window itself is FLAG_NOT_TOUCH_MODAL,
     * so touches outside its bounds reach the game unchanged.
     */
    private fun makeDraggable(view: View, click: () -> Unit, longPress: (() -> Boolean)? = null) {
        // `longPress` is optional: only the ball has one, the title row does not.
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var moved = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        view.setOnClickListener { click() }
        view.setOnLongClickListener { longPress?.invoke() ?: false }
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
                        // The idle fold must never fight the finger.
                        if (!dragging) { dragging = true; cancelIdle() }
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
                    if (wasMoved) finishDrag() else target.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    moved = false
                    dragging = false
                    finishDrag()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * End of a drag. The window is dropped where the finger left it: a collapsed shape is
     * shown as the *full* ball (never re-parked under the finger) and the idle timer
     * restarts from zero, so “drag, release, wait 3 s + 3 s” re-parks it naturally.
     */
    private fun finishDrag() {
        if (root == null) return
        if (machine.shape != FloatingShape.PANEL) {
            dispatch(FloatingAction.DRAG)
        } else {
            save()
            runCatching { windows.updateViewLayout(root ?: return, layout) }
        }
    }

    /**
     * Places the window for the current shape.
     *
     *  - [FloatingShape.PANEL], [FloatingShape.BUBBLE] and an appearing
     *    [FloatingShape.CAPSULE] are always whole on screen ([restore] marks the shapes
     *    that are placed fresh rather than re-snapped).
     *  - [FloatingShape.PARKED] is the only shape allowed a negative x: exactly half of the
     *    52 dp ball sits outside the screen. The window already carries
     *    `FLAG_NOT_TOUCH_MODAL` + `TYPE_APPLICATION_OVERLAY`, so it may legally be placed at
     *    a negative x, and the remaining half stays a touch target that wakes it back.
     *
     * The parked side is decided from the current centre, and every path is clamped so a
     * shape can be reached again from any previous position (a rotation can never strand
     * it outside the display).
     */
    private fun snapIntoPlace(restore: Boolean) {
        val display = context.resources.displayMetrics
        val widthPx = layout.width.takeIf { it > 0 } ?: defaultWidthPx()
        val heightPx = root?.height?.takeIf { it > 0 } ?: defaultHeightPx()
        layout.y = layout.y.coerceIn(0, (display.heightPixels - heightPx - dp(32)).coerceAtLeast(0))
        val right = layout.x + widthPx / 2 >= display.widthPixels / 2
        // The drawable itself carries the opacity (an alpha multiplier on the fill), so the
        // window-level alpha stays 1: a folded shape must never be a second, invisible
        // surface on top of the real one.
        root?.alpha = 1f
        val maxX = (display.widthPixels - widthPx).coerceAtLeast(0)
        layout.x = when (machine.shape) {
            FloatingShape.PANEL -> layout.x.coerceIn(0, maxX)
            FloatingShape.CAPSULE -> if (restore) (if (right) maxX else 0)
                else layout.x.coerceIn(0, maxX)
            FloatingShape.BUBBLE -> if (right) maxX else 0
            FloatingShape.PARKED -> if (right) display.widthPixels - widthPx / 2
                else widthPx / 2 - widthPx
        }
        save()
    }

    /** Explicit width used when the window has not been laid out yet. */
    private fun defaultWidthPx(): Int = when (machine.shape) {
        FloatingShape.PANEL -> dp(width)
        FloatingShape.CAPSULE -> dp(96)   // ~WRAP_CONTENT: clamped only, never snapped
        FloatingShape.BUBBLE, FloatingShape.PARKED -> dp(52)
    }

    private fun defaultHeightPx(): Int = when (machine.shape) {
        FloatingShape.PANEL -> dp(200)
        FloatingShape.CAPSULE -> dp(38)
        FloatingShape.BUBBLE, FloatingShape.PARKED -> dp(52)
    }

    /**
     * Keeps the window reachable while it is *being* dragged: the saved anchor survives
     * rotation and density changes in the preferences, but is always clamped to the current
     * display, so a window dragged to an edge in portrait cannot land off-screen in
     * landscape. The drag loop and [snapIntoPlace] use the same bounds, so a parked ball can
     * always be dragged back out: at most half of a collapsed shape may sit outside.
     */
    private fun clampPosition() {
        val display = context.resources.displayMetrics
        val widthPx = layout.width.takeIf { it > 0 } ?: defaultWidthPx()
        val height = root?.height?.takeIf { it > 0 } ?: defaultHeightPx()
        val panel = machine.shape == FloatingShape.PANEL
        val minX = if (panel) 0 else -widthPx / 2
        val maxX = if (panel) display.widthPixels - widthPx else display.widthPixels - widthPx / 2
        layout.x = layout.x.coerceIn(minX, maxX.coerceAtLeast(minX))
        layout.y = layout.y.coerceIn(0, (display.heightPixels - height - dp(32)).coerceAtLeast(0))
    }

    /**
     * Persists the anchor plus the collapsed flag of older builds (so a downgrade still
     * folds). The live shape itself is not persisted: it is owned by [machine] and
     * re-derived by [loadInitialShape].
     */
    private fun save() {
        preferences.edit().putInt("x", layout.x).putInt("y", layout.y).putInt("opacity", opacity)
            .putInt("width", width)
            .putBoolean("collapsed", machine.shape != FloatingShape.PANEL).apply()
    }

    private fun discardResult(analysis: JSONObject, tile: String): JSONObject? {
        val hand = analysis.optJSONObject("hand14") ?: return null
        for (group in listOf("maintain", "backwards")) {
            val choices = hand.optJSONArray(group) ?: continue
            for (i in 0 until choices.length()) {
                val candidate = choices.getJSONObject(i)
                if (candidate.optString("discard").removeSuffix("r") == tile.removeSuffix("r"))
                    return candidate.optJSONObject("result")
            }
        }
        return null
    }

    private fun handDescription(hand: JSONObject): String {
        val shanten = hand.optInt("shanten", 99)
        val stage = when {
            shanten == 0 -> t("听牌", "Tenpai", "聴牌")
            shanten < 0 -> t("和牌形", "Winning shape", "和了形")
            else -> t("${shanten}向听", "${shanten} shanten", "${shanten} 向聴")
        }
        val chance = if (shanten == 0) t(
            "估计和牌率 ${number(hand.optDouble("avg_agari_rate"))}%",
            "Win rate est. ${number(hand.optDouble("avg_agari_rate"))}%",
            "和了率 推定 ${number(hand.optDouble("avg_agari_rate"))}%")
            else t("和牌率 —（未听牌）", "Win rate — (not tenpai)", "和了率 —（未聴牌）")
        val waits = t("进张 ${hand.optInt("waits_total")} 枚", "Draws ${hand.optInt("waits_total")}",
            "有効牌 ${hand.optInt("waits_total")} 枚")
        val furiten = if (hand.optBoolean("is_furiten")) " · " + t("振听", "Furiten", "振聴") else ""
        return "$stage · $waits\n$chance$furiten"
    }

    private fun mainDetail(hand: JSONObject?, risk: String): String {
        if (hand == null) return t("放铳风险指数 $risk", "Deal-in risk index $risk", "放銃リスク指数 $risk")
        val shanten = hand.optInt("shanten", 99)
        val stage = when {
            shanten == 0 -> t("听牌", "Tenpai", "聴牌")
            shanten < 0 -> t("和牌形", "Winning shape", "和了形")
            else -> t("${shanten}向听", "${shanten} shanten", "${shanten} 向聴")
        }
        val waits = t("进张 ${hand.optInt("waits_total")} 枚", "Draws ${hand.optInt("waits_total")}",
            "有効牌 ${hand.optInt("waits_total")} 枚")
        val rate = if (shanten == 0) t(
            "和率 ${number(hand.optDouble("avg_agari_rate"))}%",
            "Win ${number(hand.optDouble("avg_agari_rate"))}%",
            "和了 ${number(hand.optDouble("avg_agari_rate"))}%") else null
        val furiten = if (hand.optBoolean("is_furiten")) t("振听", "Furiten", "振聴") else null
        return listOfNotNull(stage, waits, rate, furiten, t("风险 $risk", "Risk $risk", "リスク $risk"))
            .joinToString(" · ")
    }

    private fun number(value: Double) = if (value.isFinite()) String.format(Locale.ROOT, "%.1f", value) else "—"

    private fun relativeSeat(seat: Int, own: Int, players: Int): String = when ((seat - own + players) % players) {
        1 -> t("下家", "right", "下家")
        2 -> if (players == 3) t("上家", "left", "上家") else t("对家", "across", "対面")
        else -> t("上家", "left", "上家")
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
        return when (kind) {
            "discard" -> t("切 $face", "Discard $face", "打 $face")
            "riichi" -> if (blank) t("立直", "Riichi", "リーチ")
                else t("立直 · 切 $face", "Riichi · discard $face", "リーチ・打 $face")
            "pon" -> t("碰 $face", "Pon $face", "ポン $face")
            "chi" -> t("吃 $face", "Chi $face", "チー $face")
            "kan", "ankan", "kakan" -> t("杠 $face", "Kan $face", "カン $face")
            "hora" -> t("和牌", "Win", "和了")
            "kita" -> t("拔北", "Kita", "北抜き")
            "abort" -> t("九种九牌流局", "Nine terminals draw", "九種九牌流局")
            else -> t("跳过", "Pass", "パス")
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
        val text = when (language) {
            ManagerLanguage.SIMPLIFIED ->
                round.replace("E", "东").replace("S", "南").replace("W", "西").replace("N", "北")
            // Same seat letters as Simplified, spelled the Traditional way; the round number that
            // follows them is copied exactly as received.
            ManagerLanguage.TRADITIONAL ->
                round.replace("E", "東").replace("S", "南").replace("W", "西").replace("N", "北")
            ManagerLanguage.ENGLISH -> round
            ManagerLanguage.JAPANESE ->
                round.replace("E", "東").replace("S", "南").replace("W", "西").replace("N", "北")
        }
        return text + " · " + t("$players 人", "$players players", "$players 人")
    }

    private fun tileName(tile: String): String {
        val honors = mapOf("E" to 0, "S" to 1, "W" to 2, "N" to 3, "P" to 4, "F" to 5, "C" to 6)
        honors[tile]?.let { return honorName(it, tile) }
        if (tile.length < 2) return "—"
        val rank = tile[0]
        val suit = when (tile[1]) { 'm' -> 0; 'p' -> 1; 's' -> 2; else -> return "—" }
        val red = tile.endsWith("r")
        return (if (red) t("赤", "Red ", "赤") else "") + suitName(suit, rank)
    }

    private fun suitName(suit: Int, rank: Char): String = when (language) {
        ManagerLanguage.SIMPLIFIED -> rank + when (suit) { 0 -> "万"; 1 -> "筒"; else -> "索" }
        // The rank character is data and stays as-is; only the suit word is Traditional.
        ManagerLanguage.TRADITIONAL -> rank + when (suit) { 0 -> "萬"; 1 -> "筒"; else -> "索" }
        ManagerLanguage.ENGLISH -> rank + when (suit) { 0 -> "m"; 1 -> "p"; else -> "s" }
        ManagerLanguage.JAPANESE -> "$rank" + when (suit) { 0 -> "萬"; 1 -> "筒"; else -> "索" } + "子"
    }

    private fun honorName(index: Int, tile: String): String = when (language) {
        ManagerLanguage.SIMPLIFIED ->
            listOf("东", "南", "西", "北", "白", "发", "中")[index]
        ManagerLanguage.TRADITIONAL ->
            listOf("東", "南", "西", "北", "白", "發", "中")[index]
        ManagerLanguage.ENGLISH -> tile
        ManagerLanguage.JAPANESE ->
            listOf("東", "南", "西", "北", "白", "發", "中")[index]
    }

    private fun tileIndex(tile: String): Int {
        val honor = listOf("E", "S", "W", "N", "P", "F", "C").indexOf(tile)
        if (honor >= 0) return 27 + honor
        if (tile.length < 2) return -1
        val rank = tile[0].digitToIntOrNull()?.takeIf { it in 1..9 } ?: return -1
        return when (tile[1]) { 'm' -> rank - 1; 'p' -> rank + 8; 's' -> rank + 17; else -> -1 }
    }
}
