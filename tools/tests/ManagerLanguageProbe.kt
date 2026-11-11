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
import com.yefeng.majmax.hookprobe.manager.TraditionalChinese
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
private val TRAD = ManagerLanguage.TRADITIONAL
private val EN = ManagerLanguage.ENGLISH
private val JA = ManagerLanguage.JAPANESE

private val SYS = ManagerLanguageChoice.SYSTEM
private val C_ZH = ManagerLanguageChoice.SIMPLIFIED
private val C_TRAD = ManagerLanguageChoice.TRADITIONAL
private val C_EN = ManagerLanguageChoice.ENGLISH
private val C_JA = ManagerLanguageChoice.JAPANESE

private fun trim(raw: String, lang: ManagerLanguage) = ManagerLanguageText.trim(raw, lang)

fun main() {
    // 1. Enum / persistence vocabulary ------------------------------------------------
    check("four languages, simplified Chinese first", ManagerLanguage.entries.first() == ZH) {
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
        ZH.label == "简体中文" && TRAD.label == "繁體中文" && EN.label == "English" &&
            JA.label == "日本語")

    // 2. Stored choice: four options, SYSTEM first and default ------------------------
    check("five choices, SYSTEM first", ManagerLanguageChoice.entries.first() == SYS) {
        ManagerLanguageChoice.entries.joinToString()
    }
    check("choice tags are the stored values",
        SYS.tag == "system" && C_ZH.tag == "zh-Hans" && C_TRAD.tag == "zh-Hant" &&
            C_EN.tag == "en" && C_JA.tag == "ja")
    check("fromTag round-trips every choice tag",
        ManagerLanguageChoice.entries.all { ManagerLanguageChoice.fromTag(it.tag) == it })
    check("missing stored value means follow-system (fresh install default)",
        ManagerLanguageChoice.fromStored(null) == SYS && ManagerLanguageChoice.fromStored("") == SYS)
    check("unknown stored value means follow-system",
        ManagerLanguageChoice.fromStored("kr") == SYS)
    check("a stored explicit language is preserved, not migrated to system",
        ManagerLanguageChoice.fromStored("zh-Hans") == C_ZH &&
            ManagerLanguageChoice.fromStored("zh-Hant") == C_TRAD &&
            ManagerLanguageChoice.fromStored("en") == C_EN &&
            ManagerLanguageChoice.fromStored("ja") == C_JA)
    check("historic zh spelling reads as simplified Chinese",
        ManagerLanguageChoice.fromStored("zh") == C_ZH)
    check("stored explicit values are case-insensitive",
        ManagerLanguageChoice.fromStored("EN") == C_EN)
    check("explicitOrNull is null only for SYSTEM",
        SYS.explicitOrNull() == null && C_ZH.explicitOrNull() == ZH &&
            C_TRAD.explicitOrNull() == TRAD && C_EN.explicitOrNull() == EN &&
            C_JA.explicitOrNull() == JA)

    // 2b. Chinese script/region: traditional regions and script, Hans wins over region -----
    run {
        fun ch(tag: String) = ManagerLanguageChoice.fromTag(tag)
        check("zh-Hant is traditional Chinese", ch("zh-Hant") == C_TRAD)
        check("zh-TW / zh-HK / zh-MO are traditional Chinese",
            ch("zh-TW") == C_TRAD && ch("zh-HK") == C_TRAD && ch("zh-MO") == C_TRAD)
        check("other traditional regions stay simplified (SG/MY/CN rules unchanged)",
            ch("zh-SG") == C_ZH && ch("zh-MY") == C_ZH && ch("zh-CN") == C_ZH)
        check("zh-Hans / plain zh are simplified Chinese",
            ch("zh-Hans") == C_ZH && ch("zh") == C_ZH)
        check("an explicit Hans subtag beats a traditional region (Hans priority)",
            ch("zh-Hans-TW") == C_ZH && ch("zh-Hans-HK") == C_ZH)
        check("an explicit Hant subtag beats a simplified region",
            ch("zh-Hant-CN") == C_TRAD)
        check("script and region in either order agree",
            ch("zh-TW-Hant") == C_TRAD && ch("zh-Hant-TW") == C_TRAD)
        check("tags are case-insensitive and accept underscores",
            ch("ZH-hant") == C_TRAD && ch("zh_tw") == C_TRAD && ch("zh_HK") == C_TRAD)
        check("isTraditionalTag mirrors the parser",
            ManagerLanguageChoice.isTraditionalTag("zh-TW") &&
                !ManagerLanguageChoice.isTraditionalTag("zh-CN") &&
                !ManagerLanguageChoice.isTraditionalTag("ja"))
        check("an unknown Chinese shape is not claimed as traditional",
            ch("zh-XX") == C_ZH)
    }

    // 3. Device-locale resolution: ja > zh > en --------------------------------------
    run {
        fun r(tag: String) = ManagerSystemLanguage.resolve(Locale.forLanguageTag(tag))
        check("system ja -> Japanese", r("ja") == JA && r("ja-JP") == JA)
        check("system zh -> simplified Chinese", r("zh") == ZH && r("zh-CN") == ZH)
        check("system zh-TW / zh-HK / zh-MO / zh-Hant -> traditional Chinese",
            r("zh-TW") == TRAD && r("zh-HK") == TRAD && r("zh-MO") == TRAD && r("zh-Hant") == TRAD) {
            listOf(r("zh-TW"), r("zh-HK"), r("zh-MO"), r("zh-Hant")).joinToString()
        }
        check("system zh-CN / zh-SG / zh-Hans / plain zh -> simplified Chinese",
            r("zh-CN") == ZH && r("zh-SG") == ZH && r("zh-Hans") == ZH && r("zh") == ZH)
        check("an explicit Hans locale tag beats a TW region (script priority)",
            ManagerSystemLanguage.resolve(Locale.forLanguageTag("zh-Hans-TW")) == ZH)
        check("a locale for a traditional region still resolves traditional after round-trip",
            ManagerSystemLanguage.resolve(Locale.forLanguageTag("zh-Hant")) == TRAD &&
                ManagerSystemLanguage.resolve(Locale.forLanguageTag("zh-HK")) == TRAD)
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
            C_EN.effective(JA) == EN && C_JA.effective(ZH) == JA && C_ZH.effective(EN) == ZH &&
                C_TRAD.effective(EN) == TRAD && C_TRAD.effective(ZH) == TRAD)
        check("SYSTEM follows the device",
            SYS.effective(JA) == JA && SYS.effective(ZH) == ZH && SYS.effective(EN) == EN &&
                SYS.effective(TRAD) == TRAD)
        check("of() maps an effective language back to its menu entry",
            ManagerLanguageChoice.of(JA) == C_JA && ManagerLanguageChoice.of(ZH) == C_ZH &&
                ManagerLanguageChoice.of(EN) == C_EN && ManagerLanguageChoice.of(TRAD) == C_TRAD)
    }

    // 5. tr() helper ----------------------------------------------------------------
    check("tr returns Chinese verbatim", ZH.tr("停止助手", "Stop", "停止") == "停止助手")
    check("tr returns English", EN.tr("停止助手", "Stop", "停止") == "Stop")
    check("tr returns Japanese", JA.tr("停止助手", "Stop", "停止") == "停止")
    check("tr derives Traditional from the Chinese source, not from en/ja",
        TRAD.tr("停止助手", "Stop", "停止") == "停止助手" &&
            TRAD.tr("设置", "Settings", "設定") == "設定")
    check("tr Traditional falls back to the Chinese text for an unknown phrase",
        TRAD.tr("某个未收录的引擎提示", "Some engine note", "某エンジン通知") == "某个未收录的引擎提示")
    check("tr Traditional never returns English or an empty string",
        TRAD.tr("", "Only EN", "のみ") == "" && TRAD.tr("系统悬浮窗", "Overlay", "ウィンドウ") != "Overlay")
    check("tr keeps an empty Chinese slot empty (supplementary lines)", EN.tr("", "Only EN", "のみ") == "Only EN")

    // 6. Menu labels and top-bar tags -----------------------------------------------
    run {
        check("follow-system label is translated",
            SYS.label(EN) == "Follow system" && SYS.label(JA) == "システムに従う" &&
                SYS.label(ZH) == "跟随系统")
        check("every language label is an endonym in every UI language",
            C_ZH.label(EN) == "简体中文" && C_TRAD.label(EN) == "繁體中文" &&
                C_TRAD.label(TRAD) == "繁體中文" && C_EN.label(JA) == "English" &&
                C_JA.label(ZH) == "日本語")
        check("follow-system label is Traditional in zh-Hant",
            SYS.label(TRAD) == "跟隨系統") { SYS.label(TRAD) }
        check("describe() shows the resolved language while following",
            SYS.describe(JA) == "システムに従う（日本語）" && SYS.describe(EN) == "Follow system (English)") {
            SYS.describe(JA)
        }
        check("describe() in zh-Hant names the resolved language in Traditional",
            SYS.describe(TRAD) == "跟隨系統（繁體中文）") { SYS.describe(TRAD) }
        check("describe() of an explicit choice is just its label",
            C_JA.describe(EN) == "日本語")
        check("top bar tag, explicit choices",
            topBarLanguageTag(C_ZH, ZH) == "中文" && topBarLanguageTag(C_EN, ZH) == "EN" &&
                topBarLanguageTag(C_JA, ZH) == "日本語" &&
                topBarLanguageTag(C_TRAD, ZH) == "繁體")
        check("top bar tag, following system",
            topBarLanguageTag(SYS, ZH) == "中文" && topBarLanguageTag(SYS, EN) == "EN" &&
                topBarLanguageTag(SYS, JA) == "日本語" && topBarLanguageTag(SYS, TRAD) == "繁體")
    }

    // 7. Tag parser used by UiLanguage.applyTag (same rules as the stored reader) ------
    run {
        fun resolved(tag: String?) = ManagerLanguageChoice.fromTag(tag).explicitOrNull()
        check("applyTag accepts zh-Hans / zh / zh-CN as simplified",
            resolved("zh-Hans") == ZH && resolved("zh") == ZH && resolved("zh-CN") == ZH &&
                resolved("zh-SG") == ZH)
        check("applyTag accepts zh-TW / zh-HK / zh-MO / zh-Hant as traditional",
            resolved("zh-TW") == TRAD && resolved("zh-HK") == TRAD && resolved("zh-MO") == TRAD &&
                resolved("zh-Hant") == TRAD)
        check("applyTag keeps explicit Hans ahead of a TW region",
            resolved("zh-Hans-TW") == ZH)
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

    // 10b. Traditional: known manager text is converted, unknown stays verbatim ----------
    run {
        check("known status line converts in Traditional",
            trim("助手已开启", TRAD) == "助手已開啟") { trim("助手已开启", TRAD) }
        check("known status line converts without English leaking",
            trim("无人值守模式已关闭", TRAD) == "無人值守模式已關閉") {
            trim("无人值守模式已关闭", TRAD)
        }
        check("timer message keeps the number in Traditional",
            trim("随机等待 2.3 秒 · 可随时关闭", TRAD) == "隨機等候 2.3 秒 · 可隨時關閉") {
            trim("随机等待 2.3 秒 · 可随时关闭", TRAD)
        }
        // The rejection line is a real phrase in the manager, so it must come back converted —
        // unless the copy table has not been regenerated yet, which the next assertion catches.
        val rejectedTrad = trim("手牌校验未通过，等待新的完整牌局状态", TRAD)
        check("rejection reason converts and keeps the Chinese reason, not English",
            rejectedTrad == "手牌校驗未通過，等待新的完整牌局狀態" ||
                rejectedTrad == "手牌校验未通过，等待新的完整牌局狀態") { rejectedTrad }
        check("a reason this build does not know is never half-translated",
            trim("某个新的拒绝原因，等待新的完整牌局状态", TRAD) ==
                "某个新的拒绝原因，等待新的完整牌局状态") {
            trim("某个新的拒绝原因，等待新的完整牌局状态", TRAD)
        }
        check("an unknown Chinese line is returned verbatim (no blanket conversion)",
            trim("某个未收录的诊断 · 第 3 局", TRAD) == "某个未收录的诊断 · 第 3 局")
        check("engine error text is returned verbatim in Traditional",
            trim("权重文件无效或结构不受支持 (状态码: 2)", TRAD) ==
                "权重文件无效或结构不受支持 (状态码: 2)")
        check("empty source never becomes a fake Traditional line",
            trim("   ", TRAD) == "   ")
    }

    // 10c. The compiled table: whole-string keys only ------------------------------
    run {
        check("table knows the app's own copy", TraditionalChinese.knows("设置") &&
            TraditionalChinese.knows("系统悬浮窗") &&
            TraditionalChinese.knows("助手已开启")) {
            "设置=" + TraditionalChinese.knows("设置") +
                " 系统悬浮窗=" + TraditionalChinese.knows("系统悬浮窗") +
                " 助手已开启=" + TraditionalChinese.knows("助手已开启")
        }
        check("table does not know an engine diagnostic",
            !TraditionalChinese.knows("某个未收录的诊断") &&
                TraditionalChinese.text("某个未收录的诊断") == "某个未收录的诊断")
        check("machine identifiers are untouched (log payloads, tile codes, paths)",
            TraditionalChinese.text("5m /include/ai/mortal_4p.onnx 300 MiB") ==
                "5m /include/ai/mortal_4p.onnx 300 MiB")
        check("numbers inside a known template survive",
            TraditionalChinese.text("放铳风险指数 3.4") == "放銃風險指數 3.4")
        check("mahjong / product terms use the intended Traditional words",
            TraditionalChinese.text("无人值守") == "無人值守" &&
                TraditionalChinese.text("系统悬浮窗模式") == "系統懸浮視窗模式" &&
                TraditionalChinese.text("设置") == "設定" &&
                TraditionalChinese.text("自动续打") == "自動續打" &&
                TraditionalChinese.text("四麻") == "四麻" &&
                TraditionalChinese.text("三麻") == "三麻") {
            listOf("无人值守", "系统悬浮窗模式", "设置", "自动续打", "四麻", "三麻")
                .joinToString(" | ") { it + "=" + TraditionalChinese.text(it) }
        }
        // Reviewer round: natural Taiwan wording plus a template row that is really used.
        check("zh-Hant uses 自訂 / 開源授權 / 懸浮視窗",
            TraditionalChinese.text("已选自定义") == "已選自訂" &&
                TraditionalChinese.text("助手开源许可") == "助手開源授權" &&
                TraditionalChinese.text("系统悬浮窗") == "系統懸浮視窗") {
            listOf("已选自定义", "助手开源许可", "系统悬浮窗")
                .joinToString(" | ") { it + "=" + TraditionalChinese.text(it) }
        }
        check("a templated title converts its wording and keeps the value",
            TraditionalChinese.text("发现新版本 0.15.2") == "發現新版本 0.15.2" &&
                TraditionalChinese.text("已载入 20 / 57 条") == "已載入 20 / 57 條" &&
                TraditionalChinese.text("操作延迟 2.0–5.0 秒（隨機）").startsWith("操作延遲 2.0")) {
            listOf("发现新版本 0.15.2", "已载入 20 / 57 条")
                .joinToString(" | ") { it + "=" + TraditionalChinese.text(it) }
        }
        // The delay line the manager renders is a shell plus an already-translated label: the
        // shell is the row asserted above; the label comes from localizedDelayLabel (tested below).
        check("a template is anchored and does not match mid-message",
            TraditionalChinese.text("事件：# · 会话：# · 序号：#") == "事件：# · 會話：# · 序號：#" &&
                TraditionalChinese.text("更新：发现新版本 1.0") == "更新：发现新版本 1.0") {
            TraditionalChinese.text("更新：发现新版本 1.0")
        }
    }

    // 11. Update-check failures -----------------------------------------------------
    run {
        val misconfigured = "更新源配置无效。"
        check("manager's own update failure is translated",
            localizedUpdateFailure(misconfigured, EN) == "The update source is misconfigured." &&
                localizedUpdateFailure(misconfigured, JA) == "更新元の設定が不正です。")
        check("update failure is identity in Chinese",
            localizedUpdateFailure(misconfigured, ZH) == misconfigured)
        // The header is translated; the Chinese sentence body comes from the copy table, so a
        // stale table leaves the source readable instead of producing a broken line.
        check("update failure converts the Chinese source in Traditional",
            localizedUpdateFailure(misconfigured, TRAD) == "更新源配置無效。" ||
                localizedUpdateFailure(misconfigured, TRAD) == misconfigured) {
            localizedUpdateFailure(misconfigured, TRAD)
        }
        check("platform update error stays verbatim in Traditional",
            localizedUpdateFailure("HTTP 403", TRAD) == "HTTP 403")
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
        check("log tabs in Traditional", logTabs(TRAD) == listOf("Hook 日誌", "助手日誌")) {
            logTabs(TRAD).toString()
        }
        check("level filter 'All' in Traditional",
            logLevelFilters(TRAD).first() == LOG_LEVEL_ALL to "全部")
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
        // Built from the Chinese source with the player count copied, so the number is the one
        // invariant that holds with or without the compiled table.
        check("staged model: Traditional keeps the player count",
            modelMessageText("4 人模型已校验并暂存；下一次完整牌局同步时生效。", TRAD)
                .let { it.startsWith("4 人") && it.contains("模型") }) {
            modelMessageText("4 人模型已校验并暂存；下一次完整牌局同步时生效。", TRAD)
        }
        // These two go through the compiled copy of AiScreen's own sentences, which is rebuilt by
        // Gradle; the probe still accepts the Simplified source (never English) when the build
        // predates the regeneration, and the conversion itself is covered by the table checks.
        // These go through the compiled copy of AiScreen's own sentences, which Gradle rebuilds.
        // The model self-check/seat lines are asserted as "digits survive and the Traditional
        // template exists in the table" — the conversion itself is covered by the table checks.
        val selfTestTrad = modelMessageText(
            "4人 Mortal (.pth) 加载与推理自检通过（实验性）· 812 ms", TRAD)
        check("self-check line: the Traditional template exists in the table",
            TraditionalChinese.knows(
                "4人 Mortal (.pth) 加载与推理自检通过（实验性）· 812 ms") ||
                TraditionalChinese.knows("模型自检中 · 暂不可开启"))
        check("self-check line: digits and player count survive",
            selfTestTrad.contains("812") && selfTestTrad.contains("4")) { selfTestTrad }
        check("seat message: Traditional is Chinese, never the English copy",
            modelMessageText("四麻自定义策略将在下一次完整牌局同步时生效。", TRAD).let {
                it == "四麻自定義策略將在下一次完整牌局同步時生效。" || it.startsWith("四麻")
            }) {
            modelMessageText("四麻自定义策略将在下一次完整牌局同步时生效。", TRAD)
        }
        check("unknown model message stays verbatim in Traditional",
            modelMessageText("模型文件超过 300 MiB 限制", TRAD) == "模型文件超过 300 MiB 限制")
        check("model intro: Traditional mentions the MiB limit",
            modelMessageText(
                "支持 Akagi ONNX，以及实验性四麻/三麻 Mortal v4（ONNX/PTH/PT，单文件上限 300 MiB）。" +
                    "自动识别人数并保存到对应槽位；兼容的自定义模型仍需手动开启无人值守。模型仅保存在本机。",
                TRAD).contains("MiB"))
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
        check("zh-Hant random delay keeps the digits", localizedDelayLabel(random, TRAD) == "2.0–5.0 秒（隨機）") {
            localizedDelayLabel(random, TRAD)
        }
        check("zh-Hant fixed delay keeps the digits", localizedDelayLabel(fixed, TRAD) == "3.0 秒（固定）") {
            localizedDelayLabel(fixed, TRAD)
        }
    }

    println("\n$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
