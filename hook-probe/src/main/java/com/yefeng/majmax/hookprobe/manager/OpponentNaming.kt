package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * How the assistant renders the display names of the three opponents.
 *
 * This mirrors the Rust enum (`hook-probe/rust-modder/src/settings.rs`): the wire values are
 * exactly the ones `ModSettings` persists in `settings.mod.json`, so a value stored here can
 * be handed to the modder as a `SettingsPatch` without a translation table in between.
 *
 * The mode only affects *display* data. The Rust side rewrites the `authGame` response of the
 * next game, and the in-game Lua layer (`MajsoulMaxNaming.lua`) renames the seats of the table
 * that is already on screen and redraws it once, so a change lands immediately and never needs a
 * restart of the game or of the mod.
 */
internal enum class OpponentNamingMode(val wire: String) {
    /** Server-provided names, i.e. the pre-existing behavior. */
    NORMAL("normal"),

    /**
     * 预设昵称 / preset names: the three opponents become fixed names (left, top, right).
     *
     * The wire value stays `"abc"` for compatibility with documents written before the
     * presets were named, even though the UI no longer shows letters.
     */
    ABC("abc"),

    /** The three [OpponentNaming.names] entries are used instead; empty keeps the server name. */
    CUSTOM("custom");

    companion object {
        /**
         * Parses a stored or received value.
         *
         * The accepted spellings match the Rust parser (`anonymize` and friends for [ABC]) and
         * are case/separator insensitive, so a document written by an older or newer build can
         * never fail the load; anything unknown degrades to [NORMAL], which is the behavior
         * before this feature existed.
         */
        fun fromWire(value: String?): OpponentNamingMode {
            val normalized = value.orEmpty()
                .filter { it.isLetterOrDigit() }
                .lowercase(Locale.ROOT)
            return when (normalized) {
                "abc", "anon", "anonymize", "anonymized", "anonymizeabc", "abcnames" -> ABC
                "custom", "customnames" -> CUSTOM
                else -> NORMAL
            }
        }
    }
}

/**
 * One complete opponent-naming choice: the mode plus the three user-defined labels.
 *
 * [names] is always the full slot list (`""` meaning "keep the server name for that seat"), so
 * the UI can index it by seat without a bounds dance and the persisted document never has to
 * distinguish "missing" from "empty". Slots are in visual seat order — left (kamicha), top
 * (toimen), right (shimocha) — which is also the order the Rust rewrite consumes them in.
 *
 * Deliberately free of Android view types: the game process parses this same class from the
 * provider reply, and the pure-logic JVM probe compiles it without the Android SDK.
 */
internal data class OpponentNaming(
    val mode: OpponentNamingMode = OpponentNamingMode.NORMAL,
    val names: List<String> = emptyList(),
) {
    /** Label of one slot; empty when the slot keeps the server name. */
    fun nameAt(slot: Int): String = names.getOrNull(slot).orEmpty()

    /** Same choice with [names] padded to [OpponentNamingSettings.SLOTS] and sanitized. */
    fun normalized(): OpponentNaming = OpponentNaming(
        mode,
        (0 until OpponentNamingSettings.SLOTS)
            .map { OpponentNamingSettings.normalizeName(nameAt(it)) },
    )

    /** `true` when at least one custom seat label would actually replace a name. */
    fun hasCustomNames(): Boolean = names.any { it.trim().isNotEmpty() }

    /** The choice as the game-facing wire string (`{"mode":"abc","names":[...]}`). */
    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.wire)
        .put("names", JSONArray(normalized().names))

    /**
     * The same choice as a camelCase `SettingsPatch` for `majmax_modder_update_settings`.
     *
     * Only the two naming fields are ever included: the Rust patch type denies unknown fields
     * and the naming path must never touch skins, the local nickname or the presets.
     */
    fun patchJson(): String = JSONObject()
        .put("namingMode", mode.wire)
        .put("customNames", JSONArray(normalized().names))
        .toString()

    companion object {
        /** Parses the game-facing wire string; a malformed or missing reply is [NORMAL]. */
        fun parse(raw: String?): OpponentNaming {
            if (raw.isNullOrBlank()) return OpponentNaming()
            return try {
                parse(JSONObject(raw))
            } catch (_: Exception) {
                OpponentNaming()
            }
        }

        /** Parses an already-decoded wire object; missing keys keep the defaults. */
        fun parse(json: JSONObject): OpponentNaming {
            val mode = OpponentNamingMode.fromWire(json.optString("mode", ""))
            val array = json.optJSONArray("names")
            val names = ArrayList<String>(OpponentNamingSettings.SLOTS)
            for (slot in 0 until OpponentNamingSettings.SLOTS) {
                names.add(array?.optString(slot, "").orEmpty())
            }
            return OpponentNaming(mode, names)
        }

        /**
         * The `SettingsPatch` JSON for one provider reply, or `null` when the reply carries no
         * usable choice.
         *
         * Static entry point for the game process [com.yefeng.majmax.hookprobe.ProbeModule],
         * which is Java: it must not rebuild the wire -> patch mapping (and could not keep it
         * in sync with the Rust field names) on its own.
         */
        @JvmStatic
        fun patchForWire(raw: String?): String? =
            if (raw.isNullOrBlank()) null else parse(raw).patchJson()

        /** Parses a wire reply; the Java callers use this to keep one decoded instance. */
        @JvmStatic
        fun decode(raw: String?): OpponentNaming = parse(raw)

        /** Canonical mode of a wire reply, for diagnostics only. */
        @JvmStatic
        fun modeOf(raw: String?): String = parse(raw).mode.wire

        /** How many seats a wire reply would actually rename, for diagnostics only. */
        @JvmStatic
        fun slotsOf(raw: String?): Int =
            parse(raw).normalized().names.count { it.trim().isNotEmpty() }
    }
}

/**
 * The one shared copy of the opponent-naming choice.
 *
 * It lives in the manager process (the only process both surfaces can reach) and is written
 * through the UID-checked [AiEndpointProvider] by the in-game control, and directly by the
 * floating window, which runs here. The game process polls it back and applies it to the Rust
 * modder, so both surfaces and the modder always agree on one stored value.
 *
 * Persistence is a `commit()` (not `apply()`): the reply of a provider write has to carry the
 * value that was really stored, otherwise a lost write would be reported as a success.
 */
internal object OpponentNamingSettings {
    /** Seats a game can rename: a four-player table has three opponents. */
    const val SLOTS = 3

    /** Longest accepted label, matching the Rust validator and the local nickname limit. */
    const val MAX_NAME_CHARS = 64

    private const val PREFS = "opponent-naming"
    private const val KEY_MODE = "mode"
    private const val KEY_NAMES = "names"

    /** Strips control characters and caps the length; the Rust patch validates the same. */
    fun normalizeName(raw: String?): String {
        val stripped = raw.orEmpty().filter { character -> !character.isISOControl() }
        if (stripped.length <= MAX_NAME_CHARS) return stripped
        val end = stripped.offsetByCodePoints(0, stripped.codePointCount(0, stripped.length)
            .coerceAtMost(MAX_NAME_CHARS))
        return stripped.substring(0, end)
    }

    /** The stored choice; a fresh install is [OpponentNamingMode.NORMAL]. */
    fun read(context: Context): OpponentNaming {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = OpponentNamingMode.fromWire(preferences.getString(KEY_MODE, null))
        val names = runCatching {
            val raw = preferences.getString(KEY_NAMES, null) ?: return@runCatching emptyList()
            val array = JSONArray(raw)
            (0 until array.length()).map { array.optString(it, "") }
        }.getOrDefault(emptyList())
        return OpponentNaming(mode, names).normalized()
    }

    /**
     * Stores exactly [naming] and returns what a later [read] would see.
     *
     * Only the two naming keys are touched, so a write can never clear an unrelated
     * preference of the assistant.
     */
    fun save(context: Context, naming: OpponentNaming): OpponentNaming {
        val stored = naming.normalized()
        val names = JSONArray()
        stored.names.forEach { names.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, stored.mode.wire)
            .putString(KEY_NAMES, names.toString())
            .commit()
        return read(context)
    }

    /**
     * Patch semantics for the provider: a missing argument keeps the stored value.
     *
     * The in-game control sends only what the user changed (a mode tap carries no names, a
     * label edit carries the collected names), so neither surface can overwrite a value the
     * other one just wrote with a stale copy of the rest.
     */
    fun applyPatch(context: Context, mode: String?, names: List<String>?): OpponentNaming {
        val current = read(context)
        val next = OpponentNaming(
            if (mode == null) current.mode else OpponentNamingMode.fromWire(mode),
            names ?: current.names,
        )
        return save(context, next)
    }
}
