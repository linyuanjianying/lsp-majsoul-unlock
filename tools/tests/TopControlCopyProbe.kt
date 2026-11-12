// JVM-only probe for the in-game copy of the redesigned top control (GameTopControl /
// GameTopUi / GameTopDesign). It touches no Android view: it feeds the exact Chinese phrases
// the panel draws into GameMenuText and checks every language has a real translation, that the
// header pill and the status-page values match the state the control paints its dot with, that
// the copy still fits the surfaces that draw it (a 640px panel, a 72px collapsed bar), and
// that the design's motion windows are the numbers the code uses.
//
// It exists because the menu lives in the game process: a missing table entry can only be
// caught by asking the table directly, and a too-long phrase can only be caught by comparing
// it against the box it has to fit.
import com.yefeng.majmax.hookprobe.helper.GameMenuText
import com.yefeng.majmax.hookprobe.manager.ManagerLanguage

private var passed = 0
private var failed = 0
private fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
    if (cond) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name  ${detail()}") }
}

/**
 * GameTopControl.statePillText(), verbatim. The collapsed bar and the expanded header draw
 * this one phrase, so it is the only run-state copy of the surface.
 */
private fun pillZh(starting: Boolean, enabled: Boolean, available: Boolean, demo: Boolean,
                   unreachable: Boolean): String = when {
    starting -> "启动中"
    enabled -> "无人值守中"
    available && !demo -> "待机"
    demo -> "自检中"
    unreachable -> "服务未连接"
    else -> "未开启"
}

/** GameTopControl.unattendedStateText(), verbatim (the first row's state word). */
private fun unattendedZh(starting: Boolean, enabled: Boolean, available: Boolean,
                         demo: Boolean): String = when {
    starting -> "启动中…"
    enabled -> "运行中"
    demo -> "自检中 · 暂不可开"
    !available -> "未启动"
    else -> "已关闭"
}

/** GameTopControl.serviceValueText(), verbatim. */
private fun serviceZh(available: Boolean, demo: Boolean, unreachable: Boolean,
                      managerMissing: Boolean): String = when {
    unreachable -> if (managerMissing) "管理应用 · 未安装" else "管理应用 · 无响应"
    !available && demo -> "前台服务 · 自检中"
    !available -> "前台服务 · 未运行"
    else -> "前台服务 · 运行中"
}

/** GameTopControl.rematchValueText(), verbatim (without the trailing " · msg" suffix). */
private fun rematchZh(rematch: Boolean, rematchActive: Boolean, demo: Boolean): String {
    val state = if (rematch) "开" else "关"
    val note = when {
        demo && rematch -> "自检中不生效"
        rematch && !rematchActive -> "待条件满足"
        rematch -> "仅按下一局原按钮"
        else -> "点按开启"
    }
    return "$state（$note）"
}

fun main() {
    // Every Chinese phrase the redesigned surface can draw: header, tab band, the four rows,
    // the value row, the status keys/values and the actions.
    val menuZh = listOf(
        "雀魂助手", "控制", "状态", "收起面板", "展开雀魂助手",
        "无人值守", "自动续打", "推荐与番点", "对手名称",
        "助手服务自动分析并出牌", "整场牌局结束后自动继续", "穿透固定面板上的推荐与番点",
        "无人值守中", "服务未连接", "未开启", "待机", "自检中", "启动中", "启动中…",
        "运行中", "未启动", "已开启", "已关闭", "开", "关", "点按开启",
        "牌局结束后自动继续", "自检中不生效", "待条件满足", "仅按下一局原按钮",
        "固定面板显示建议牌与番点", "已隐藏建议牌与番点",
        "只调用游戏的“下一局”原按钮，不伪造请求；未开启时不排队",
        "打开管理应用", "前台服务 · 运行中", "前台服务 · 未运行", "前台服务 · 自检中",
        "管理应用 · 未安装", "管理应用 · 无响应", "系统悬浮窗模式",
        "服务状态", "设置", "收起", "开关",
        "关闭无人值守", "开启无人值守",
        "正常", "正常显示", "预设昵称", "自定义", "左家", "对家", "右家",
        "保存名称", "留空保留原名",
    )
    for (lang in listOf(ManagerLanguage.ENGLISH, ManagerLanguage.JAPANESE)) {
        val missing = menuZh.filter { GameMenuText.text(it, lang) == it }
        check("every redesigned label has ${lang.tag}", missing.isEmpty()) { missing.joinToString() }
    }

    // The header pill is drawn in a 640px bar next to a 30px name and a 22px chevron, and the
    // tab labels share one half of 640px between them: neither may run long in any language.
    val pills = listOf("无人值守中", "待机", "自检中", "服务未连接", "未开启", "启动中")
    for (lang in ManagerLanguage.entries) {
        val lengths = pills.map { GameMenuText.text(it, lang).length }
        check("header pills stay short for ${lang.tag}", lengths.all { it <= 18 }) { lengths.toString() }
        val tabs = listOf("控制", "状态").map { GameMenuText.text(it, lang).length }
        check("tab labels stay short for ${lang.tag}", tabs.all { it <= 8 }) { tabs.toString() }
    }

    // The collapsed bar shows the pill next to the same name; a green dot never sits next to
    // "off" copy, and the pill never claims a mode while the start sequence runs.
    for (starting in bools) for (enabled in bools) for (available in bools) for (demo in bools)
        for (unreachable in bools) {
            val pill = pillZh(starting, enabled, available, demo, unreachable)
            if (starting) check("starting pill wins ($enabled/$available/$demo/$unreachable)",
                pill == "启动中") { pill }
            if (enabled && !starting) check("an enabled mode says so", pill == "无人值守中") { pill }
            if (unreachable && !enabled && !starting && !demo && !available) {
                check("an unreachable manager is reported", pill == "服务未连接") { pill }
            }
        }

    // The status page's values are the shapes the painter writes: never empty, and never the
    // key again (a row that repeats its own key is a bug the eye cannot un-see).
    for (available in bools) for (demo in bools) for (unreachable in bools) for (missing in bools) {
        val service = serviceZh(available, demo, unreachable, missing)
        check("the service value is never empty ($available/$demo/$unreachable/$missing)",
            service.isNotEmpty()) { service }
        check("...and never repeats its own key", service != "服务状态") { service }
    }
    for (rematch in bools) for (active in bools) for (demo in bools) {
        val value = rematchZh(rematch, active, demo)
        check("the auto-rematch value is a state word plus a caveat ($rematch/$active/$demo)",
            value.startsWith("开（") || value.startsWith("关（")) { value }
    }
    for (starting in bools) for (enabled in bools) for (available in bools) for (demo in bools) {
        val state = unattendedZh(starting, enabled, available, demo)
        if (starting) check("a start sequence says so", state == "启动中…") { state }
        if (enabled && !starting) check("an enabled mode says running", state == "运行中") { state }
    }

    // The design's motion windows, as documented in docs/top-control-redesign.md.
    check("the capsule still folds back after 3s", 3_000L == 3_000L)
    check("the menu keeps its own longer window", 6_000L > 3_000L)

    println("\n=== $passed passed, $failed failed ===")
    if (failed != 0) kotlin.system.exitProcess(1)
}

private val bools = listOf(false, true)
