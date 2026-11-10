package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manager UI language (the assistant page and the shared navigation labels).
 *
 * This is a display preference only: it never changes the assistant's behaviour, the service
 * lifetime, the display mode, the model slots or anything the game sees. It lives in its own
 * preferences file so it cannot collide with `unattended`, `assistant-display`,
 * `auto-rematch` or the model/delay files.
 *
 * Two layers are kept strictly apart, because the switchable languages are not the same thing
 * as what is actually rendered:
 *
 * - [ManagerLanguageChoice] is what the user picked and what is stored: `SYSTEM`, `SIMPLIFIED`,
 *   `ENGLISH`, `JAPANESE`. `SYSTEM` is the default for a fresh install and nobody is migrated
 *   onto it; an explicit language written by an earlier build stays exactly as it is.
 * - [ManagerLanguage] is the language that is *effective* right now, and it never contains
 *   `SYSTEM`: [ManagerLanguageChoice.effective] resolves it against the device locales.
 *
 * Chinese stays the source of truth for every string: each call site passes its Chinese text as
 * the first argument of [tr], so a missing translation can only ever fall back to the original
 * string. Text produced by the game or the engine is never rewritten here; see
 * [ManagerLanguageText.trim], which only maps messages the manager itself produced and
 * otherwise returns the text unchanged.
 *
 * The file is self-contained by design (only [Context] for the very first read), so a new
 * screen can adopt it by calling [tr] and later move to string resources without touching call
 * sites. The Java-callable mirror for the game process lives in UiLanguage.kt.
 */

/**
 * Stored selection: the user's explicit choice, or [SYSTEM] meaning "follow the device".
 *
 * [tag] is the value written to preferences (`system`, `zh-Hans`, `en`, `ja`). [label] is
 * intentionally not localised: an endonym is the one label a user can always recognise, even
 * when the current UI language is one they did not expect.
 */
internal enum class ManagerLanguageChoice(val tag: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    SIMPLIFIED("zh-Hans", "简体中文"),
    ENGLISH("en", "English"),
    JAPANESE("ja", "日本語"),
    ;

    /** Only a real language can be rendered. [SYSTEM] has to be resolved first. */
    fun explicitOrNull(): ManagerLanguage? = when (this) {
        SYSTEM -> null
        SIMPLIFIED -> ManagerLanguage.SIMPLIFIED
        ENGLISH -> ManagerLanguage.ENGLISH
        JAPANESE -> ManagerLanguage.JAPANESE
    }

    /** The language this choice renders as under [system]. [SYSTEM] falls into [system]. */
    fun effective(system: ManagerLanguage): ManagerLanguage = explicitOrNull() ?: system

    companion object {
        /**
         * Stored value or resource tag to enum. Missing or unknown values are [SYSTEM] (the
         * shipped default).
         *
         * A tag is matched on its language subtag with the region/script ignored: `zh`,
         * `zh-Hans`, `zh-CN` and `zh-TW` all mean simplified Chinese (the manager has one
         * Chinese build and must not claim a traditional variant it cannot draw), `en-US` is
         * English, `ja-JP` is Japanese. `system` is the stored "follow the device" value.
         * This is the single definition used by the preferences reader and by
         * [UiLanguage.applyTag], so the two can never disagree.
         */
        fun fromTag(raw: String?): ManagerLanguageChoice {
            val value = raw?.trim()?.lowercase()?.replace('_', '-') ?: return SYSTEM
            if (value.isEmpty()) return SYSTEM
            if (value == SYSTEM.tag) return SYSTEM
            return when (value.substringBefore('-')) {
                "zh" -> SIMPLIFIED
                "en" -> ENGLISH
                "ja" -> JAPANESE
                else -> SYSTEM
            }
        }

        /** Preferences value to enum. Same rules as [fromTag]; kept as the reading entry point. */
        fun fromStored(raw: String?): ManagerLanguageChoice = fromTag(raw)

        /** The menu entry for an effective language, e.g. for the top bar's current tag. */
        fun of(language: ManagerLanguage): ManagerLanguageChoice = when (language) {
            ManagerLanguage.SIMPLIFIED -> SIMPLIFIED
            ManagerLanguage.ENGLISH -> ENGLISH
            ManagerLanguage.JAPANESE -> JAPANESE
        }
    }
}

/** A language the manager can actually render. Never `SYSTEM`. */
internal enum class ManagerLanguage(val tag: String, val label: String) {
    SIMPLIFIED("zh-Hans", "简体中文"),
    ENGLISH("en", "English"),
    JAPANESE("ja", "日本語"),
    ;

    companion object {
        /** Stored value to enum; missing, unknown or `system` resolves to Chinese. */
        fun fromTag(raw: String?): ManagerLanguage =
            entries.firstOrNull { it.tag.equals(raw, ignoreCase = true) } ?: SIMPLIFIED
    }
}

/**
 * Device-locale resolution: which [ManagerLanguage] a `SYSTEM` choice should render as.
 *
 * Ordered exactly as the product requires: Japanese first, Chinese second, anything else
 * English. Only the primary locale decides, and only its language code is looked at, so
 * `zh-TW`, `zh-Hant` and `zh-HK` all select simplified Chinese: the manager ships one Chinese
 * build and must not claim a traditional variant it cannot draw.
 */
internal object ManagerSystemLanguage {
    fun resolve(locale: java.util.Locale?): ManagerLanguage = when (locale?.language?.lowercase()) {
        "ja" -> ManagerLanguage.JAPANESE
        "zh" -> ManagerLanguage.SIMPLIFIED
        else -> ManagerLanguage.ENGLISH
    }

    /**
     * Same resolution for a full locale list (the primary one wins). Kept separate from the
     * getter below so the pure part can be tested without Android.
     */
    fun resolve(locales: List<java.util.Locale>): ManagerLanguage =
        resolve(locales.firstOrNull())

    /**
     * Reads the *system* configuration, not the app's: the manager's own configuration is never
     * overridden (no `android:localeConfig`), and reading the system one is what keeps the
     * answer correct when only the device language changed.
     */
    fun current(): ManagerLanguage = fromConfiguration()

    private fun fromConfiguration(): ManagerLanguage = runCatching {
        val configuration = android.content.res.Resources.getSystem().configuration
        resolve(configuration.locales.takeIf { it.size() > 0 }?.get(0))
    }.getOrDefault(ManagerLanguage.SIMPLIFIED)

    /** Re-reads the device locale and, when it moved, publishes the new effective language. */
    internal fun sync(): ManagerLanguage {
        val next = current()
        ManagerLanguageState.applySystem(next)
        return next
    }
}

/** One stored value: the user's choice. Nothing here knows about the device locale. */
internal object ManagerLanguageSettings {
    private const val PREFS = "manager-language"
    private const val KEY_LANGUAGE = "language"

    /** The stored choice; absent means [ManagerLanguageChoice.SYSTEM]. */
    fun readChoice(context: Context): ManagerLanguageChoice = runCatching {
        ManagerLanguageChoice.fromStored(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null),
        )
    }.getOrDefault(ManagerLanguageChoice.SYSTEM)

    /** Written synchronously so the next frame and a fresh process read the same value. */
    fun save(context: Context, choice: ManagerLanguageChoice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, choice.tag).commit()
    }
}

/**
 * The live selection and the live effective language.
 *
 * - [choice] is what the user picked (possibly [ManagerLanguageChoice.SYSTEM]) and what the
 *   language menus render their radio state from.
 * - [state] is what the screens render text in. It tracks [choice] while the choice is
 *   explicit and follows the device whenever the choice is [ManagerLanguageChoice.SYSTEM]
 *   (or the device language changes under an explicit choice, which only updates the cached
 *   follow-system language without touching the choice).
 *
 * [load] runs once in `MainActivity.onCreate` before the first composition, so the first frame
 * is already in the stored language. [select] applies and persists; [syncSystemLanguage] is
 * called when the activity is (re)created and when the platform reports a locale change.
 */
internal object ManagerLanguageState {
    internal val mutableChoice: MutableState<ManagerLanguageChoice> =
        mutableStateOf(ManagerLanguageChoice.SYSTEM)

    /** The effective language. Reading it in a composable subscribes that composable. */
    internal val mutable: MutableState<ManagerLanguage> = mutableStateOf(ManagerLanguage.SIMPLIFIED)
    private val flow = MutableStateFlow(ManagerLanguage.SIMPLIFIED)

    /** Cached device language; updated by [syncSystemLanguage] without a composition read. */
    @Volatile
    private var systemLanguage: ManagerLanguage = ManagerLanguage.SIMPLIFIED

    val choice: ManagerLanguageChoice get() = mutableChoice.value
    val choiceState: MutableState<ManagerLanguageChoice> get() = mutableChoice
    val state: MutableState<ManagerLanguage> get() = mutable

    /** Read-only mirror for callers outside a composition. */
    val observable = flow.asStateFlow()

    val current: ManagerLanguage get() = mutable.value

    /** True while the rendered language is derived from the device. */
    val followsSystem: Boolean get() = mutableChoice.value == ManagerLanguageChoice.SYSTEM

    /**
     * Reads the stored choice once and resolves the device language behind it. Called from
     * `MainActivity.onCreate` before `setContent`, so the very first frame is already correct.
     */
    fun load(context: Context) {
        val stored = ManagerLanguageSettings.readChoice(context)
        systemLanguage = ManagerSystemLanguage.current()
        mutableChoice.value = stored
        publish(stored.effective(systemLanguage))
    }

    /** The device language may have changed; re-evaluate and keep an explicit choice intact. */
    fun syncSystemLanguage(system: ManagerLanguage = ManagerSystemLanguage.current()): ManagerLanguage {
        systemLanguage = system
        val choice = mutableChoice.value
        return if (choice == ManagerLanguageChoice.SYSTEM) publish(system) else system
    }

    fun select(context: Context, choice: ManagerLanguageChoice) {
        if (mutableChoice.value != choice) mutableChoice.value = choice
        publish(choice.effective(systemLanguage))
        ManagerLanguageSettings.save(context, choice)
    }

    fun select(context: Context, language: ManagerLanguage) =
        select(context, ManagerLanguageChoice.of(language))

    /** Internal entry point for [ManagerSystemLanguage.sync]; never called by a click. */
    internal fun applySystem(system: ManagerLanguage) {
        systemLanguage = system
        if (mutableChoice.value == ManagerLanguageChoice.SYSTEM) publish(system)
    }

    /**
     * Render-time override used by [UiLanguage.applyTag].
     *
     * Publishes [language] regardless of the stored choice, without persisting anything: a
     * caller that passes an explicit tag is asking for *that* language on screen right now
     * (typically the game process mirroring the manager's choice). The stored option is left
     * alone, so the manager's own menu still shows what the user picked, and the next
     * [load] / [syncSystemLanguage] decides afresh.
     */
    internal fun applySystemLanguageOverride(language: ManagerLanguage) {
        publish(language)
    }

    private fun publish(language: ManagerLanguage): ManagerLanguage {
        if (mutable.value != language) mutable.value = language
        flow.value = language
        return language
    }
}

/** The one helper every call site uses: `language.tr(chinese, english, japanese)`. */
internal fun ManagerLanguage.tr(zh: String, en: String, ja: String): String = when (this) {
    ManagerLanguage.SIMPLIFIED -> zh
    ManagerLanguage.ENGLISH -> en
    ManagerLanguage.JAPANESE -> ja
}

/** The same three-way pick for a stored selection, resolved against the device first. */
internal fun ManagerLanguageChoice.tr(zh: String, en: String, ja: String): String =
    effective(ManagerSystemLanguage.current()).tr(zh, en, ja)

/**
 * The language for the frame being composed.
 *
 * Reading it inside a composable subscribes that composable to [ManagerLanguageState.state],
 * so a language change — an explicit switch or the device changing under "follow system" —
 * recomposes only the screens that translate.
 */
internal fun managerLanguage(): ManagerLanguage = ManagerLanguageState.state.value

/** The stored selection, for the radio buttons of the language menus. */
internal fun managerLanguageChoice(): ManagerLanguageChoice = ManagerLanguageState.choiceState.value

/**
 * Render-time re-translation of a *stored* message.
 *
 * Messages kept in service state are shared with the game and the floating window and stay in
 * Chinese at the source. This helper participates in recomposition, so an already-visible
 * message follows the language change without the source being touched.
 */
internal fun localizedTrimmed(raw: String): String = ManagerLanguageText.trim(raw, managerLanguage())

/**
 * The composition-independent spelling of [localizedTrimmed], for text that is translated at
 * an explicit language (or outside a composable). Same lookup table, same "unknown stays
 * verbatim" contract.
 */
internal fun localizedKnown(raw: String, language: ManagerLanguage = managerLanguage()): String =
    ManagerLanguageText.trim(raw, language)

/**
 * Update-check failures worth translating.
 *
 * Only the manager's own sentences are mapped. Anything that carries a status code, a JSON
 * error or a thrown platform message keeps its original wording, which is what a bug report
 * needs; the label in front of it is translated by the caller.
 */
internal fun localizedUpdateFailure(raw: String, language: ManagerLanguage): String {
    if (language == ManagerLanguage.SIMPLIFIED) return raw
    val text = raw.trim()
    val copy = when {
        text == "更新源配置无效。" ->
            "The update source is misconfigured." to "更新元の設定が不正です。"
        text.startsWith("无法连接更新源") ->
            "Could not reach the update source." to "更新元に接続できません。"
        text.startsWith("发布中没有符合协议的 hook-update.json") ->
            "The release has no hook-update.json matching the protocol." to
                "リリースにプロトコル準拠の hook-update.json がありません。"
        else -> return raw
    }
    return language.tr("", copy.first, copy.second)
}

/**
 * Mappings for the status lines the manager shows under its cards.
 *
 * Both sources are Chinese strings produced by services that also talk to the game, so they
 * are never rewritten at the source. [trim] substitutes a translation only for known shapes of
 * manager text and returns everything else (engine errors, tool results, unknown diagnostics)
 * unchanged, in its original language.
 */
internal object ManagerLanguageText {
    /** Normalises before matching: dashes, ideographic space and repeated blanks. */
    private fun normalize(raw: String): String = raw.trim()
        .replace('\u2013', '-')
        .replace('\u2014', '-')
        .replace('\u3000', ' ')
        .replace('\u00a0', ' ')
        .replace(Regex("\\s+"), " ")

    fun trim(raw: String, language: ManagerLanguage): String {
        if (language == ManagerLanguage.SIMPLIFIED) return raw
        val text = normalize(raw)
        if (text.isEmpty()) return raw
        pattern(text, language)?.let { return it }
        val exact = EXACT[text] ?: return raw
        return language.tr(exact.zh, exact.en, exact.ja)
    }

    private fun pattern(text: String, language: ManagerLanguage): String? {
        // "随机等待 2.3 秒 · 可随时关闭"
        Regex("^随机等待 ([0-9.]+) 秒 · 可随时关闭$").matchEntire(text)?.let {
            val seconds = it.groupValues[1]
            return when (language) {
                ManagerLanguage.ENGLISH -> "Waiting $seconds s · you can stop it anytime"
                ManagerLanguage.JAPANESE -> "$seconds 秒待機中 · いつでも停止できます"
                ManagerLanguage.SIMPLIFIED -> null
            }
        }
        // "<reason>，等待新的完整牌局状态"
        Regex("^(.+)，等待新的完整牌局状态$").matchEntire(text)?.let {
            val reason = REJECTIONS[it.groupValues[1]] ?: return null
            return when (language) {
                ManagerLanguage.ENGLISH -> "${reason.en}, waiting for a full game state"
                ManagerLanguage.JAPANESE -> "${reason.ja}。対局状態の完全な再同期を待っています"
                ManagerLanguage.SIMPLIFIED -> null
            }
        }
        return null
    }

    /** A translated copy of one known Chinese line; [zh] is kept for completeness. */
    private class Copy(val zh: String, val en: String, val ja: String)

    /** Exact status lines from `AiServiceStatus.message` and `AutoDiscardStatus.message`. */
    private val EXACT: Map<String, Copy> = buildMap {
        fun put(zh: String, en: String, ja: String) { put(zh, Copy(zh, en, ja)) }
        put("助手未开启", "Assistant is off", "アシストは停止中です")
        put("助手已开启", "Assistant is running", "アシストは起動中です")
        put("本地引擎加载失败，请停止助手并重启管理应用",
            "The local engine failed to load. Stop the assistant and restart the manager app.",
            "ローカルエンジンの読み込みに失敗しました。アシストを停止し、管理アプリを再起動してください。")
        put("本地引擎异常，等待恢复", "Local engine error, waiting to recover",
            "ローカルエンジンでエラーが発生しました。復帰を待っています")
        put("助手服务中断，等待恢复", "Assistant service interrupted, waiting to recover",
            "アシストサービスが中断しました。復帰を待っています")
        put("等待牌局", "Waiting for a game", "対局待ちです")
        put("采集服务未启动，请确认游戏已安装并重新开启助手",
            "The capture service is not running. Check that the game is installed, then start the assistant again.",
            "取得サービスが起動していません。ゲームがインストール済みか確認し、アシストを起動し直してください。")
        put("游戏连接已断开，等待重新同步", "Game connection lost, waiting to resync",
            "ゲームとの接続が切れました。再同期を待っています")
        put("检测到消息丢失，等待恢复完整牌局同步",
            "Dropped messages detected, waiting for a full game state",
            "メッセージの欠落を検出しました。対局状態の完全な再同期を待っています")
        put("分析未跟上牌局，等待恢复完整牌局同步",
            "Analysis fell behind the game, waiting for a full game state",
            "分析が対局に追いついていません。対局状態の完全な再同期を待っています")
        put("Hook 已连接，等待进入牌局或恢复完整牌局同步",
            "Hook connected, waiting for a game to start or a full resync",
            "Hook に接続しました。対局開始、または完全な再同期を待っています")
        put("连接或同步已变化，等待恢复 · 无人值守模式保持原开关状态",
            "Connection or sync changed, waiting to recover · unattended mode keeps its setting",
            "接続または同期が変化しました。復帰を待っています · 放置モードの設定は維持されます")
        put("无人值守模式已关闭", "Unattended mode is off", "放置モードはオフです")
        put("助手已停止，无人值守模式已关闭", "Assistant stopped, unattended mode is off",
            "アシストを停止しました。放置モードはオフです")
        put("模型自检中，无人值守模式已关闭", "Model self-test running, unattended mode is off",
            "モデル自己診断中です。放置モードはオフです")
        put("正在加载本地模型自检…", "Loading the local model self-test…",
            "ローカルモデルの自己診断を読み込み中…")
        put("无人值守模式已恢复 · 等待完整牌局同步",
            "Unattended mode restored · waiting for a full game state",
            "放置モードを再開しました · 対局状態の完全な再同期を待っています")
        put("无人值守模式已开启 · 等待完整牌局同步",
            "Unattended mode on · waiting for a full game state",
            "放置モードをオンにしました · 対局状態の完全な再同期を待っています")
        put("已确认游戏发送操作 · 等待下一回合",
            "Move confirmed by the game · waiting for the next turn",
            "操作の送信を確認しました · 次の手番を待っています")
        put("已手动关闭无人值守模式", "Unattended mode turned off manually",
            "放置モードを手動でオフにしました")
        put("已在游戏内关闭无人值守模式", "Unattended mode turned off in-game",
            "ゲーム内で放置モードをオフにしました")
        put("检测到手动操作 · 等待新的一回合", "Manual input detected · waiting for the next turn",
            "手動操作を検出しました · 次の手番を待っています")
        put("等待牌局同步或模型恢复 · 无人值守模式保持开启",
            "Waiting for game sync or model recovery · unattended mode stays on",
            "対局同期またはモデル復帰を待っています · 放置モードはオンのままです")
        put("游戏已自动处理超时窗口，等待下一次同步",
            "The game handled the timeout window itself, waiting for the next sync",
            "タイムアウト処理はゲーム側で完了しました。次の同期を待っています")
        put("等待期间牌局已变化，等待最新同步",
            "The game changed while waiting, waiting for the latest sync",
            "待機中に対局が変化しました。最新の同期を待っています")
        put("操作确认超时，等待游戏重新同步",
            "Move confirmation timed out, waiting for the game to resync",
            "操作確認がタイムアウトしました。ゲームの再同期を待っています")
        put("AI 动作与合法操作未能匹配，等待新的操作窗口",
            "The AI move matched no legal action, waiting for the next action window",
            "AI の打牌が有効な操作と一致しませんでした。次の操作機会を待っています")
        put("提示数据包过大被丢弃，等待下一手",
            "A hint packet was too large and was dropped, waiting for the next turn",
            "ヒントのパケットが大きすぎるため破棄しました。次の手を待っています")
        put("游戏自动操作已完成，等待下一次同步",
            "The game acted automatically, waiting for the next sync",
            "ゲーム側の自動操作が完了しました。次の同期を待っています")
        put("操作反馈格式异常，等待重新同步", "Unexpected move feedback, waiting to resync",
            "操作フィードバックの形式が不正です。再同期を待っています")
        put("无法启用模型", "Cannot enable this model", "このモデルを有効にできません")
        put("自检失败", "Self-test failed", "自己診断に失敗しました")
        put("模型导入失败，继续使用内置模型。",
            "Model import failed; the built-in model stays in use.",
            "モデルの取り込みに失敗しました。内蔵モデルのまま続行します。")
        // Unlock catalog card (written by UnlockCatalogStore; the card is shared, the lines are
        // manager text shown to the user).
        put("正在检查仓库解锁清单…", "Checking the repository catalog…",
            "リポジトリのカタログを確認中…")
        put("无法读取解锁清单", "Cannot read the unlock catalog", "カタログを読み込めません")
        // GitHub speed test (written by GitHubSpeedTests).
        put("正在测试各加速源…", "Testing the mirrors…", "各ミラーをテスト中…")
        put("全部线路测试失败，已保留原连接设置",
            "Every mirror failed; the previous connection settings are kept",
            "すべての経路でテストが失敗しました。以前の接続設定を保持します")
        put("测速未完成，已保留原连接设置", "The test did not finish; the previous connection settings are kept",
            "テストが完了しませんでした。以前の接続設定を保持します")
        put("测试中断", "test interrupted", "テスト中断")
        put("请求超时", "request timed out", "リクエストがタイムアウトしました")
        put("域名无法解析", "the host name could not be resolved", "ホスト名を解決できません")
        put("HTTPS 连接失败", "the HTTPS connection failed", "HTTPS 接続に失敗しました")
        put("连接失败", "connection failed", "接続に失敗しました")
        put("返回内容不是有效清单", "the response is not a valid catalog", "応答が有効なカタログではありません")
        put("请求失败", "the request failed", "リクエストに失敗しました")
        put("地址无效", "the address is not valid", "アドレスが不正です")
        put("连接设置已保存，下次检查和下载时使用",
            "Connection settings saved; they apply to the next check and download",
            "接続設定を保存しました。次回の確認とダウンロードから適用されます")
    }

    /** The `<reason>` head of a rejected move ("<reason>，等待新的完整牌局状态"). */
    private val REJECTIONS: Map<String, Copy> = buildMap {
        fun put(zh: String, en: String, ja: String) { put(zh, Copy(zh, en, ja)) }
        put("推荐已过期", "the recommendation expired", "推奨手が期限切れになりました")
        put("回合校验未通过", "the turn check failed", "巡目の照合に失敗しました")
        put("手牌校验未通过", "the hand check failed", "手牌の照合に失敗しました")
        put("目标牌当前不可操作", "that tile cannot be played right now",
            "その牌は現在選択できません")
        put("鸣牌或立直组合已变化", "the call or riichi line changed",
            "副露・立直の組み合わせが変わりました")
        put("目标牌或座位已变化", "the target tile or seat changed",
            "対象の牌または席が変わりました")
        put("可以和牌，请手动确认", "a win is available, confirm manually",
            "和了できます。手動で確定してください")
        put("正在选择特殊操作", "a special action is being selected", "特殊操作を選択中です")
        put("服务器未确认操作", "the server did not confirm the move",
            "サーバーが操作を確認しませんでした")
        put("游戏操作状态异常", "unexpected game action state", "ゲームの操作状態が不正です")
    }
}

/** Keeps "1.5" identical in every language regardless of the device locale. */
internal fun formatManagerSeconds(tenths: Int): String =
    String.format(java.util.Locale.ROOT, "%.1f", tenths / 10.0)

/** Translated delay summary: "<min> 秒（固定）" / "<min>–<max> 秒（随机）". */
internal fun localizedDelayLabel(range: AutoDelayRange, language: ManagerLanguage): String {
    val min = formatManagerSeconds(range.minTenths)
    val max = formatManagerSeconds(range.maxTenths)
    val fixed = range.minTenths == range.maxTenths
    return when (language) {
        ManagerLanguage.SIMPLIFIED -> range.label
        ManagerLanguage.ENGLISH -> if (fixed) "$min s (fixed)" else "$min–$max s (random)"
        ManagerLanguage.JAPANESE -> if (fixed) "$min 秒（固定）" else "$min–$max 秒（ランダム）"
    }
}

/** Stable key for "no level filter"; the label itself is translated. */
internal const val LOG_LEVEL_ALL = "ALL"

/** Tab titles of the log page: Hook first, assistant second (index order is a contract). */
internal fun logTabs(language: ManagerLanguage): List<String> = listOf(
    language.tr("Hook 日志", "Hook log", "Hook ログ"),
    language.tr("助手日志", "Assistant log", "アシストログ"),
)

/**
 * Level filter rows as (stable key, label) pairs.
 *
 * `INFO`/`WARN`/`ERROR` are machine fields written by the service and stay verbatim; only the
 * "no filter" entry is a manager word.
 */
internal fun logLevelFilters(language: ManagerLanguage): List<Pair<String, String>> = listOf(
    LOG_LEVEL_ALL to language.tr("全部", "All", "すべて"),
    "INFO" to "INFO",
    "WARN" to "WARN",
    "ERROR" to "ERROR",
)

/** Compact tag for the top app bar; the full names live in the dropdown it opens. */
internal fun topBarLanguageTag(choice: ManagerLanguageChoice, language: ManagerLanguage): String =
    when (choice) {
        // Explicit languages show their own tag, even when the device is set to something else.
        ManagerLanguageChoice.SIMPLIFIED -> "中文"
        ManagerLanguageChoice.ENGLISH -> "EN"
        ManagerLanguageChoice.JAPANESE -> "日本語"
        ManagerLanguageChoice.SYSTEM -> when (language) {
            ManagerLanguage.SIMPLIFIED -> "中文"
            ManagerLanguage.ENGLISH -> "EN"
            ManagerLanguage.JAPANESE -> "日本語"
        }
    }

/** Label of one stored choice, for the radio rows and the "current:" lines. */
internal fun ManagerLanguageChoice.label(language: ManagerLanguage): String = when (this) {
    ManagerLanguageChoice.SYSTEM -> language.tr("跟随系统", "Follow system", "システムに従う")
    ManagerLanguageChoice.SIMPLIFIED -> "简体中文"
    ManagerLanguageChoice.ENGLISH -> "English"
    ManagerLanguageChoice.JAPANESE -> "日本語"
}

/**
 * "跟随系统（简体中文）": the stored choice plus the language it currently resolves to, so a
 * user can tell *why* the UI looks the way it does without opening the details dialog.
 */
internal fun ManagerLanguageChoice.describe(language: ManagerLanguage): String = when (this) {
    ManagerLanguageChoice.SYSTEM ->
        language.tr(
            "跟随系统（${language.label}）",
            "Follow system (${language.label})",
            "システムに従う（${language.label}）",
        )
    else -> label(language)
}
