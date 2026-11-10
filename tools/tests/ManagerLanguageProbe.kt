// JVM-only regression probe for the manager language switch.
//
// Compiles the real sources (ManagerLanguage.kt, AiScreen.kt, AutoDelaySettings.kt) against the
// already-built Kotlin classes with -Xfriend-paths, so `internal` members are reachable without
// touching the project. Only pure functions are exercised: no Context, no composition.
//
// The "follow system" layer is covered exhaustively here because it is pure logic: the choice
// vocabulary, its storage round-trip, the ordered device-locale resolution, the separation of
// stored choice from effective language, and the tag parser used by UiLanguage.applyTag.
import com.yefeng.majmax.hookprobe.manager.AutoDelayRange
import com.yefeng.majmax.hookprobe.manager.LOG_LEVEL_ALL
import com.yefeng.majmax.hookprobe.manager.ManagerLanguage
import com.yefeng.majmax.hookprobe.manager.ManagerLanguageChoice
import com.yefeng.majmax.hookprobe.manager.ManagerLanguageText
import com.yefeng.majmax.hookprobe.manager.ManagerSystemLanguage
import com.yefeng.majmax.hookprobe.manager.describe
import com.yefeng.majmax.hookprobe.manager.label
import com.yefeng.majmax.hookprobe.manager.localizedDelayLabel
import com.yefeng.majmax.hookprobe.manager.localizedUpdateFailure
import com.yefeng.majmax.hookprobe.manager.logLevelFilters
import com.yefeng.majmax.hookprobe.manager.logTabs
import com.yefeng.majmax.hookprobe.manager.modelMessageText
import com.yefeng.majmax.hookprobe.manager.topBarLanguageTag
import com.yefeng.majmax.hookprobe.manager.tr
import java.util.Locale

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
    if (cond) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name  ${detail()}") }
}

private val ZH = ManagerLanguage.SIMPLIFIED
private val EN = ManagerLanguage.ENGLISH
private val JA = ManagerLanguage.JAPANESE

private val SYS = ManagerLanguageChoice.SYSTEM
private val C_ZH = ManagerLanguageChoice.SIMPLIFIED
private val C_EN = ManagerLanguageChoice.ENGLISH
private val C_JA = ManagerLanguageChoice.JAPANESE

private fun trim(raw: String, lang: ManagerLanguage) = ManagerLanguageText.trim(raw, lang)

fun main() {
    // 1. Enum / persistence vocabulary ------------------------------------------------
    check("three languages, Chinese first", ManagerLanguage.entries.first() == ZH) {
        ManagerLanguage.entries.joinToString()
    }
    check("ManagerLanguage never contains SYSTEM", ManagerLanguage.entries.none { it.tag == "system" })
    check("fromTag round-trips tags",
        ManagerLanguage.entries.all { ManagerLanguage.fromTag(it.tag) == it })
    check("fromTag unknown/null falls back to Chinese",
        ManagerLanguage.fromTag("kr") == ZH && ManagerLanguage.fromTag(null) == ZH &&
            ManagerLanguage.fromTag("") == ZH)
    check("fromTag is case-insensitive", ManagerLanguage.fromTag("EN") == EN)
    check("labels stay in their own script",
        ZH.label == "简体中文" && EN.label == "English" && JA.label == "日本語")

    // 2. Stored choice: four options, SYSTEM first and default ------------------------
    check("four choices, SYSTEM first", ManagerLanguageChoice.entries.first() == SYS) {
        ManagerLanguageChoice.entries.joinToString()
    }
    check("choice tags are the stored values",
        SYS.tag == "system" && C_ZH.tag == "zh-Hans" && C_EN.tag == "en" && C_JA.tag == "ja")
    check("fromTag round-trips every choice tag",
        ManagerLanguageChoice.entries.all { ManagerLanguageChoice.fromTag(it.tag) == it })
    check("missing stored value means follow-system (fresh install default)",
        ManagerLanguageChoice.fromStored(null) == SYS && ManagerLanguageChoice.fromStored("") == SYS)
    check("unknown stored value means follow-system",
        ManagerLanguageChoice.fromStored("kr") == SYS)
    check("a stored explicit language is preserved, not migrated to system",
        ManagerLanguageChoice.fromStored("zh-Hans") == C_ZH &&
            ManagerLanguageChoice.fromStored("en") == C_EN &&
            ManagerLanguageChoice.fromStored("ja") == C_JA)
    check("historic zh spelling reads as simplified Chinese",
        ManagerLanguageChoice.fromStored("zh") == C_ZH)
    check("stored explicit values are case-insensitive",
        ManagerLanguageChoice.fromStored("EN") == C_EN)
    check("explicitOrNull is null only for SYSTEM",
        SYS.explicitOrNull() == null && C_ZH.explicitOrNull() == ZH &&
            C_EN.explicitOrNull() == EN && C_JA.explicitOrNull() == JA)

    // 3. Device-locale resolution: ja > zh > en --------------------------------------
    run {
        fun r(tag: String) = ManagerSystemLanguage.resolve(Locale.forLanguageTag(tag))
        check("system ja -> Japanese", r("ja") == JA && r("ja-JP") == JA)
        check("system zh -> simplified Chinese", r("zh") == ZH && r("zh-CN") == ZH)
        check("system zh-TW/zh-HK/zh-Hant still -> simplified Chinese (only one Chinese build)",
            r("zh-TW") == ZH && r("zh-HK") == ZH && r("zh-Hant") == ZH)
        check("system en -> English", r("en") == EN && r("en-US") == EN)
        check("any other system language -> English",
            r("ko") == EN && r("fr-FR") == EN && r("de") == EN && r("ru") == EN)
        check("null locale -> English (safe fallback, never Chinese-by-accident)",
            ManagerSystemLanguage.resolve(null as Locale?) == EN)
        check("empty locale list -> English",
            ManagerSystemLanguage.resolve(emptyList<Locale>()) == EN)
        check("only the primary locale decides",
            ManagerSystemLanguage.resolve(listOf(Locale.forLanguageTag("ko"),
                Locale.forLanguageTag("ja"))) == EN)
        check("upper-case language tags still resolve",
            ManagerSystemLanguage.resolve(Locale.forLanguageTag("JA-jp")) == JA)
    }

    // 4. Effective language = choice, with SYSTEM delegated to the device ------------
    run {
        check("explicit choices ignore the device",
            C_EN.effective(JA) == EN && C_JA.effective(ZH) == JA && C_ZH.effective(EN) == ZH)
        check("SYSTEM follows the device",
            SYS.effective(JA) == JA && SYS.effective(ZH) == ZH && SYS.effective(EN) == EN)
        check("of() maps an effective language back to its menu entry",
            ManagerLanguageChoice.of(JA) == C_JA && ManagerLanguageChoice.of(ZH) == C_ZH &&
                ManagerLanguageChoice.of(EN) == C_EN)
    }

    // 5. tr() helper ----------------------------------------------------------------
    check("tr returns Chinese verbatim", ZH.tr("停止助手", "Stop", "停止") == "停止助手")
    check("tr returns English", EN.tr("停止助手", "Stop", "停止") == "Stop")
    check("tr returns Japanese", JA.tr("停止助手", "Stop", "停止") == "停止")
    check("tr keeps an empty Chinese slot empty (supplementary lines)", EN.tr("", "Only EN", "のみ") == "Only EN")

    // 6. Menu labels and top-bar tags -----------------------------------------------
    run {
        check("follow-system label is translated",
            SYS.label(EN) == "Follow system" && SYS.label(JA) == "システムに従う" &&
                SYS.label(ZH) == "跟随系统")
        check("the three language labels are endonyms in every UI language",
            C_ZH.label(EN) == "简体中文" && C_EN.label(JA) == "English" && C_JA.label(ZH) == "日本語")
        check("describe() shows the resolved language while following",
            SYS.describe(JA) == "システムに従う（日本語）" && SYS.describe(EN) == "Follow system (English)") {
            SYS.describe(JA)
        }
        check("describe() of an explicit choice is just its label",
            C_JA.describe(EN) == "日本語")
        check("top bar tag, explicit choices",
            topBarLanguageTag(C_ZH, ZH) == "中文" && topBarLanguageTag(C_EN, ZH) == "EN" &&
                topBarLanguageTag(C_JA, ZH) == "日本語")
        check("top bar tag, following system",
            topBarLanguageTag(SYS, ZH) == "中文" && topBarLanguageTag(SYS, EN) == "EN" &&
                topBarLanguageTag(SYS, JA) == "日本語")
    }

    // 7. Tag parser used by UiLanguage.applyTag (same rules as the stored reader) ------
    run {
        fun resolved(tag: String?) = ManagerLanguageChoice.fromTag(tag).explicitOrNull()
        check("applyTag accepts zh-Hans / zh / zh-CN / zh-TW", resolved("zh-Hans") == ZH &&
            resolved("zh") == ZH && resolved("zh-CN") == ZH && resolved("zh-TW") == ZH)
        check("applyTag accepts en / en-US and ja / ja-JP",
            resolved("en") == EN && resolved("en-US") == EN && resolved("ja") == JA &&
                resolved("ja-JP") == JA)
        check("applyTag is case-insensitive and tolerates a region suffix in caps",
            resolved("JA") == JA && resolved("JA-jp") == JA && resolved("EN-us") == EN)
        check("applyTag rejects an unsupported or empty tag (no silent switch)",
            resolved("ko") == null && resolved("") == null && resolved(null) == null)
        check("applyTag does not accept 'system' as a language",
            resolved("system") == null)
    }

    // 8. Chinese must never be re-written -------------------------------------------
    run {
        val samples = listOf(
            "助手未开启", "无人值守模式已关闭", "随机等待 2.3 秒 · 可随时关闭",
            "推荐已过期，等待新的完整牌局状态", "本地引擎异常，等待恢复", "来自游戏的未知提示",
        )
        check("SIMPLIFIED is an identity mapping",
            samples.all { trim(it, ZH) == it }) { samples.joinToString(" | ") }
    }

    // 9. Known service messages get translated; numbers survive ----------------------
    run {
        val en = trim("随机等待 2.3 秒 · 可随时关闭", EN)
        val ja = trim("随机等待 2.3 秒 · 可随时关闭", JA)
        check("timer message: English keeps 2.3", en == "Waiting 2.3 s · you can stop it anytime") { en }
        check("timer message: Japanese keeps 2.3", ja == "2.3 秒待機中 · いつでも停止できます") { ja }

        val rejected = trim("手牌校验未通过，等待新的完整牌局状态", EN)
        check("rejection reason: English", rejected == "the hand check failed, waiting for a full game state") { rejected }
        val rejectedJa = trim("可以和牌，请手动确认，等待新的完整牌局状态", JA)
        check("rejection reason: Japanese", rejectedJa.startsWith("和了できます")) { rejectedJa }

        check("unattended off", trim("无人值守模式已关闭", EN) == "Unattended mode is off")
        check("assistant running", trim("助手已开启", JA) == "アシストは起動中です")

        check("surrounding blanks are ignored", trim("  助手已开启  ", EN) == "Assistant is running")
    }

    // 10. Unknown / engine text is preserved, not faked ------------------------------
    run {
        val engine = "仅支持实验性四麻/三麻 Mortal v4；权重文件无效或结构不受支持 (状态码: 2)"
        check("engine error stays verbatim (EN)", trim(engine, EN) == engine)
        check("engine error stays verbatim (JA)", trim(engine, JA) == engine)
        val unknown = "来自游戏的未知提示 · 第 3 局"
        check("unknown diagnostics stay verbatim", trim(unknown, EN) == unknown && trim(unknown, JA) == unknown)
        val halfReason = "某个未收录的原因，等待新的完整牌局状态"
        check("unknown rejection reason is not half-translated", trim(halfReason, EN) == halfReason) {
            trim(halfReason, EN)
        }
        check("blank stays blank", trim("   ", EN) == "   ")
    }

    // 11. Update-check failures -----------------------------------------------------
    run {
        val misconfigured = "更新源配置无效。"
        check("manager's own update failure is translated",
            localizedUpdateFailure(misconfigured, EN) == "The update source is misconfigured." &&
                localizedUpdateFailure(misconfigured, JA) == "更新元の設定が不正です。")
        check("update failure is identity in Chinese",
            localizedUpdateFailure(misconfigured, ZH) == misconfigured)
        val network = "无法连接更新源。"
        check("unreachable source is translated",
            localizedUpdateFailure(network, EN) == "Could not reach the update source.") {
            localizedUpdateFailure(network, EN)
        }
        val platform = "HTTP 403"
        check("platform error text stays verbatim",
            localizedUpdateFailure(platform, EN) == platform && localizedUpdateFailure(platform, JA) == platform)
        val thrown = "Unable to resolve host \"api.github.com\""
        check("thrown network message stays verbatim", localizedUpdateFailure(thrown, EN) == thrown)
    }

    // 12. Log page vocabulary -------------------------------------------------------
    run {
        check("level filter keys are stable and machine levels stay verbatim",
            logLevelFilters(EN) == listOf(LOG_LEVEL_ALL to "All", "INFO" to "INFO",
                "WARN" to "WARN", "ERROR" to "ERROR")) { logLevelFilters(EN).toString() }
        check("level keys do not depend on the language",
            logLevelFilters(ZH).map { it.first } == logLevelFilters(JA).map { it.first })
        check("log tabs, Hook first", logTabs(EN).first() == "Hook log" && logTabs(JA).last() == "アシストログ")
    }

    // 13. Model status line ---------------------------------------------------------
    run {
        val staged = modelMessageText("4 人模型已校验并暂存；下一次完整牌局同步时生效。", EN)
        check("model staged: English keeps player count",
            staged.startsWith("4-player model verified")) { staged }
        val builtIn = modelMessageText("三麻已选择内置策略；当前牌牌保持原模型，下一次完整牌局同步时生效。", JA)
        check("sanma built-in: unknown shape is not half-translated",
            builtIn == "三麻已选择内置策略；当前牌牌保持原模型，下一次完整牌局同步时生效。") { builtIn }
        val builtIn2 = modelMessageText("三麻已选择内置策略；当前牌局保持原模型，下一次完整牌局同步时生效。", JA)
        check("sanma built-in: Japanese", builtIn2.startsWith("三人打ち：内蔵ポリシー")) { builtIn2 }
        val custom = modelMessageText("四麻自定义策略将在下一次完整牌局同步时生效。", EN)
        check("yonma custom: English", custom.startsWith("Four-player: the custom policy")) { custom }
        val removed = modelMessageText("三麻自定义模型已从可选槽位移除。", EN)
        check("removed: English", removed.startsWith("Three-player: the custom model was removed")) { removed }
        val selfTest = modelMessageText("4人 Mortal (.pth) 加载与推理自检通过（实验性）· 812 ms", EN)
        check("self-check: numbers survive", selfTest.contains("812 ms") && selfTest.contains("4-player")) { selfTest }
        val ready = modelMessageText("3人 Akagi ONNX 模型可用 · 45 ms", JA)
        check("ready: Japanese keeps ms", ready.contains("45 ms")) { ready }
        val intro = modelMessageText(
            "支持 Akagi ONNX，以及实验性四麻/三麻 Mortal v4（ONNX/PTH/PT，单文件上限 300 MiB）。" +
                "自动识别人数并保存到对应槽位；兼容的自定义模型仍需手动开启无人值守。模型仅保存在本机。", EN)
        check("intro: English mentions the MiB limit", intro.contains("MiB")) { intro }
        val tool = "模型文件超过 300 MiB 限制"
        check("tool error stays verbatim", modelMessageText(tool, EN) == tool && modelMessageText(tool, JA) == tool)
        check("SIMPLIFIED keeps model text", modelMessageText("四麻自定义策略将在下一次完整牌局同步时生效。", ZH)
            == "四麻自定义策略将在下一次完整牌局同步时生效。")
    }

    // 14. Delay label: one decimal, language-specific suffix ------------------------
    run {
        val random = AutoDelayRange(20, 50)
        val fixed = AutoDelayRange(30, 30)
        check("zh delay label unchanged", localizedDelayLabel(random, ZH) == random.label) { random.label }
        check("en random delay", localizedDelayLabel(random, EN) == "2.0–5.0 s (random)") {
            localizedDelayLabel(random, EN)
        }
        check("en fixed delay", localizedDelayLabel(fixed, EN) == "3.0 s (fixed)")
        check("ja random delay", localizedDelayLabel(random, JA) == "2.0–5.0 秒（ランダム）") {
            localizedDelayLabel(random, JA)
        }
    }

    println("\n$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
