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
                        if (snapshot == null) {
                            panel?.close()
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
                            panel?.close()
                            badge?.update(JSONObject().put("status", "waiting"))
                            previousResult = ""
                            previousCoords = ""
                            previousButtons = ""
                        } else {
                            val result = snapshot.getString("result").orEmpty()
                            panel?.update(runCatching { JSONObject(result) }.getOrNull(), snapshot.getBoolean("recommendationPanel", true))
                            val coords = snapshot.getString("coords").orEmpty()
                            val buttons = snapshot.getString("buttons").orEmpty()
                            val sourceW = snapshot.getInt("sourceW")
                            val sourceH = snapshot.getInt("sourceH")
                            val buttonSourceW = snapshot.getInt("buttonSourceW")
                            val buttonSourceH = snapshot.getInt("buttonSourceH")
                            val source = "${sourceW}x${sourceH}"
                            val buttonSource = "${buttonSourceW}x${buttonSourceH}"
                            if (result.isNotEmpty() && result != previousResult) {
                                val parsed = runCatching { JSONObject(result) }.getOrNull()
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

    fun attach(activity: Activity) {
        detach()
        val decor = activity.window?.decorView as? FrameLayout ?: return
        host = decor
        badge = TileBadgeOverlay(activity, decor)
        panel = RecommendationPanelOverlay(activity, decor)
        main.post(tick)
    }

    fun detach() {
        generation++
        main.removeCallbacks(tick)
        panel?.close(); panel = null
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
