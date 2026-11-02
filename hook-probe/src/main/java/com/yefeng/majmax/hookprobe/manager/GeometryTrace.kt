package com.yefeng.majmax.hookprobe.manager

import org.json.JSONObject

/**
 * Bounded, rate-limited trace for the badge-geometry pipeline.
 *
 * The overlay runs in the manager process, so it cannot call the in-game
 * [com.yefeng.majmax.hookprobe.HookDiagnostics] channel directly; it records into
 * [DiagnosticsStore] instead, under the `assistant.hint` component.
 *
 * Emission rules keep log volume predictable during a long session:
 *  - each (code + detail) pair is written at most [MAX_PER_CODE] times,
 *  - the whole trace is capped at [MAX_TOTAL] records per overlay lifetime,
 *  - callers pass `key=value` pairs; recognised keys become structured fields and
 *    the remainder is folded into the sanitised `reason` field.
 *
 * Tile names and coordinates are ordinary gameplay state, not credentials, and
 * [DiagnosticsStore] filters every value to letters/digits plus a few separators.
 */
internal object GeometryTrace {
    private const val MAX_TOTAL = 96
    private const val MAX_PER_CODE = 16

    /** Keys promoted to structured fields instead of being squeezed into `reason`. */
    private val structuredKeys = setOf(
        "kept", "rejected", "ambiguous", "missing", "placed", "hand", "views", "hints",
        "screenW", "screenH", "overlayW", "overlayH", "open", "baida", "invalid", "ortho",
        "behind", "minNx", "maxNx", "minNy", "maxNy", "sample",
        "tiles", "order", "reds", "views_n", "cam", "cams", "pairs", "frame", "targets",
    )

    private val counts = HashMap<String, Int>()
    private var total = 0
    private var context: android.content.Context? = null

    fun bind(context: android.content.Context?) {
        this.context = context?.applicationContext
    }

    fun reset() {
        counts.clear()
        total = 0
    }

    /**
     * Records a diagnostic. [detail] is a `;`-separated `key=value` list. `;` (not a
     * space) is the separator because tile order snapshots contain gaps, e.g.
     * `order=1m,2m 5p;views=1m@0.21 2m@0.26`.
     */
    fun note(code: String, detail: String) {
        val ctx = context ?: return
        if (total >= MAX_TOTAL) return
        val seen = counts[code] ?: 0
        if (seen >= MAX_PER_CODE) return
        counts[code] = seen + 1
        total++

        val fields = JSONObject()
        val leftovers = ArrayList<String>()
        for (token in detail.split(';')) {
            if (token.isEmpty()) continue
            val eq = token.indexOf('=')
            if (eq > 0 && token.substring(0, eq) in structuredKeys) {
                val key = token.substring(0, eq)
                val raw = token.substring(eq + 1)
                fields.put(key, raw.toIntOrNull() ?: raw)
            } else {
                leftovers.add(token)
            }
        }
        if (leftovers.isNotEmpty()) fields.put("reason", leftovers.joinToString(" ").take(120))
        fields.put("count", seen + 1)
        runCatching { DiagnosticsStore.appendAssistant(ctx, "INFO", "assistant.hint", code, fields) }
    }
}
