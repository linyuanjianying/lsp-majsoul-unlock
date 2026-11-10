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
    val en = lines(ManagerLanguage.ENGLISH)
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
