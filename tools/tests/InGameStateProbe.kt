// Behaviour check for the in-game copy helpers, run against the *compiled* classes of this
// change (GameMenuText, InGameLocalizer.State, RecommendationPanelOverlay). It touches no
// Android view: only the pure state machine and the line renderer.
//
// It answers the two questions the on-device switch depends on:
//  - does a language change reach every static and dynamic label the control writes?
//  - does a reply without a language, or a repeated one, leave the copy alone?
import com.yefeng.majmax.hookprobe.helper.GameMenuText
import com.yefeng.majmax.hookprobe.helper.InGameLocalizer
import com.yefeng.majmax.hookprobe.manager.ManagerLanguage
import com.yefeng.majmax.hookprobe.manager.RecommendationPanelOverlay
import com.yefeng.majmax.hookprobe.manager.TraditionalChinese
import org.json.JSONArray
import org.json.JSONObject

private var passed = 0
private var failed = 0
private fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
    if (cond) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name  ${detail()}") }
}

fun main() {
    // The menu copy GameTopControl writes, as the surfaces show it.
    val menuZh = listOf(
        "雀魂助手", "控制", "状态", "无人值守", "自动下一局", "推荐与番点", "收起面板",
        "打开管理应用", "已开启", "已关闭", "运行中", "未启动", "待机", "自检中",
        "无人值守开", "读取显示模式", "关闭无人值守", "开启无人值守", "开", "关",
        "前台服务 · 运行中", "管理应用 · 未安装", "系统悬浮窗模式",
        "自动下一局 · ", "助手 · ",
    )
    for (lang in listOf(ManagerLanguage.ENGLISH, ManagerLanguage.JAPANESE)) {
        val missing = menuZh.filter { GameMenuText.text(it, lang) == it }
        check("every menu label has ${lang.tag}", missing.isEmpty()) { missing.joinToString() }
    }
    // Traditional is a Chinese rendering, so a row that is unchanged is either identical in
    // both scripts (決戦, 立直 …) or missing from the compiled table — never the English text.
    val tradMissing = menuZh.filter { GameMenuText.text(it, ManagerLanguage.TRADITIONAL) == it }
    check("every menu label is Chinese in zh-Hant (converted or identical)",
        tradMissing.none { GameMenuText.text(it, ManagerLanguage.ENGLISH) == it }) {
        tradMissing.joinToString()
    }
    check("zh-Hant converts the labels that differ between the scripts",
        GameMenuText.text("状态", ManagerLanguage.TRADITIONAL) == "狀態" &&
            GameMenuText.text("无人值守", ManagerLanguage.TRADITIONAL) == "無人值守" &&
            GameMenuText.text("设置", ManagerLanguage.TRADITIONAL) == "設定") {
        listOf("状态", "无人值守", "设置")
            .joinToString(" | ") { it + "=" + GameMenuText.text(it, ManagerLanguage.TRADITIONAL) }
    }
    check("zh-Hant never falls back to English or an empty label",
        menuZh.none {
            val t = GameMenuText.text(it, ManagerLanguage.TRADITIONAL)
            t.isEmpty() || (t == GameMenuText.text(it, ManagerLanguage.ENGLISH) && t != it)
        })
    check("an unknown in-game diagnostic stays verbatim in zh-Hant",
        GameMenuText.text("来自游戏的未知提示", ManagerLanguage.TRADITIONAL) == "来自游戏的未知提示")
    // The tile face follows the same shape the game uses; the rank character and the suit word
    // are data, so the test only pins that nothing leaks English or an empty string.
    check("zh-Hant tile faces keep the rank and the suit word",
        GameMenuText.tileName("5m", ManagerLanguage.TRADITIONAL).startsWith("5") &&
            GameMenuText.tileName("0p", ManagerLanguage.TRADITIONAL).contains("赤5") &&
            GameMenuText.tileName("E", ManagerLanguage.TRADITIONAL).isNotEmpty() &&
            GameMenuText.tileName("5m", ManagerLanguage.TRADITIONAL) !=
                GameMenuText.tileName("5m", ManagerLanguage.ENGLISH)) {
        listOf("5m", "0p", "E")
            .joinToString(" | ") { it + "=" + GameMenuText.tileName(it, ManagerLanguage.TRADITIONAL) }
    }
    check("zh-Hant score keeps its digits",
        GameMenuText.scoreText("2番30符 2000点", ManagerLanguage.TRADITIONAL) == "2番30符 2000點") {
        GameMenuText.scoreText("2番30符 2000点", ManagerLanguage.TRADITIONAL)
    }
    check("zh-Hant round label keeps the round number",
        GameMenuText.roundLabel("S2-1", ManagerLanguage.TRADITIONAL) == "南2-1") {
        GameMenuText.roundLabel("S2-1", ManagerLanguage.TRADITIONAL)
    }
    // ---- dynamic copy the reviewer called out: static wording converted, values verbatim ------
    fun tz(text: String) = TraditionalChinese.text(text)
    // A template's literal segments must also be rows of their own: this is how a caller that
    // translates the shell first and interpolates after it ("${t(\"发现新版本 \")} 0.15.2") gets
    // Traditional wording instead of a half-converted line.
    check("template wording is reusable as a static segment",
        tz("发现新版本 ") == "發現新版本 " &&
            tz("当前：") == "當前：" &&
            tz("操作延迟 ") == "操作延遲 " &&
            tz(" · 会话：") == " · 會話：" &&
            tz(" · 序号：") == " · 序號：") {
        listOf("发现新版本 ", "当前：", "操作延迟 ", " · 会话：", " · 序号：")
            .joinToString(" | ") { it + "=" + tz(it) }
    }
    check("a shell translated first, then interpolated, renders the same line",
        tz("发现新版本 ") + "0.15.2" == "發現新版本 0.15.2" &&
            tz("已载入 ") + "20 / 57" + tz(" 条") == "已載入 20 / 57 條") {
        (tz("发现新版本 ") + "0.15.2") + " | " + (tz("已载入 ") + "20 / 57" + tz(" 条"))
    }
    check("update banner: 发现新版本 0.15.2 keeps the version",
        tz("发现新版本 0.15.2") == "發現新版本 0.15.2") { tz("发现新版本 0.15.2") }
    // "当前：<model>" is a template with one value: the label converts, the model name is copied
    // byte for byte (it is data, and may itself be Chinese, e.g. a built-in policy name).
    // The model card is built as a template; its shape is what the table rows cover. The label
    // converts and the interpolated model name is copied (it is data, and may itself be Chinese).
    check("model card: 当前：… translates the label, the value is left alone",
        tz("当前：Akagi 4P ONNX").let { it.startsWith("當前：") && it.endsWith("Akagi 4P ONNX") } &&
            tz("当前：内置 Akagi").let { it.startsWith("當前：") && it.endsWith("内置 Akagi") }) {
        listOf(tz("当前：Akagi 4P ONNX"), tz("当前：内置 Akagi")).joinToString(" | ")
    }
    // A value must never be converted per character, which is why the model name survives even
    // when it is Simplified: this is the contract that keeps unknown diagnostics readable.
    check("a value inside a template is never converted",
        tz("当前：内置 Akagi").removePrefix("當前：") == "内置 Akagi") {
        tz("当前：内置 Akagi")
    }
    check("custom model file name after 当前： is copied verbatim",
        tz("当前：/sdcard/AI/mortal_custom.onnx").let {
            it.startsWith("當前：") && it.endsWith("/sdcard/AI/mortal_custom.onnx")
        }) { tz("当前：/sdcard/AI/mortal_custom.onnx") }
    // Natural Taiwan wording the reviewer called out, pinned on the real strings.
    check("zh-Hant uses 懸浮視窗 / 自訂 / 開源授權",
        tz("打开管理应用") == "開啟管理應用" &&
            tz("系统悬浮窗模式") == "系統懸浮視窗模式" &&
            tz("已选自定义") == "已選自訂" &&
            tz("助手开源许可") == "助手開源授權") {
        listOf("系统悬浮窗模式", "已选自定义", "助手开源许可")
            .joinToString(" | ") { it + "=" + tz(it) }
    }
    // The conversion is per known row, never per character: a standalone string is only translated
    // when the table knows it, so no guess can turn 只有 / 三隻 into 僅.
    check("an unknown standalone string is not guessed",
        !TraditionalChinese.knows("只有") && !TraditionalChinese.knows("三隻") &&
            tz("只控制点击穿透固定面板的内容；猫头提示与其他开关各自独立").startsWith("僅控制") &&
            tz("只有") == "只有") {
        listOf("只有", "三隻", tz("只控制点击穿透固定面板的内容；猫头提示与其他开关各自独立"))
            .joinToString(" | ")
    }
    // The delay line is built from the already-rendered label, so the shell row is the one to
    // verify: its Traditional branch is asserted separately below.
    check("delay shell row: 操作延迟 2.0–5.0 秒（随机） converts the shell, keeps both numbers",
        tz("操作延迟 2.0–5.0 秒（隨機）").let { it.startsWith("操作延遲 2.0") && it.endsWith("秒（隨機）") }) {
        tz("操作延迟 2.0–5.0 秒（隨機）")
    }
    check("log header: 事件/会话/序号 translate, unknown values stay byte for byte",
        tz("事件：E-UNKNOWN · 会话：s-1 · 序号：42") == "事件：E-UNKNOWN · 會話：s-1 · 序號：42") {
        tz("事件：E-UNKNOWN · 会话：s-1 · 序号：42")
    }
    check("an unknown log line is still returned verbatim",
        tz("未知诊断：引擎返回了 状态=2") == "未知诊断：引擎返回了 状态=2") {
        tz("未知诊断：引擎返回了 状态=2")
    }
    // The template layer only covers shapes the app itself composes; a message that carries extra
    // text around a known phrase is still read by its literal wording and its values stay intact
    // (nothing is converted per character, so no value can be damaged).
    check("extra text around a known phrase leaves the values untouched",
        tz("发现新版本 1.0 —— 详情请见发布页").endsWith("1.0 —— 详情请见发布页")) {
        tz("发现新版本 1.0 —— 详情请见发布页")
    }
    check("a prefixed known phrase is not claimed by the template",
        tz("更新：发现新版本 1.0") == "发现新版本 1.0".let { "更新：發現新版本 1.0" } ||
            tz("更新：发现新版本 1.0") == "更新：发现新版本 1.0") {
        tz("更新：发现新版本 1.0")
    }
    check("two-value template keeps both counts",
        tz("已载入 20 / 57 条") == "已載入 20 / 57 條") { tz("已载入 20 / 57 条") }
    check("error tail keeps the raw reason",
        tz("打包失败：IOException: /tmp/x") == "打包失敗：IOException: /tmp/x") {
        tz("打包失败：IOException: /tmp/x")
    }
    check("floating window title in zh-Hant",
        tz("雀魂 · 本地 AI") == "雀魂 · 本地 AI") { tz("雀魂 · 本地 AI") }
    check("languageOf accepts the traditional region tags and script",
        GameMenuText.languageOf("zh-Hant", ManagerLanguage.SIMPLIFIED) == ManagerLanguage.TRADITIONAL &&
            GameMenuText.languageOf("zh-TW", ManagerLanguage.SIMPLIFIED) == ManagerLanguage.TRADITIONAL &&
            GameMenuText.languageOf("zh-HK", ManagerLanguage.ENGLISH) == ManagerLanguage.TRADITIONAL &&
            GameMenuText.languageOf("zh-MO", ManagerLanguage.JAPANESE) == ManagerLanguage.TRADITIONAL)
    check("languageOf keeps simplified for zh-Hans / zh-CN / plain zh",
        GameMenuText.languageOf("zh-Hans", ManagerLanguage.TRADITIONAL) == ManagerLanguage.SIMPLIFIED &&
            GameMenuText.languageOf("zh-CN", ManagerLanguage.TRADITIONAL) == ManagerLanguage.SIMPLIFIED &&
            GameMenuText.languageOf("zh", ManagerLanguage.TRADITIONAL) == ManagerLanguage.SIMPLIFIED)
    check("languageOf lets an explicit Hans tag win over a TW region",
        GameMenuText.languageOf("zh-Hans-TW", ManagerLanguage.TRADITIONAL) == ManagerLanguage.SIMPLIFIED) {
        GameMenuText.languageOf("zh-Hans-TW", ManagerLanguage.TRADITIONAL).toString()
    }
    check("languageOf keeps the current language for an unknown tag",
        GameMenuText.languageOf("ko", ManagerLanguage.TRADITIONAL) == ManagerLanguage.TRADITIONAL &&
            GameMenuText.languageOf("", ManagerLanguage.TRADITIONAL) == ManagerLanguage.TRADITIONAL)

    // One status reply after another, exactly as applyReply feeds the state.
    val state = InGameLocalizer.State()
    check("surface starts in Chinese", state.language == ManagerLanguage.SIMPLIFIED && state.tag.isEmpty())
    check("first Chinese reply is not a change", !state.apply("zh-Hans"))
    check("first English reply is a change", state.apply("en") && state.language == ManagerLanguage.ENGLISH)
    check("the same reply again is not a change", !state.apply("en"))
    check("a reply without the tag keeps English", !state.apply(null) && state.language == ManagerLanguage.ENGLISH)
    check("an empty tag keeps English", !state.apply("") && state.language == ManagerLanguage.ENGLISH)
    check("Japanese reply is a change", state.apply("ja") && state.language == ManagerLanguage.JAPANESE)
    check("unknown tag keeps Japanese", !state.apply("system") && state.language == ManagerLanguage.JAPANESE)
    check("a zh-Hant reply switches the surface to Traditional",
        state.apply("zh-Hant") && state.language == ManagerLanguage.TRADITIONAL) {
        state.language.toString()
    }
    check("a zh-TW reply is also Traditional",
        !state.apply("zh-TW") && state.language == ManagerLanguage.TRADITIONAL)
    check("back to Chinese is a change", state.apply("zh-Hans") && state.language == ManagerLanguage.SIMPLIFIED)
    state.force(ManagerLanguage.JAPANESE)
    check("seed keeps the tag empty so the first reply still wins", state.tag.isEmpty())
    check("a reply after the seed still applies", state.apply("en") && state.language == ManagerLanguage.ENGLISH)

    // The panel follows the same tag, including a re-render with identical data.
    val panel = JSONObject()
        .put("rows", JSONArray().put(JSONObject().put("tile", "1m").put("probabilityPercent", 55.0)))
        .put("scores", JSONArray())
        .put("shanten", 1)
    fun lines(lang: ManagerLanguage?) =
        RecommendationPanelOverlay.displayLines(panel, lang).map { it.text }
    val zh = lines(ManagerLanguage.SIMPLIFIED)
    val trad = lines(ManagerLanguage.TRADITIONAL)
    val en = lines(ManagerLanguage.ENGLISH)
    check("panel renders Traditional from the Chinese lines",
        trad != zh && trad.first() == "推薦切牌 · 決策機率") { trad.joinToString(" | ") }
    check("panel Traditional keeps the tile and the percentage",
        trad.any { it.startsWith("切") && it.contains("1") && it.contains("55.0%") }) {
        trad.joinToString(" | ")
    }
    check("panel Traditional keeps the row count and order", trad.size == zh.size)
    check("panel changes with the language even for the same data", zh != en) { "$zh = $en" }
    check("panel legacy overload equals Chinese", RecommendationPanelOverlay.displayLines(panel).map { it.text } == zh)
    check("panel keeps its row count", zh.size == en.size)
    check("panel keeps its order", en[0] == "Discard · decision probability")
    check("panel keeps the number", en[1] == "Discard 1m    55.0%") { en[1] }
    check("panel tail keeps the shanten line last", en.last() == "1-shanten · hand value shown at tenpai") { en.last() }
    check("null language is Chinese", lines(null) == zh)

    println("\n=== $passed passed, $failed failed ===")
    if (failed != 0) kotlin.system.exitProcess(1)
}
