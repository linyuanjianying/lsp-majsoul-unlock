package com.yefeng.majmax.hookprobe

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import com.yefeng.majmax.hookprobe.helper.GameMenuText
import com.yefeng.majmax.hookprobe.manager.ManagerLanguage
import com.yefeng.majmax.hookprobe.manager.ManagerLanguageSettings
import com.yefeng.majmax.hookprobe.manager.ManagerSystemLanguage
import com.yefeng.majmax.hookprobe.manager.RecommendationPanelOverlay
import com.yefeng.majmax.hookprobe.manager.TileBadgeOverlay
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only tile hints in the game window; no system overlay or touch interception.
 *
 * This layer only exists when the assistant runs in Hook mode: [GameTopControl] constructs it
 * right before attaching, and only after the provider confirmed that mode. In overlay mode it
 * is never created at all, so there is no "hidden but built" state to keep in sync.
 *
 * [presentationAllowed] remains the cheap gate for the moment before the first answer is
 * known; it is not a lease and it never switches modes. The provider already reports
 * `overlayMode` / `runMode` for the authoritative answer, so the layer clears itself when it
 * sees overlay mode instead of re-deriving the decision locally.
 *
 * Language: the recommendation window follows the manager's UI language, carried by the
 * `uiLanguage` field of the same `hintSnapshot` this tick already reads — no extra provider
 * call and no extra timer. The tile badges are intentionally left alone: their content is
 * drawn on the tiles and must read exactly like the game's own tiles.
 */
class GameHintLayer {
    private val main = Handler(Looper.getMainLooper())
    private val uri = Uri.parse("content://com.yefeng.majmax.hookprobe.ai/capture")
    private val busy = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "majmax-game-hints").apply { isDaemon = true }
    }
    private var host: ViewGroup? = null
    private var badge: TileBadgeOverlay? = null
    private var panel: RecommendationPanelOverlay? = null
    private var generation = 0
    private var presentationEnabled = true
    private var previousPanelResult: String? = null
    private var previousPanelEnabled = true
    private var previousResult = ""
    private var previousCoords = ""
    private var previousButtons = ""
    private var previousSource = ""
    private var previousButtonSource = ""

    /**
     * Language the recommendation window is rendered in.
     *
     * Seeded from the manager's own preference when this layer is created (the panel is
     * created in the game process, so this is only a first guess), then corrected by the
     * `uiLanguage` field of every snapshot. A snapshot without the field — an older
     * manager, or a reply that failed — leaves it alone instead of resetting it.
     */
    private var language: ManagerLanguage = ManagerLanguage.SIMPLIFIED
    private var languageKnown = false
    private var previousLanguageTag = ""

    private val tick = object : Runnable {
        override fun run() {
            val container = host ?: return
            val token = generation
            val app = container.context.applicationContext
            if (busy.compareAndSet(false, true)) {
                io.execute {
                    val snapshot = runCatching {
                        app.contentResolver.call(uri, "hintSnapshot", null, null)
                    }.getOrNull()
                    main.post {
                        busy.set(false)
                        if (token != generation || host !== container) return@post
                        // The provider reports "not running" and `overlayMode = true` for a
                        // service that runs in overlay mode; either one clears the hints.
                        if (!presentationEnabled || !allowed() || snapshot?.getBoolean("overlayMode", false) == true) {
                            clearSurfaces()
                            return@post
                        }
                        if (snapshot != null && snapshot.getString("runMode") == "overlay") {
                            clearSurfaces()
                            return@post
                        }
                        if (snapshot == null) {
                            panel?.update(null, false)
                            previousPanelResult = null
                            // The provider call itself failed (e.g. SecurityException).
                            // A null snapshot is otherwise indistinguishable from
                            // "not running" downstream, so surface it explicitly.
                            badge?.update(JSONObject().put("status", "waiting"))
                            previousResult = ""
                            previousCoords = ""
                            previousButtons = ""
                            return@post
                        }
                        // Language first: a change must repaint the panel even when the
                        // recommendation below is byte-identical to the last one.
                        val tag = snapshot.getString("uiLanguage").orEmpty()
                        val languageChanged = syncLanguage(tag)
                        if (snapshot.getBoolean("running") != true) {
                            panel?.update(null, false)
                            previousPanelResult = null
                            badge?.update(JSONObject().put("status", "waiting"))
                            previousResult = ""
                            previousCoords = ""
                            previousButtons = ""
                        } else {
                            val result = snapshot.getString("result").orEmpty()
                            val panelEnabled = snapshot.getBoolean("recommendationPanel", true)
                            val parsedResult by lazy { runCatching { JSONObject(result) }.getOrNull() }
                            if (result != previousPanelResult || panelEnabled != previousPanelEnabled || languageChanged) {
                                panel?.update(parsedResult, panelEnabled)
                                previousPanelResult = result
                                previousPanelEnabled = panelEnabled
                            }
                            val coords = snapshot.getString("coords").orEmpty()
                            val buttons = snapshot.getString("buttons").orEmpty()
                            val sourceW = snapshot.getInt("sourceW")
                            val sourceH = snapshot.getInt("sourceH")
                            val buttonSourceW = snapshot.getInt("buttonSourceW")
                            val buttonSourceH = snapshot.getInt("buttonSourceH")
                            val source = "${sourceW}x${sourceH}"
                            val buttonSource = "${buttonSourceW}x${buttonSourceH}"
                            // Tile badges: unchanged behaviour, same inputs, no language.
                            if (result.isNotEmpty() && result != previousResult) {
                                val parsed = parsedResult
                                if (parsed != null) {
                                    runCatching { badge?.update(parsed) }.onSuccess {
                                        previousResult = result
                                    }
                                }
                            }
                            if (coords.isNotEmpty() && (coords != previousCoords || source != previousSource)) {
                                val parsed = runCatching { JSONArray(coords) }.getOrNull()
                                if (parsed != null) {
                                    runCatching { badge?.updateHookedCoords(parsed, sourceW, sourceH) }.onSuccess {
                                        previousCoords = coords
                                        previousSource = source
                                    }
                                }
                            }
                            if (buttons.isNotEmpty() && (buttons != previousButtons || buttonSource != previousButtonSource)) {
                                val parsed = runCatching { JSONArray(buttons) }.getOrNull()
                                if (parsed != null) {
                                    runCatching {
                                        badge?.updateHookedButtons(parsed, buttonSourceW, buttonSourceH)
                                    }.onSuccess {
                                        previousButtons = buttons
                                        previousButtonSource = buttonSource
                                    }
                                }
                            }
                        }
                    }
                }
            }
            main.postDelayed(this, 120)
        }
    }

    /**
     * Adopts the snapshot's language tag. Returns true when it differs from the one that
     * produced what is on screen, which is the signal to repaint the recommendation
     * window even if nothing else changed. The tile badge never sees this value.
     */
    private fun syncLanguage(tag: String): Boolean {
        if (tag == previousLanguageTag && languageKnown) return false
        val next = GameMenuText.languageOf(tag, ensureLanguage())
        previousLanguageTag = tag
        languageKnown = true
        language = next
        // host = true: from now on the game's tag owns the copy of this panel.
        panel?.setLanguageTag(tag, true)
        return true
    }

    /**
     * First guess only: the manager's stored preference. The player's choice lives in the
     * manager process; this read is just so the very first frame is already right, and a
     * failed read simply keeps Chinese until the first snapshot arrives.
     */
    private fun ensureLanguage(): ManagerLanguage {
        if (languageKnown) return language
        val app = host?.context?.applicationContext
        if (app != null) {
            // The stored *choice*, with "follow the system" resolved here, matches what
            // the manager page shows before the first reply corrects it.
            language = runCatching {
                ManagerLanguageSettings.readChoice(app).effective(ManagerSystemLanguage.current())
            }.getOrDefault(language)
        }
        return language
    }

    /** Called by the control's mode projection, independently of hint delivery. */
    fun setPresentationEnabled(enabled: Boolean) {
        if (presentationEnabled == enabled) return
        presentationEnabled = enabled
        if (!enabled) clearSurfaces()
    }

    /**
     * Called by the control when it hides the in-game surface: drops every view this layer
     * drew so a hidden control never leaves a hint behind.
     */
    fun hideNow() = clearSurfaces()

    private fun clearSurfaces() {
        panel?.close()
        badge?.close()
        previousPanelResult = null
        previousResult = ""
        previousCoords = ""
        previousButtons = ""
        previousSource = ""
        previousButtonSource = ""
    }

    /** Builds and shows the in-game hints. Only ever called in Hook mode. */
    fun attach(activity: Activity) {
        detach()
        val decor = activity.window?.decorView as? FrameLayout ?: return
        host = decor
        ensureLanguage()
        badge = TileBadgeOverlay(activity, decor)
        panel = RecommendationPanelOverlay(activity, decor)
        // Queued behind the snapshot call the tick issues right below, so the panel is
        // painted with the same tag everything else uses; this only avoids a Chinese
        // flash for a player who already chose another language.
        panel?.setLanguageTag(previousLanguageTag.ifEmpty { languageTagOf(language) })
        main.post(tick)
    }

    private fun languageTagOf(language: ManagerLanguage): String = when (language) {
        ManagerLanguage.SIMPLIFIED -> "zh-Hans"
        ManagerLanguage.ENGLISH -> "en"
        ManagerLanguage.JAPANESE -> "ja"
    }

    /**
     * False while the in-game hint surface must stay down (gate not decided yet, no activity).
     * The tick consults it, so hiding covers the hints and not only the control bar.
     */
    @JvmField var presentationAllowed: () -> Boolean = { true }

    /** Java setter for [presentationAllowed] (GameTopControl runs in the game process). */
    fun setPresentationAllowed(allowed: () -> Boolean) {
        presentationAllowed = allowed
    }

    private fun allowed(): Boolean = runCatching { presentationAllowed() }.getOrDefault(false)

    fun detach() {
        generation++
        main.removeCallbacks(tick)
        panel?.close(); panel = null
        previousPanelResult = null
        badge?.close()
        badge = null
        host = null
        previousResult = ""
        previousCoords = ""
        previousButtons = ""
        previousSource = ""
        previousButtonSource = ""
    }
}
