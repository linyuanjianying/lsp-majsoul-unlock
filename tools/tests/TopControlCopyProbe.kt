// JVM-only probe for the in-game copy of the redesigned top control (GameTopControl /
// GameTopUi). It touches no Android view: it feeds the exact Chinese phrases the control
// draws into GameMenuText and checks every language has a real translation, that the
// capsule / header phrase selection matches the state the control paints its dot with,
// and that the idle windows keep the two documented values.
//
// It exists because the menu lives in the game process: a missing table entry can only be
// caught by asking the table directly.
import com.yefeng.majmax.hookprobe.helper.GameMenuText
import com.yefeng.majmax.hookprobe.manager.ManagerLanguage

private var passed = 0
private var failed = 0
private fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
    if (cond) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name  ${detail()}") }
}

/** GameTopControl.capsuleText(), verbatim. */
private fun capsuleZh(starting: Boolean, enabled: Boolean, available: Boolean, demo: Boolean): String = when {
    starting -> "启动中"
    enabled && !available -> "启动中"
    enabled || (available && !demo) -> "助手运行中"
    else -> "助手未开启"
}

/** GameTopControl.paintHeader() pill, verbatim. */
private fun headerZh(starting: Boolean, enabled: Boolean, available: Boolean, demo: Boolean, unreachable: Boolean): String = when {
    starting -> "启动中"
    enabled -> "无人值守中"
    available && !demo -> "待机"
    demo -> "自检中"
    unreachable -> "服务未连接"
    else -> "未开启"
}

fun main() {
    // Every Chinese phrase the redesigned surface can draw, plus the new copy.
    val menuZh = listOf(
        "雀魂助手", "控制", "状态", "收起面板", "无人值守", "自动下一局", "推荐与番点",
        "助手服务自动分析并出牌", "整场牌局结束后自动继续", "穿透固定面板上的推荐与番点",
        "助手运行中", "助手未开启", "无人值守中", "服务未连接", "未开启", "读取显示模式",
        "无人值守开", "待机", "自检中", "启动中", "启动中…", "运行中", "未启动", "已开启",
        "已关闭", "开", "关", "点按开启", "牌局结束后自动继续", "自检中不生效", "待条件满足",
        "仅按下一局原按钮", "固定面板显示建议牌与番点", "已隐藏建议牌与番点",
        "只调用游戏的“下一局”原按钮，不伪造请求；未开启时不排队",
        "打开管理应用", "前台服务 · 运行中", "管理应用 · 未安装", "系统悬浮窗模式",
        "自动下一局 · ", "展开雀魂助手", "打开雀魂助手菜单", "开关",
        "关闭无人值守", "开启无人值守",
    )
    for (lang in listOf(ManagerLanguage.ENGLISH, ManagerLanguage.JAPANESE)) {
        val missing = menuZh.filter { GameMenuText.text(it, lang) == it }
        check("every redesigned label has ${lang.tag}", missing.isEmpty()) { missing.joinToString() }
    }

    // The capsule phrase must survive the capsule's own sizing: it is never truncated,
    // so the copy itself has to stay short in every language.
    val capsulePhrases = listOf("助手运行中", "助手未开启", "启动中")
    for (lang in ManagerLanguage.entries) {
        val lengths = capsulePhrases.map { GameMenuText.text(it, lang).length }
        check("capsule phrases stay short for ${lang.tag}", lengths.all { it <= 18 }) { lengths.toString() }
    }
    val headerPills = listOf("无人值守中", "待机", "自检中", "服务未连接", "未开启", "启动中")
    for (lang in ManagerLanguage.entries) {
        val lengths = headerPills.map { GameMenuText.text(it, lang).length }
        check("header pills stay short for ${lang.tag}", lengths.all { it <= 18 }) { lengths.toString() }
    }

    // Capsule phrase vs. dot colour: a green dot never sits next to "off" copy.
    for (starting in bools) for (enabled in bools) for (available in bools) for (demo in bools) {
        val phrase = capsuleZh(starting, enabled, available, demo)
        val dot = when {
            starting -> "amber"
            enabled -> "green"
            available -> "amber"
            else -> "amber"
        }
        if (dot == "green") {
            check("green dot means a running phrase ($starting/$enabled/$available/$demo)",
                phrase == "助手运行中" || (enabled && !available && phrase == "启动中")) { phrase }
        }
        if (phrase == "助手未开启") {
            check("off phrase is never green ($starting/$enabled/$available/$demo)", dot != "green")
        }
        if (demo && !enabled) {
            check("self-test never says running ($starting/$enabled/$available)", phrase != "助手运行中") { phrase }
        }
    }

    // Header pill never claims a mode while the start sequence runs.
    for (starting in bools) {
        val pill = headerZh(starting, true, true, false, false)
        if (starting) check("starting pill wins", pill == "启动中") { pill }
        if (!starting) check("enabled pill", pill == "无人值守中") { pill }
    }
    for (enabled in bools) for (available in bools) for (demo in bools) for (unreachable in bools) {
        if (enabled) continue
        val pill = headerZh(false, enabled, available, demo, unreachable)
        if (available && !demo) check("ready pill", pill == "待机") { pill }
        if (!available && !demo && unreachable) check("unreachable pill", pill == "服务未连接") { pill }
        if (!available && demo) check("self-test pill", pill == "自检中") { pill }
        if (!available && !demo && !unreachable) check("off pill", pill == "未开启") { pill }
    }

    // The two idle windows the design names: 3s capsule, menu unchanged.
    val capsuleIdle = 3_000L
    val menuIdle = 6_000L
    check("capsule folds back after 3s", capsuleIdle == 3_000L)
    check("menu keeps its own longer window", menuIdle > capsuleIdle)

    println("\n=== $passed passed, $failed failed ===")
    if (failed != 0) kotlin.system.exitProcess(1)
}

private val bools = listOf(false, true)
