package com.yefeng.majmax.hookprobe.manager

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded, privacy-safe rendering of an adapter ACK `reason` for diagnostics.
 *
 * The adapter encodes its rows as JSON objects:
 *
 *  - `hand_geometry` (Lua `reportHandCoords`) emits `screenW/screenH/handpai/concealed/
 *    open/baida/invalid/behind/minNx/maxNx/minNy/maxNy/cam/ortho`, plus `cams`
 *    (`name|ortho|inView/total|bbox|meanNy`, rows joined by `+`, and a `|!<miss>`
 *    suffix when no camera covered the hand) and `tiles`;
 *  - `button_coords` (Lua `reportButtonCoords`) emits `screenW/screenH/buttons`, where
 *    each button is `{key, nx, ny}`.
 *
 * The controller used to log `state + reason` and the store then cut it at 64
 * characters, which is why every rejected frame in the reported package collapsed to
 * the same `hand_geometry concealed:13,tiles:1m,7z,cams:UICamera|o` fragment:
 * `concealed` (the only count that says "nothing was projected") was never recorded.
 *
 * This renderer keeps a fixed vocabulary instead of the payload:
 *
 *  - numbers: finite doubles, at most 6 significant digits;
 *  - short identifiers (`cam`, tile names, button keys): letters/digits/`_.:` only;
 *  - camera fingerprints: `name|ortho|inView/total|bbox|meanNy` with `/`, `,`, `.`,
 *    `-` and `_` allowed inside a field;
 *  - button centres: `key:nx,ny` pairs, capped at [MAX_POINTS] (the adapter sends up to
 *    nine, so the cap is above that);
 *  - anything else — a raw RPC body, a nickname, a token, an unknown key — is either
 *    dropped (unknown key) or rendered as `!` plus an 8-digit fingerprint, so repeated
 *    values still compare equal without any of their bytes reaching the log.
 */
internal object AutoAckDetails {
    /** Short identifiers safe to copy verbatim: tiles, button keys, state names. */
    private val IDENTIFIER = Regex("[A-Za-z0-9_.:\\-]{1,40}")
    private val CAMERA_NAME = Regex("[A-Za-z0-9_]{1,40}")
    /** One camera field: digits/letters plus the separators the Lua format uses. */
    private val CAMERA_FIELD = Regex("[A-Za-z0-9_.,:;/+\\-]{1,48}")
    private const val MAX_POINTS = 16
    /** The adapter samples at most six cameras per fingerprint; keep headroom for more. */
    private const val MAX_CAMERAS = 16

    /**
     * The only keys ever read out of an adapter payload, matching what the Lua side
     * actually emits for the two diagnostic states. Anything else (account, token,
     * request body, a future field) is dropped before it can reach a record.
     */
    private val allowed = setOf(
        // hand_geometry
        "screenW", "screenH", "handpai", "concealed", "open", "baida", "invalid", "behind",
        "minNx", "maxNx", "minNy", "maxNy", "cam", "ortho", "cams", "tiles",
        // button_coords
        "buttons",
        // shared / occasional
        "count",
    )

    /**
     * Renders [reason] for [state].
     *
     * A JSON object (what the adapter sends) is compacted key by key; a bare JSON
     * array is a legacy `hand_coords` capture; anything else is prose (rejection
     * states such as `stale`) and is kept only if it is a short plain sentence.
     */
    fun render(state: String, reason: String): String {
        val text = reason.trim()
        // The controller passes `"<state> <detail>"` as the detail for a rejection, and
        // the store has already been writing the code alone. Never repeat the state.
        val id = identifier(state)
        if (text.isEmpty()) return id
        if (id.isNotEmpty() && (text == id || text.startsWith("$id "))) return text
        if (text.startsWith("{")) {
            val encoded = runCatching { JSONObject(text) }.getOrNull()
                ?: return sanitizeText(text)
            val prefix = identifier(state)
            val body = compact(encoded)
            return if (body.isEmpty()) prefix else "$prefix $body"
        }
        if (text.startsWith("[")) {
            val array = runCatching { JSONArray(text) }.getOrNull()
                ?: return sanitizeText(text)
            return "points=" + points(array)
        }
        return sanitizeText(text)
    }

    /** Renders the allow-listed fields of [source]; unknown keys never reach the output. */
    fun compact(source: JSONObject): String {
        val out = StringBuilder()
        fun field(name: String, value: String) {
            if (value.isEmpty()) return
            if (out.isNotEmpty()) out.append(' ')
            out.append(name).append('=').append(value)
        }
        for (key in source.keys().asSequence().sorted()) {
            if (key !in allowed) continue
            when (val value = source.opt(key)) {
                is Number -> source.optDouble(key, Double.NaN)
                    .takeIf { it.isFinite() }?.let { field(key, format(it)) }
                is Boolean -> field(key, if (value) "1" else "0")
                is String -> when (key) {
                    "cam" -> field(key, identifier(value))
                    "cams" -> field(key, cameras(value))
                    "tiles" -> field(key, identifiers(value))
                    else -> field(key, identifier(value))
                }
                is JSONArray -> if (key == "buttons") field(key, buttons(value)) else field(key, points(value))
                else -> Unit
            }
        }
        return out.toString()
    }

    /** Button centres as `key:nx,ny`, capped at [MAX_POINTS]. */
    fun buttons(array: JSONArray): String {
        val out = StringBuilder()
        val limit = minOf(array.length(), MAX_POINTS)
        for (i in 0 until limit) {
            val item = array.optJSONObject(i) ?: continue
            val key = identifier(item.optString("key"))
            val nx = finite(item, "nx") ?: continue
            val ny = finite(item, "ny") ?: continue
            if (out.isNotEmpty()) out.append(' ')
            out.append(key).append(':').append(nx).append(',').append(ny)
        }
        return if (array.length() > limit) "$out;n=${array.length()}" else out.toString()
    }

    /** One `x,y` pair for a coordinate array, bounded to [MAX_POINTS]. */
    fun points(array: JSONArray): String {
        val out = StringBuilder()
        val limit = minOf(array.length(), MAX_POINTS)
        for (i in 0 until limit) {
            val item = array.optJSONObject(i) ?: continue
            val nx = finite(item, "nx") ?: continue
            val ny = finite(item, "ny") ?: continue
            if (out.isNotEmpty()) out.append(' ')
            out.append(nx).append(',').append(ny)
        }
        return if (array.length() > limit) "$out;points=${array.length()}" else out.toString()
    }

    /**
     * `name|ortho|inView/total|bbox|meanNy` rows joined by `+`, keeping the trailing
     * `|!<miss>` section. A field outside the documented shape collapses to a
     * fingerprint, so a malformed row still yields a stable, bounded string.
     */
    fun cameras(value: String): String {
        if (value.isBlank()) return ""
        val rows = value.split('+')
        val rendered = rows.take(MAX_CAMERAS).map { camera(it) }
        val text = rendered.joinToString("+")
        return if (rows.size > MAX_CAMERAS) "$text;cams=${rows.size}" else text
    }

    private fun camera(row: String): String {
        if (row.isBlank()) return ""
        // Keep the miss marker attached to the last camera row: it is the one field
        // that explains why a capture failed.
        val parts = if (row.contains('|')) row.split('|') else listOf(row)
        val name = parts.first().trim()
        if (name.isEmpty()) return ""
        val head = if (CAMERA_NAME.matches(name)) name else fingerprint(name)
        val rest = parts.drop(1).map { part ->
            val trimmed = part.trim()
            when {
                trimmed.isEmpty() -> ""
                CAMERA_FIELD.matches(trimmed) -> trimmed
                // `!<miss>` keeps digits, underscores and a leading `!`.
                trimmed.matches(Regex("![A-Za-z0-9_.,:\\- ]{0,64}")) -> trimmed
                else -> fingerprint(trimmed)
            }
        }
        return (listOf(head) + rest).joinToString("|")
    }

    private fun identifier(value: String): String {
        val trimmed = value.trim()
        return if (IDENTIFIER.matches(trimmed)) trimmed else if (trimmed.isEmpty()) "" else fingerprint(trimmed)
    }

    private fun identifiers(value: String): String {
        if (value.isEmpty()) return ""
        val items = value.split(',', '|', ' ')
        val limit = minOf(items.size, MAX_POINTS)
        val rendered = items.take(limit).joinToString(",") { identifier(it) }
        return if (items.size > limit) "$rendered;n=${items.size}" else rendered
    }

    private fun finite(source: JSONObject, key: String): String? {
        if (!source.has(key)) return null
        val value = source.optDouble(key, Double.NaN)
        if (!value.isFinite()) return null
        return format(value)
    }

    /** At most 6 significant digits, no trailing zeros, never scientific noise. */
    internal fun format(value: Double): String {
        if (!value.isFinite()) return "nan"
        val magnitude = kotlin.math.abs(value)
        if (magnitude == 0.0) return "0"
        if (magnitude >= 1_000_000.0) return String.format(java.util.Locale.US, "%.0f", value)
        val decimals = (6 - (kotlin.math.floor(kotlin.math.log10(magnitude)) + 1).toInt()).coerceIn(0, 6)
        var text = String.format(java.util.Locale.US, "%.${decimals}f", value)
        if (text.contains('.')) text = text.trimEnd('0').trimEnd('.')
        return text.ifEmpty { "0" }
    }

    /**
     * Deterministic, domain-separated 32-bit FNV-1a fingerprint. Not a security hash:
     * equal inputs compare equal, and no input bytes are copied.
     */
    internal fun fingerprint(value: String): String {
        if (value.isEmpty()) return "!0"
        var hash = 0x811C9DC5.toInt()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 0x01000193
        }
        return "!" + java.lang.Integer.toHexString(hash)
    }

    /**
     * Prose reason (rejection states). Keeps letters, digits and the separators the
     * existing messages use; a value that is not a short plain sentence is
     * fingerprinted instead.
     */
    fun sanitizeText(text: String): String {
        val collapsed = text.trim().replace(Regex("\\s+"), " ")
        if (collapsed.length > 160) return collapsed.take(160) + "+"
        if (!collapsed.matches(Regex("[A-Za-z0-9 _./()：:,+|=;!\\-]{1,160}"))) return fingerprint(collapsed)
        return collapsed
    }
}
