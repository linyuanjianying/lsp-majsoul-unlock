// JVM-only regression probe for the in-game / floating-window copy.
//
// Compiles the real sources (GameMenuText.kt, InGameLocalizer.kt,
// RecommendationPanelOverlay.kt) against the already-built Kotlin classes with
// -Xfriend-paths, so `internal` members are reachable without touching the project.
// Only pure functions are exercised: no Context, no window, no provider.
//
// What it proves:
//  - Chinese is byte-identical to the pre-existing copy (displayLines' original shape,
//    the legacy signature still compiles and still returns the Chinese rows).
//  - Every translated line is in the requested language, and the mahjong terms are the
//    ones the table promises (tsumo/ron/shanten/riichi/furiten, ツモ/ロン/シャンテン/リーチ).
//  - English has no CJK left in the shared strings, and no unknown diagnostic is ever
//    rewritten.
//  - The machine values (dataFor output, tile codes, numbers) are untouched by the
//    translation path.
import com.yefeng.majmax.hookprobe.helper.GameMenuText
import com.yefeng.majmax.hookprobe.manager.ManagerLanguage
import com.yefeng.majmax.hookprobe.manager.RecommendationPanelOverlay
import org.json.JSONArray
import org.json.JSONObject

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
    if (cond) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name  ${detail()}") }
}

private val ZH = ManagerLanguage.SIMPLIFIED
private val EN = ManagerLanguage.ENGLISH
private val JA = ManagerLanguage.JAPANESE

private val CJK = Regex("[\\u4e00-\\u9fff\\u3040-\\u30ff]")

private fun t(zh: String, lang: ManagerLanguage) = GameMenuText.text(zh, lang)

private fun lines(data: JSONObject, lang: ManagerLanguage?) =
    RecommendationPanelOverlay.displayLines(data, lang)

private fun samplePanel(): JSONObject = JSONObject()
    .put("rows", JSONArray()
        .put(JSONObject().put("tile", "5p").put("probabilityPercent", 83.42))
        .put(JSONObject().put("tile", "E").put("probabilityPercent", 9.5)))
    .put("scores", JSONArray()
        .put(JSONObject()
            .put("tile", "5s")
            .put("ron", JSONObject().put("han", 2).put("fu", 30).put("points", 2000))
            .put("tsumo", JSONObject().put("han", 3).put("fu", 20).put("points", 700))
            .put("riichi_ron", JSONObject().put("yakuman", true).put("points", 32000))
            .put("riichi_tsumo", JSONObject().put("han", 1).put("fu", 40).put("points", 1300))))
    .put("scoreLabel", "当前听牌番点")
    .put("players", 3)
    .put("kitaCount", 2)

fun main() {
    // 1. Legacy signature and Chinese identity --------------------------------------
    val data = samplePanel()
    val legacy = RecommendationPanelOverlay.displayLines(data)
    val zhExplicit = lines(data, ZH)
    val zhNull = lines(data, null)
    check("legacy displayLines() still compiles and returns rows", legacy.isNotEmpty())
    check("null language == Chinese", zhNull == legacy) { "$zhNull != $legacy" }
    check("explicit Chinese == legacy", zhExplicit == legacy)
    check("Chinese row text is the original copy",
        legacy[0].text == "推荐切牌 · 决策概率" && legacy[1].text.startsWith("切5筒")) { legacy.joinToString(" | ") { it.text } }
    check("Chinese score line keeps its original separator",
        legacy.any { it.text == "荣和 2番30符 2000点 / 自摸 3番20符 700点" }) {
        legacy.joinToString(" | ") { it.text }
    }
    check("Chinese furiten label kept", legacy.any { it.text == "当前听牌番点" })
    check("Chinese kita line kept", legacy.any { it.text.startsWith("拔北 ×2") })
    check("Chinese wait line kept", legacy.any { it.text == "和5索" }) { legacy.joinToString(" | ") { it.text } }


    // 2. English panel ---------------------------------------------------------------
    val en = lines(data, EN)
    check("English panel keeps the same row count", en.size == legacy.size) { "${en.size} vs ${legacy.size}" }
    check("English panel keeps gold/small flags", en.map { it.gold to it.small } == legacy.map { it.gold to it.small })
    check("English title", en[0].text == "Discard · decision probability") { en[0].text }
    check("English discard row keeps tile and number",
        en[1].text == "Discard 5p    83.4%") { en[1].text }
    check("English honour tile", en[2].text == "Discard East    9.5%") { en[2].text }
    check("English score header", en.any { it.text == "Hand value at tenpai" }) { en.joinToString(" | ") { it.text } }
    check("English ron/tsumo line", en.any { it.text == "Ron 2 han 30 fu, 2000 pts · Tsumo 3 han 20 fu, 700 pts" }) {
        en.joinToString(" | ") { it.text }
    }
    check("English riichi line", en.any { it.text == "Riichi · ron yakuman 32000 pts · tsumo 1 han 40 fu, 1300 pts" }) {
        en.joinToString(" | ") { it.text }
    }
    check("English has no CJK left", en.none { CJK.containsMatchIn(it.text) }) {
        en.firstOrNull { CJK.containsMatchIn(it.text) }?.text ?: ""
    }
    check("English estimate footnote translated", en.any { it.text.startsWith("Baseline estimate") })

    // 3. Japanese panel --------------------------------------------------------------
    val ja = lines(data, JA)
    check("Japanese keeps the same row count", ja.size == legacy.size)
    check("Japanese title", ja[0].text == "打牌推奨 · 判断確率") { ja[0].text }
    check("Japanese discard row", ja[1].text == "打 5筒    83.4%") { ja[1].text }
    check("Japanese ron/tsumo", ja.any { it.text == "ロン 2翻 30符 2000点 · ツモ 3翻 20符 700点" }) {
        ja.joinToString(" | ") { it.text }
    }
    check("Japanese furiten label", ja.any { it.text.startsWith("テンパイ時の点数") }) {
        ja.joinToString(" | ") { it.text }
    }
    check("Japanese riichi line", ja.any { it.text.startsWith("リーチ ロン役満 32000点") }) {
        ja.joinToString(" | ") { it.text }
    }
    check("Japanese tiles keep their kanji", ja[1].text.contains("筒"))
    check("English wait line", en.any { it.text == "Win 5s" }) { en.joinToString(" | ") { it.text } }
    check("Japanese wait line", ja.any { it.text == "和了 5索" }) { ja.joinToString(" | ") { it.text } }

    // 4. Waiting / shanten states ----------------------------------------------------
    val waiting = JSONObject().put("rows", JSONArray()).put("scores", JSONArray()).put("shanten", 2)
    check("waiting line zh", lines(waiting, ZH).last().text == "2向听 · 听牌后显示番点")
    check("waiting line en", lines(waiting, EN).last().text == "2-shanten · hand value shown at tenpai") {
        lines(waiting, EN).last().text
    }
    check("waiting line ja", lines(waiting, JA).last().text == "2 シャンテン · テンパイ後に点数を表示") {
        lines(waiting, JA).last().text
    }
    val ready = JSONObject().put("rows", JSONArray()).put("scores", JSONArray()).put("shanten", 0)
    check("tenpai without scores en", lines(ready, EN).last().text == "Tenpai · no hand value available")
    val empty = JSONObject().put("rows", JSONArray()).put("scores", JSONArray()).put("shanten", -99)
    check("no data en", lines(empty, EN).last().text == "Waiting for the hand analysis") {
        lines(empty, EN).last().text
    }

    // 5. dataFor is untouched by the translation path --------------------------------
    val result = JSONObject()
        .put("analysis", JSONObject().put("hand13", JSONObject().put("shanten", 0)))
        .put("recommendations", JSONArray().put(JSONObject().put("kind", "discard").put("tile", "E").put("weight", 40)))
    val machine = RecommendationPanelOverlay.dataFor(result)
    check("dataFor keeps the machine label", machine.getString("scoreLabel") == "当前听牌番点")
    check("dataFor keeps shanten", machine.getInt("shanten") == 0)
    check("dataFor returns machine JSON even after displayLines ran",
        RecommendationPanelOverlay.dataFor(result).toString() == machine.toString())

    // 6. Shared copy helper ----------------------------------------------------------
    check("unknown diagnostic is never rewritten",
        GameMenuText.text("Collector exploded at frame 41", EN) == "Collector exploded at frame 41")
    check("unknown diagnostic untouched in Japanese",
        GameMenuText.text("Collector exploded at frame 41", JA) == "Collector exploded at frame 41")
    check("Chinese pass-through is identity for an untranslated string",
        GameMenuText.text("某条只存在于中文的界面文案", ZH) == "某条只存在于中文的界面文案")
    check("brand translated", t("雀魂助手", EN) == "Mahjong Assistant" && t("雀魂助手", JA) == "雀魂アシスト")
    check("tabs translated", t("控制", EN) == "Control" && t("状态", JA) == "状態")
    check("unattended translated", t("无人值守", EN) == "Unattended" && t("无人值守", JA) == "放置モード")
    check("auto next game translated", t("自动下一局", EN) == "Auto next game" && t("自动下一局", JA) == "自動で次局へ")
    check("discards & points translated", t("推荐与番点", EN) == "Discards & points")
    check("prefix keeps its dynamic tail", t("自动下一局 · 开（仅按下一局原按钮）", EN)
        .startsWith("Auto next game · ") && t("自动下一局 · 开", EN).endsWith(" 开"))
    check("system overlay error keeps the reason token verbatim",
        t("系统悬浮窗不可用 · overlay_permission_missing", EN) ==
            "System overlay unavailable · overlay_permission_missing")
    check("manager status line translated", t("等待牌局", EN) == "Waiting for a game")
    check("unknown manager status line untouched",
        t("engine: cuDNN init failed", EN) == "engine: cuDNN init failed")
    check("every long label has three languages",
        listOf("助手服务自动分析并出牌", "整场牌局结束后自动继续", "固定面板显示建议牌与番点 · 不影响其他功能",
            "点击开启即自动启动管理应用的前台分析服务", "停止助手会关闭无人值守模式并隐藏悬浮窗；拖动标题栏移动，点“收起”变成浮标。",
            "先开启助手，再启动雀魂进入对局。中途开启或重连后，需等待完整牌局同步。")
            .all { t(it, EN) != it && t(it, JA) != it && !CJK.containsMatchIn(t(it, EN)) })

    // 7. Tile faces ------------------------------------------------------------------
    check("tile 5p en", GameMenuText.tileName("5p", EN) == "5p")
    check("tile 0m en", GameMenuText.tileName("0m", EN) == "Red 5m")
    check("tile 0m ja", GameMenuText.tileName("0m", JA) == "赤萬")
    check("tile 5z en", GameMenuText.tileName("5z", EN) == "White")
    check("tile E ja", GameMenuText.tileName("E", JA) == "東")
    check("unknown tile code echoed", GameMenuText.tileName("x9", EN) == "x9")
    check("tile zh identity", GameMenuText.tileName("5p", ZH) == "5筒" && GameMenuText.tileName("0m", ZH) == "赤5万")

    // 8. Round labels ---------------------------------------------------------------
    check("round en", GameMenuText.roundLabel("E1", EN) == "East 1")
    check("round ja", GameMenuText.roundLabel("S2", JA) == "南2")
    check("round zh", GameMenuText.roundLabel("W3", ZH) == "西3")
    check("round unknown echoed", GameMenuText.roundLabel("", EN) == "")

    // 9. Tag parsing ----------------------------------------------------------------
    check("tag zh", GameMenuText.languageOf("zh-Hans", EN) == ZH)
    check("tag en", GameMenuText.languageOf("en", ZH) == EN)
    check("tag ja", GameMenuText.languageOf("ja", EN) == JA)
    check("empty tag keeps current", GameMenuText.languageOf("", JA) == JA)
    check("null tag keeps current", GameMenuText.languageOf(null as String?, JA) == JA)
    check("unknown tag keeps current", GameMenuText.languageOf("ko", JA) == JA)
    check("snapshot without the field keeps current",
        GameMenuText.languageOf(JSONObject().put("running", true), EN) == EN)
    check("snapshot with the field applies",
        GameMenuText.languageOf(JSONObject().put("uiLanguage", "ja"), EN) == JA)

    // 10. Localizer state machine ---------------------------------------------------
    val state = com.yefeng.majmax.hookprobe.helper.InGameLocalizer.State()
    check("state starts Chinese", state.language == ZH)
    check("first tag changes nothing (same language)", !state.apply("zh-Hans"))
    check("switch to English reports a change", state.apply("en") && state.language == EN)
    check("same tag again reports no change", !state.apply("en"))
    check("missing tag keeps English", !state.apply("") && state.language == EN)
    check("switch to Japanese", state.apply("ja") && state.language == JA)
    state.force(ZH)
    check("force seeds without a tag", state.language == ZH && state.tag == "")

    // 11. Overlay vs in-game language sources -----------------------------------------
    // The shared table is what the overlay re-reads; a tag that names nothing keeps the
    // current value, which is what protects the in-game panel from a manager-side source.
    check("overlay re-read keeps the current language for a blank tag",
        GameMenuText.languageOf("", JA) == JA)
    check("a host tag still moves the copy", GameMenuText.languageOf("en", JA) == EN)
    check("resolveTag vocabulary covers all three",
        listOf("zh-Hans", "en", "ja").all { GameMenuText.languageOf(it, ZH) == GameMenuText.languageOf(it, EN) })
    check("a panel repaint signature is language-sensitive",
        RecommendationPanelOverlay.displayLines(
            JSONObject().put("rows", JSONArray()).put("scores", JSONArray()).put("shanten", 2), EN
        ).map { it.text } !=
            RecommendationPanelOverlay.displayLines(
                JSONObject().put("rows", JSONArray()).put("scores", JSONArray()).put("shanten", 2), JA
            ).map { it.text })

    println("\n=== $passed passed, $failed failed ===")
    if (failed != 0) kotlin.system.exitProcess(1)
}
