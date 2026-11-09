package com.yefeng.majmax.hookprobe

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import com.yefeng.majmax.hookprobe.manager.TileBadgeOverlay
import com.yefeng.majmax.hookprobe.manager.RecommendationPanelOverlay
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only tile hints in the game window; no system overlay or touch interception. */
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
                        if (!presentationEnabled || !allowed() || snapshot?.getBoolean("overlayMode", false) == true) {
                            clearSurfaces()
                            return@post
                        }
                        // A snapshot fetched before the user switched modes carries the
                        // previous generation. Painting it would put one frame of the old
                        // surface on top of the new one, so it is dropped and the next
                        // tick simply asks again.
                        if (snapshot != null && !presentationStale(
                                snapshot.getInt("displayGeneration", 0))) {
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
                            if (result != previousPanelResult || panelEnabled != previousPanelEnabled) {
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

    /** Called by the control's mode projection, independently of hint delivery. */
    fun setPresentationEnabled(enabled: Boolean) {
        if (presentationEnabled == enabled) return
        presentationEnabled = enabled
        if (!enabled) clearSurfaces()
    }

    /**
     * Called by the control right before it acknowledges a switch: drops every surface this
     * layer drew, so the acknowledgement cannot race a still-visible hint.
     */
    fun hideNow() = clearSurfaces()

    /**
     * True when the snapshot was taken for a display-mode generation that has already been
     * superseded by the decision this layer paints under. Same rule as the manager's
     * generation stamp: the in-game hint surface and the system overlays must never paint
     * for two different modes. A snapshot without the field (older manager) is accepted.
     */
    private fun presentationStale(snapshotGeneration: Int): Boolean {
        if (snapshotGeneration == 0) return false
        val current = modeGeneration()
        return current != 0 && snapshotGeneration != current
    }

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

    fun attach(activity: Activity) {
        detach()
        val decor = activity.window?.decorView as? FrameLayout ?: return
        host = decor
        badge = TileBadgeOverlay(activity, decor)
        panel = RecommendationPanelOverlay(activity, decor)
        main.post(tick)
    }

    /** Supplies the mode generation the in-game surface currently paints under. */
    /**
     * False while the in-game hint surface must stay down (overlay mode, switch window).
     * The tick consults it, so "hidden" covers the hints and not only the control bar: the
     * acknowledgement the control sends promises exactly this.
     */
    @JvmField var presentationAllowed: () -> Boolean = { true }

    /** Mode generation the in-game surface currently paints under. */
    @JvmField var modeGeneration: () -> Int = { 0 }

    private fun allowed(): Boolean = runCatching { presentationAllowed() }.getOrDefault(true)

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
