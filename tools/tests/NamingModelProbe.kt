// JVM-only regression for the shared opponent-naming model (manager/OpponentNaming.kt).
//
// The model is the contract between three places that must not drift apart:
//
//   1. the Rust settings document (`namingMode` / `customNames`, camelCase, deny_unknown_fields),
//   2. the provider wire string the game process reads (`{"mode":"abc","names":[...]}`),
//   3. both UIs (in-game control card, floating window card).
//
// It is pure Kotlin (no Context, no View), so it is compiled straight from the real source and
// exercised here: tolerant mode parsing, the exact camelCase patch the Rust patch type accepts,
// the 64-character / 3-slot normalization, and the "empty means keep the server name" rule.
import com.yefeng.majmax.hookprobe.manager.OpponentNaming
import com.yefeng.majmax.hookprobe.manager.OpponentNamingMode
import com.yefeng.majmax.hookprobe.manager.OpponentNamingSettings
import org.json.JSONObject

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
    if (cond) { passed++; println("PASS  $name") }
    else { failed++; println("FAIL  $name  ${detail()}") }
}

private fun checkEq(name: String, expected: Any?, actual: Any?) =
    check(name, expected == actual) { "expected=$expected actual=$actual" }

fun main() {
    // ---- 1. tolerant mode parsing (mirrors the Rust NamingMode::parse) -----------
    checkEq("parse(null) is normal", OpponentNamingMode.NORMAL, OpponentNamingMode.fromWire(null))
    checkEq("parse(\"\") is normal", OpponentNamingMode.NORMAL, OpponentNamingMode.fromWire(""))
    checkEq("parse(\"normal\") is normal", OpponentNamingMode.NORMAL, OpponentNamingMode.fromWire("normal"))
    checkEq("parse(\"Anonymize\") is abc", OpponentNamingMode.ABC, OpponentNamingMode.fromWire("Anonymize"))
    checkEq("parse(\"a_b-c\") is abc", OpponentNamingMode.ABC, OpponentNamingMode.fromWire("a_b-c"))
    checkEq("parse(\"ABC\") is abc", OpponentNamingMode.ABC, OpponentNamingMode.fromWire("ABC"))
    checkEq("parse(\"customNames\") is custom", OpponentNamingMode.CUSTOM,
        OpponentNamingMode.fromWire("customNames"))
    checkEq("parse(\"future-mode\") degrades to normal", OpponentNamingMode.NORMAL,
        OpponentNamingMode.fromWire("future-mode"))

    // ---- 2. the default is "nothing changes" ------------------------------------
    val fresh = OpponentNaming.decode(null)
    checkEq("a missing choice is normal", OpponentNamingMode.NORMAL, fresh.mode)
    checkEq("a missing choice has three empty slots", 3, fresh.normalized().names.size)
    checkEq("every empty slot means 'keep the server name'", false, fresh.hasCustomNames())
    // org.json does not preserve key order, so the patch is compared field by field.
    val freshPatch = JSONObject(fresh.patchJson())
    checkEq("the default patch asks for normal", "normal", freshPatch.optString("namingMode"))
    checkEq("the default patch keeps three empty labels", 3,
        freshPatch.getJSONArray("customNames").length())
    checkEq("the default patch carries nothing else", 2, freshPatch.length())

    // ---- 3. the patch is exactly what the Rust SettingsPatch accepts ------------
    val abc = OpponentNaming(OpponentNamingMode.ABC)
    val abcPatch = JSONObject(abc.patchJson())
    checkEq("the patch carries namingMode", "abc", abcPatch.optString("namingMode"))
    checkEq("the patch carries customNames", true, abcPatch.optJSONArray("customNames") != null)
    checkEq("the patch carries nothing else", 2, abcPatch.length())

    val custom = OpponentNaming(OpponentNamingMode.CUSTOM, listOf("上家", "", "下家"))
    val customPatch = JSONObject(custom.patchJson())
    checkEq("custom mode round-trips through the patch", "custom", customPatch.optString("namingMode"))
    checkEq("custom names are padded to three slots", 3,
        customPatch.getJSONArray("customNames").length())
    checkEq("an empty middle slot stays empty", "",
        customPatch.getJSONArray("customNames").optString(1))
    checkEq("the left slot is the first name", "上家",
        customPatch.getJSONArray("customNames").optString(0))
    checkEq("the right slot is the third name", "下家",
        customPatch.getJSONArray("customNames").optString(2))

    // ---- 4. the wire form and the patch agree ---------------------------------
    val wire = custom.toJson().toString()
    val decoded = OpponentNaming.parse(wire)
    checkEq("the wire retains the mode", OpponentNamingMode.CUSTOM, decoded.mode)
    checkEq("the wire retains every slot", listOf("上家", "", "下家"), decoded.normalized().names)
    checkEq("an unknown mode in the wire falls back to normal", OpponentNamingMode.NORMAL,
        OpponentNaming.parse("""{"mode":"nope","names":["甲"]}""").mode)
    checkEq("a malformed wire falls back to normal", OpponentNamingMode.NORMAL,
        OpponentNaming.parse("not json").mode)

    // ---- 5. what the game process sends to the modder --------------------------
    checkEq("patchForWire(null) is null", null, OpponentNaming.patchForWire(null))
    checkEq("patchForWire(blank) is null", null, OpponentNaming.patchForWire("  "))
    checkEq("patchForWire(malformed) still yields a valid patch", "normal",
        JSONObject(OpponentNaming.patchForWire("{oops")!!).optString("namingMode"))
    checkEq("modeOf reports the canonical mode", "abc",
        OpponentNaming.modeOf("""{"mode":"anonymize","names":[]}"""))
    checkEq("slotsOf counts only non-empty labels", 2,
        OpponentNaming.slotsOf("""{"mode":"custom","names":["甲","","丙"]}"""))

    // ---- 6. validation: control characters and the 64-character cap ------------
    checkEq("control characters are stripped", "ab",
        OpponentNamingSettings.normalizeName("a\u0000b\u0007"))
    checkEq("a newline is a control character too", "ab",
        OpponentNamingSettings.normalizeName("a\nb"))
    checkEq("the 64-character cap is applied", 64,
        OpponentNamingSettings.normalizeName("名".repeat(80)).length)
    // Astral characters must not be split in half by the cap: a lone surrogate cannot be
    // encoded as UTF-8 and would make the whole patch fail to deserialize in Rust.
    val astral = OpponentNamingSettings.normalizeName("😀".repeat(80))
    checkEq("the cap counts code points, not UTF-16 units", 64, astral.codePointCount(0, astral.length))
    checkEq("64 astral code points keep 128 UTF-16 units", 128, astral.length)
    // The last *code point* must still be the whole emoji: a split pair would end with a lone
    // low surrogate, which serde_json rejects when the patch reaches the modder.
    checkEq("the capped astral string ends on a whole character", 0x1F600,
        astral.codePointBefore(astral.length))
    checkEq("a name of exactly 64 characters is kept", 64,
        OpponentNamingSettings.normalizeName("名".repeat(64)).length)

    // ---- 7. slots are normalized (pad / truncate) -----------------------------
    val tooMany = OpponentNaming(OpponentNamingMode.CUSTOM, listOf("甲", "乙", "丙", "丁"))
    checkEq("a fourth label is dropped", listOf("甲", "乙", "丙"), tooMany.normalized().names)
    checkEq("a missing slot reads as empty", "", tooMany.nameAt(9))
    checkEq("hasCustomNames is true when any slot is set", true, custom.hasCustomNames())
    checkEq("hasCustomNames ignores blank slots",
        false, OpponentNaming(OpponentNamingMode.CUSTOM, listOf("", "  ", "")).hasCustomNames())
    // A blank label is stored trimmed-but-typed: the Rust side decides what "empty" means, the
    // manager only guarantees that a whitespace-only label is still treated as "keep the name".
    checkEq("a whitespace-only slot is not a custom name", false,
        OpponentNaming(OpponentNamingMode.CUSTOM, listOf("   ", "", "")).hasCustomNames())

    println("=== $passed passed, $failed failed ===")
    if (failed > 0) kotlin.system.exitProcess(1)
}
