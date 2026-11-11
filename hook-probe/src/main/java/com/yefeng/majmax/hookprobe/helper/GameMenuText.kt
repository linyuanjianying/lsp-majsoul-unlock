package com.yefeng.majmax.hookprobe.helper

import com.yefeng.majmax.hookprobe.manager.ManagerLanguage
import com.yefeng.majmax.hookprobe.manager.ManagerLanguageChoice
import com.yefeng.majmax.hookprobe.manager.TraditionalChinese
import org.json.JSONObject

/**
 * Translations for the text the manager itself produces on the in-game surfaces.
 *
 * Scope is deliberately narrow. This helper never walks a view tree and never guesses:
 * it is given the exact string a surface is about to draw and either returns a
 * translation for a known shape or the original text untouched. That keeps the two
 * contracts that matter here:
 *
 *  - Chinese is the source of truth, so a missing translation can only ever fall back to
 *    the original string. Traditional Chinese is produced from that Chinese source through the
 *    compiled [com.yefeng.majmax.hookprobe.manager.TraditionalChinese] table, never from the
 *    English or the Japanese copy.
 *  - Text produced by the engine, the game or an unknown diagnostic is *not* in these
 *    tables and is therefore kept verbatim (`GameTopControl` only hands it strings it
 *    wrote itself, so an unknown diagnostic can never be rewritten).
 *  - The tile badges (`TileBadgeOverlay`) never call this at all: what sits on the tiles
 *    stays exactly as the game draws it.
 *
 * Wording follows Japanese mahjong usage (ツモ / ロン / 鳴き / シャンテン / リーチ /
 * 放銃 / フリテン) and the romanised forms English players actually use (tsumo, ron,
 * shanten, riichi, deal-in, furiten) instead of literal word-for-word renderings.
 */
internal object GameMenuText {

    private class Copy(val zh: String, val en: String, val ja: String)

    /** Exact table: short labels, state words, notes, captions and tile faces. */
    private val EXACT: Map<String, Copy> = buildMap {
        fun put(zh: String, en: String, ja: String) { put(zh, Copy(zh, en, ja)) }

        // ---- brand and navigation -------------------------------------------
        put("雀魂助手", "Mahjong Assistant", "雀魂アシスト")
        put("控制", "Control", "操作")
        put("状态", "Status", "状態")
        put("收起面板", "Collapse panel", "パネルを閉じる")
        put("收起", "Hide", "閉じる")
        put("助手", "Assistant", "アシスト")
        put("设置", "Settings", "設定")

        // ---- top handle and assistant capsule ---------------------------------
        put("助手运行中", "Assistant running", "アシスト実行中")
        put("助手未开启", "Assistant off", "アシスト未起動")
        put("无人值守中", "Unattended", "放置モード中")
        put("服务未连接", "No service", "サービス未接続")
        put("未开启", "Off", "オフ")
        put("读取显示模式", "Reading mode", "表示モードを読み込み中")
        put("无人值守开", "Unattended on", "放置モード オン")

        // ---- switch cards ----------------------------------------------------
        put("无人值守", "Unattended", "放置モード")
        put("自动控制", "Automation", "自動操作")
        put("助手服务自动分析并出牌", "The assistant analyses and plays for you",
            "アシストが自動で分析して打牌します")
        put("自动续打", "Auto next game", "自動連戦")
        put("自动下一局", "Auto next game", "自動で次局へ")
        put("整场牌局结束后自动继续", "Continues after the whole match ends",
            "対局が終わると自動で続けます")
        put("推荐与番点", "Discards & points", "打牌と点数")
        put("点击穿透固定面板上的推荐与番点", "Recommendations and hand values on the fixed panel",
            "固定パネルの打牌推奨と点数表示")
        put("固定面板", "Fixed panel", "固定パネル")

        // ---- on / off / pending ---------------------------------------------
        put("已开启", "On", "オン")
        put("已关闭", "Off", "オフ")
        put("开", "On", "オン")
        put("关", "Off", "オフ")
        put("待开启", "Pending", "オン待ち")

        // ---- unattended card -------------------------------------------------
        put("启动中…", "Starting…", "起動中…")
        put("启动中", "Starting", "起動中")
        put("运行中", "Running", "実行中")
        put("自检中 · 暂不可开", "Self-test · unavailable", "自己診断中 · オン不可")
        put("自检中", "Self-test", "自己診断中")
        put("未启动", "Not started", "未起動")
        put("待机", "Standby", "待機")
        put("点按关闭 · 助手服务继续运行", "Tap to turn off · the service keeps running",
            "タップでオフ · サービスは継続")
        put("模型自检中，结束后可开启", "Model self-test running; you can turn it on afterwards",
            "モデル自己診断中です。終了後オンにできます")
        put("点按开启，自动拉起管理应用服务", "Tap to start the manager's service",
            "タップで管理アプリのサービスを起動します")
        put("点按开启 AI 自动出牌", "Tap to let the AI play for you",
            "タップで AI の自動打牌を開始")

        // ---- auto next game card --------------------------------------------
        put("自检中不生效", "Inactive during self-test", "自己診断中は無効")
        put("待条件满足", "Waiting for conditions", "条件待ち")
        put("仅按下一局原按钮", "Presses the game's own next-game button only",
            "ゲームの「次局」ボタンのみを押します")
        put("牌局结束后自动继续，与上项独立", "Continues after the game; independent of the item above",
            "対局後に自動で続行。上の項目とは独立です")
        put("牌局结束后自动继续", "Continues when the game ends", "対局が終わると自動で続行")
        put("只调用游戏的“下一局”原按钮，不伪造请求；未开启时不排队",
            "Only presses the game's own “next game” button; nothing is forged and nothing queues while it is off",
            "ゲーム本来の「次局」ボタンのみを押します。リクエストの偽造や、オフ時の待機はしません")
        put("点按开启", "Tap to turn on", "タップでオン")

        // ---- discards & points card -----------------------------------------
        put("穿透固定面板上的推荐与番点", "Discards & points on the pass-through panel",
            "クリック透過パネルの打牌推奨と点数")
        put("固定面板显示建议牌与番点", "The fixed panel shows discards and hand values",
            "固定パネルに打牌推奨と点数を表示")
        put("已隐藏建议牌与番点", "Discards and hand values are hidden", "打牌推奨と点数を非表示")
        put("固定面板显示建议牌与番点 · 不影响其他功能",
            "The fixed panel shows discards and hand values · other features unaffected",
            "固定パネルに打牌推奨と点数を表示 · 他の機能に影響なし")
        put("已隐藏建议牌与番点 · 其他功能不受影响",
            "Discards and hand values are hidden · other features unaffected",
            "打牌推奨と点数を非表示 · 他の機能に影響なし")
        put("固定面板显示建议牌与番点；可随时在此关闭",
            "The fixed panel shows discards and hand values; turn it off here anytime",
            "固定パネルに打牌推奨と点数を表示。いつでもここでオフにできます")
        put("已隐藏建议牌与番点，其他功能不受影响",
            "Discards and hand values are hidden; other features are unaffected",
            "打牌推奨と点数を非表示にしました。他の機能に影響はありません")
        put("只控制点击穿透固定面板的内容；猫头提示与其他开关各自独立",
            "Only controls the click-through fixed panel; tile hints and the other switches are independent",
            "クリック透過の固定パネルの内容のみを制御。牌上ヒントと他のスイッチは独立です")

        // ---- header, capsule and status page --------------------------------
        put("读取显示模式", "Reading mode", "表示モードを読み込み中")
        put("无人值守开", "Unattended on", "放置モード オン")
        put("待机", "Standby", "待機")
        put("未连接", "Not connected", "未接続")
        put("管理应用 · 未安装", "Manager app · not installed", "管理アプリ · 未インストール")
        put("管理应用 · 无响应", "Manager app · no response", "管理アプリ · 応答なし")
        put("前台服务 · 自检中", "Foreground service · self-test", "常駐サービス · 自己診断中")
        put("前台服务 · 未运行", "Foreground service · not running", "常駐サービス · 停止中")
        put("前台服务 · 运行中", "Foreground service · running", "常駐サービス · 実行中")
        put("正在启动助手服务…", "Starting the assistant service…", "アシストサービスを起動中…")
        put("模型自检中 · 暂不可开启", "Model self-test · cannot start yet", "モデル自己診断中 · まだ開始できません")
        put("助手未启动", "Assistant is not started", "アシストは未起動です")
        put("无人值守已开启", "Unattended mode is on", "放置モードはオンです")
        put("助手已就绪 · 无人值守关闭", "Assistant ready · unattended off", "アシスト準備完了 · 放置モード オフ")
        put("关闭无人值守", "Turn unattended off", "放置モードをオフ")
        put("开启无人值守", "Turn unattended on", "放置モードをオン")
        put("点击开启即自动启动管理应用的前台分析服务",
            "Turning it on starts the manager's foreground analysis service",
            "オンにすると管理アプリの解析サービスが起動します")
        put("点击关闭只关闭无人值守，助手服务继续运行",
            "Turning it off only stops unattended mode; the service keeps running",
            "オフにすると放置モードだけが停止し、サービスは継続します")
        put("开启后按 AI 首选操作，可随时在此关闭", "Once on, the AI plays its top pick; turn it off here anytime",
            "オンにすると AI の第一候補で打牌。いつでもここでオフにできます")
        put("打开管理应用", "Open the manager app", "管理アプリを開く")
        put("未安装管理应用 · 点击打开", "Manager app not installed · tap to open", "管理アプリ未インストール · タップで開く")
        put("管理应用无响应 · 点击打开", "Manager app not responding · tap to open", "管理アプリ応答なし · タップで開く")
        put("点击打开管理应用", "Tap to open the manager app", "タップで管理アプリを開く")
        put("系统悬浮窗模式", "System overlay mode", "システムオーバーレイモード")

        // ---- notices the control writes itself ------------------------------
        put("正在启动分析与无人值守…", "Starting analysis and unattended mode…",
            "解析と放置モードを起動中…")
        put("未找到管理应用 · 请先安装", "Manager app not found · install it first",
            "管理アプリが見つかりません · 先にインストールしてください")
        put("管理应用无响应 · 点击重试", "Manager app not responding · tap to retry",
            "管理アプリ応答なし · タップで再試行")
        put("已请求启动 · 等待服务就绪", "Start requested · waiting for the service",
            "起動を要求しました · サービスの準備を待っています")
        put("正在通过管理应用完成启动…", "Finishing the start through the manager app…",
            "管理アプリ経由で起動を完了中…")
        put("无法打开管理应用 · 请手动开启助手", "Cannot open the manager app · start the assistant manually",
            "管理アプリを開けません · 手動でアシストを起動してください")
        put("无法启动前台服务 · 点击重试", "Cannot start the foreground service · tap to retry",
            "常駐サービスを起動できません · タップで再試行")
        put("启动中断 · 点击重试", "Start interrupted · tap to retry", "起動が中断しました · タップで再試行")
        put("启动超时 · 点击重试", "Start timed out · tap to retry", "起動がタイムアウトしました · タップで再試行")
        put("正在开启整场后续局…", "Enabling auto next game…", "自動次局をオンにしています…")
        put("正在关闭整场后续局…", "Disabling auto next game…", "自動次局をオフにしています…")
        put("管理应用无响应 · 设置可能未保存", "Manager app not responding · the setting may not be saved",
            "管理アプリ応答なし · 設定が保存されていない可能性があります")
        put("整场后续局已开启", "Auto next game is on", "自動次局をオンにしました")
        put("整场后续局已关闭 · 无人值守未变", "Auto next game is off · unattended unchanged",
            "自動次局をオフにしました · 放置モードは変更なし")
        put("正在显示推荐与番点面板…", "Showing the discards & points panel…", "打牌と点数のパネルを表示中…")
        put("正在隐藏推荐与番点面板…", "Hiding the discards & points panel…", "打牌と点数のパネルを非表示中…")
        put("管理应用无响应 · 面板设置可能未保存", "Manager app not responding · the panel setting may not be saved",
            "管理アプリ応答なし · パネル設定が保存されていない可能性があります")
        put("推荐与番点面板已开启", "Discards & points panel is on", "打牌と点数のパネルをオンにしました")
        put("推荐与番点面板已关闭 · 其他功能未变", "Discards & points panel is off · other features unchanged",
            "打牌と点数のパネルをオフにしました · 他の機能は変更なし")
        put("正在关闭无人值守…", "Turning unattended mode off…", "放置モードをオフにしています…")
        put("管理应用无响应 · 模式可能仍在开启", "Manager app not responding · the mode may still be on",
            "管理アプリ応答なし · モードがオンのままの可能性があります")
        put("未能关闭无人值守 · 点击重试", "Could not turn unattended off · tap to retry",
            "放置モードをオフにできません · タップで再試行")
        put("已关闭无人值守 · 助手服务保持运行", "Unattended off · the assistant service keeps running",
            "放置モードをオフにしました · サービスは継続します")
        put("未安装管理应用", "Manager app is not installed", "管理アプリがインストールされていません")

        // ---- accessibility labels -------------------------------------------
        put("展开雀魂助手", "Expand Mahjong Assistant", "雀魂アシストを展開")
        put("打开雀魂助手菜单", "Open the Mahjong Assistant menu", "雀魂アシストのメニューを開く")
        put("开关", " switch", "スイッチ")
        put("拖动悬浮窗", "Drag the floating window", "ウィンドウをドラッグ")
        put("收起成浮标", "Collapse to a bubble", "バブルに折りたたむ")
        put("助手分区", "Assistant tab", "アシストタブ")
        put("设置分区", "Settings tab", "設定タブ")
        put("助手分区，已选中", "Assistant tab, selected", "アシストタブ、選択中")
        put("设置分区，已选中", "Settings tab, selected", "設定タブ、選択中")
        put("，切换后查看推荐与对局状态", ", shows recommendations and game state",
            "、切り替えると打牌推奨と対局状態を表示")
        put("，切换后调整宽度、透明度和开关", ", adjust width, opacity and switches",
            "、切り替えると幅・透明度・スイッチを調整")
        put("点击展开助手面板，选择无人值守和自动下一局开关，可拖动",
            "Tap to open the assistant panel and set unattended and auto next game; draggable",
            "タップでアシストパネルを開き、放置モードと自動次局を設定。ドラッグ可")
        put("无人值守模式开关", "Unattended mode switch", "放置モードのスイッチ")
        put("推荐与番点面板开关", "Discards & points panel switch", "打牌と点数のパネルのスイッチ")
        put("整场结束后自动匹配（实验性）", "Auto next game after the match (experimental)",
            "対局後に自動で次局（試験的）")
        put("关闭 AI 助手", "Stop the AI assistant", "AI アシストを停止")

        // ---- floating window: header and bubble -----------------------------
        put("本地自检 · 样例", "Local self-test · sample", "ローカル自己診断 · サンプル")
        put("雀魂 · 本地 AI", "Mahjong · local AI", "雀魂 · ローカル AI")
        put("雀魂 · 本地助手", "Mahjong · local assistant", "雀魂 · ローカルアシスト")
        put("自检中，不会操作游戏", "Self-test: the game is not touched", "自己診断中。ゲームは操作しません")
        put("无人值守运行中", "Unattended mode running", "放置モード実行中")
        put("样例数据，仅供自检", "Sample data, for self-test only", "サンプルデータ（自己診断用）")
        put("对局已同步，等待操作", "Game synced, waiting for a move", "対局同期済み。操作を待っています")
        put("等待牌局连接", "Waiting for a game", "対局待ちです")
        put("等待牌局", "Waiting for a game", "対局待ちです")

        // ---- floating window: cards -----------------------------------------
        put("点开关即可随时暂停；手动出牌会关闭此模式",
            "Flip the switch to pause anytime; playing manually turns this mode off",
            "スイッチでいつでも一時停止。手動で打牌するとこのモードはオフになります")
        put("开启后按 AI 首选自动出牌，可随时停止",
            "Once on, the AI plays its top pick; you can stop anytime",
            "オンにすると AI の第一候補で自動打牌。いつでも停止できます")
        put("整场结束后调用游戏的结算与“下一局”，不新建或伪造请求",
            "After the match the game's own settlement and “next game” are used; no requests are forged",
            "対局後にゲーム本来の精算と「次局」を呼び出します。リクエストの偽造はしません")
        put("模型自检中不会自动匹配", "No auto next game during the self-test", "自己診断中は自動次局を行いません")
        put("仅调用游戏的“下一局”原按钮；可随时在此关闭",
            "Only presses the game's own “next game” button; turn it off here anytime",
            "ゲーム本来の「次局」ボタンのみを押します。いつでもここでオフにできます")
        put("默认关闭。与无人值守独立，开启后自动续局",
            "Off by default. Independent of unattended mode; continues automatically when on",
            "既定はオフ。放置モードとは独立で、オンにすると自動で次局へ進みます")

        // ---- floating window: recommendation block --------------------------
        put("先开启助手，再启动雀魂进入对局。中途开启或重连后，需等待完整牌局同步。",
            "Start the assistant first, then open Mahjong Soul. After a mid-game start or a reconnect, a full game sync is needed.",
            "先にアシストを起動し、雀魂を開いて対局に入ってください。途中から起動した場合や再接続後は、対局の完全な再同期が必要です。")
        put("实验性 Mortal v4 · 特征含近似计算", "Experimental Mortal v4 · approximate features",
            "試験的な Mortal v4 · 特徴量に近似計算を含みます")
        put("等待操作机会", "Waiting for a turn", "打牌の機会を待っています")
        put("🎯 推荐切牌", "🎯 Recommended discard", "🎯 おすすめの打牌")
        put("🛡️ 防守参考  ", "🛡️ Defence reference  ", "🛡️ 守備の参考  ")
        put("⚠️ 已立直：", "⚠️ Riichi: ", "⚠️ リーチ：")
        put("和牌率为听牌后的估计；风险指数越低越好，含打点权重，并非实际放铳概率。",
            "Win rates are estimates once tenpai; the risk index is better when lower. It includes hand-value weighting and is not a real deal-in probability.",
            "和了率はテンパイ後の推定値です。リスク指数は低いほど良く、打点補正を含むため実際の放銃率ではありません。")
        put("（含模型加载）", " (incl. model load)", "（モデル読み込みを含む）")
        put("手机本地计算 · ", "On-device · ", "スマホ内で計算 · ")
        put("首选", "Top", "第一候補")
        put("跳过", "Pass", "パス")
        put("和牌", "Win", "アガリ")
        put("立直", "Riichi", "リーチ")
        put("拔北", "Kita", "北抜き")
        put("九种九牌流局", "Nine terminals draw", "九種九牌による流局")

        // ---- floating window: settings pane ---------------------------------
        put("窗口外观", "Appearance", "ウィンドウ表示")
        put("背景不透明度", "Background opacity", "背景の不透明度")
        put("窗口宽度", "Window width", "ウィンドウ幅")
        put("助手操作", "Assistant actions", "アシスト操作")
        put("停止助手会关闭无人值守模式并隐藏悬浮窗；拖动标题栏移动，点“收起”变成浮标。",
            "Stopping the assistant turns unattended mode off and hides this window. Drag the title bar to move it; tap \u201cHide\u201d to collapse it to a bubble.",
            "アシストを停止すると放置モードがオフになり、このウィンドウも閉じます。タイトルバーのドラッグで移動、「閉じる」でバブルに折りたたみます。")
        put("停止助手", "Stop assistant", "アシスト停止")

        // ---- fixed click-through recommendation panel -----------------------
        put("推荐切牌 · 决策概率", "Discard · decision probability", "打牌推奨 · 判断確率")
        put("等待出牌机会", "Waiting for a turn", "打牌の機会を待っています")
        put("和牌番点", "Hand value", "和了の点数")
        put("当前听牌番点", "Hand value at tenpai", "テンパイ時の点数")
        put("（振听不可荣和）", " (furiten: no ron)", "（フリテン：ロン不可）")
        put("无役", "No yaku", "役なし")
        put("役满", "Yakuman", "役満")
        put("基础估算；不含里宝/一发/本场/供托/和牌赤5",
            "Baseline estimate; excludes ura-dora, ippatsu, honba, riichi sticks and aka-5 on the win",
            "概算です。裏ドラ・一発・本場・供託・和了時の赤5は含みません")
        put("已听牌 · 暂无可用番点", "Tenpai · no hand value available", "テンパイ · 利用できる点数なし")
        put("等待手牌分析", "Waiting for the hand analysis", "手牌の分析を待っています")
        put("防守参考", "Defence reference", "守備の参考")
        put("已立直：", "Riichi: ", "リーチ：")

        // ---- mahjong words shared by the panel and the window ---------------
        put("听牌", "tenpai", "テンパイ")
        put("和牌形", "winning shape", "和了形")
        put("振听", "furiten", "フリテン")
        put("向听", "shanten", "シャンテン")
        put("下家", "next player", "下家")
        put("对家", "opposite player", "対面")
        put("上家", "previous player", "上家")

        // ---- tile faces ------------------------------------------------------
        put("东", "East", "東")
        put("南", "South", "南")
        put("西", "West", "西")
        put("北", "North", "北")
        put("白", "White", "白")
        put("发", "Green", "發")
        put("中", "Red", "中")
        put("赤5", "Red 5", "赤5")
        put("万", "m", "萬")
        put("筒", "p", "筒")
        put("索", "s", "索")
    }

    /** Strings a surface assembles itself: the dynamic tail stays verbatim. */
    private val PREFIX: List<Pair<String, Copy>> = listOf(
        "自动下一局 · " to Copy("自动下一局 · ", "Auto next game · ", "自動で次局へ · "),
        "助手 · " to Copy("助手 · ", "Assistant · ", "アシスト · "),
        "系统悬浮窗不可用 · " to Copy("系统悬浮窗不可用 · ", "System overlay unavailable · ",
            "システムオーバーレイ利用不可 · "),
        "手机本地计算 · " to Copy("手机本地计算 · ", "On-device · ", "スマホ内で計算 · "),
        "风险 " to Copy("风险 ", "Risk ", "リスク "),
        "切" to Copy("切", "Discard ", "打 "),
    )

    /**
     * Translates one known manager string; everything else comes back untranslated.
     *
     * For [ManagerLanguage.TRADITIONAL] every branch below converts the **Chinese** source —
     * the exact row, the Chinese prefix or the Chinese template — through the compiled table.
     * A string the table does not know (an engine error, a game message, an unknown
     * diagnostic) is returned verbatim, exactly as the Simplified UI would show it.
     */
    fun text(zh: String, language: ManagerLanguage): String {
        if (language == ManagerLanguage.SIMPLIFIED || zh.isEmpty()) return zh
        EXACT[zh]?.let { return pick(it, language) }
        for ((key, copy) in PREFIX) {
            if (zh.startsWith(key)) return pick(copy, language) + zh.substring(key.length)
        }
        pattern(zh.trim(), language)?.let { return it }
        return zh
    }

    private fun pick(copy: Copy, language: ManagerLanguage): String = when (language) {
        ManagerLanguage.SIMPLIFIED -> copy.zh
        // The Chinese source of the same row, converted: an untranslated row falls back to the
        // Simplified wording instead of leaking the English or the Japanese rendering.
        ManagerLanguage.TRADITIONAL -> TraditionalChinese.text(copy.zh)
        ManagerLanguage.ENGLISH -> copy.en
        ManagerLanguage.JAPANESE -> copy.ja
    }

    private fun pattern(text: String, language: ManagerLanguage): String? {
        // "<n>向听 · 听牌后显示番点"
        Regex("^(-?\\d+)向听 · 听牌后显示番点$").matchEntire(text)?.let {
            val n = it.groupValues[1]
            return when (language) {
                ManagerLanguage.ENGLISH -> "$n-shanten · hand value shown at tenpai"
                ManagerLanguage.JAPANESE -> "$n シャンテン · テンパイ後に点数を表示"
                ManagerLanguage.TRADITIONAL -> "$n 向聽 · 聽牌後顯示番點"
                else -> null
            }
        }
        // "荣和 <score> / 自摸 <score>"
        Regex("^荣和 (.+) / 自摸 (.+)$").matchEntire(text)?.let {
            val ron = scoreText(it.groupValues[1], language)
            val tsumo = scoreText(it.groupValues[2], language)
            return when (language) {
                ManagerLanguage.ENGLISH -> "Ron $ron · Tsumo $tsumo"
                ManagerLanguage.JAPANESE -> "ロン $ron · ツモ $tsumo"
                ManagerLanguage.TRADITIONAL -> "榮和 $ron · 自摸 $tsumo"
                else -> null
            }
        }
        // "立直 荣<score> / 摸<score>"
        Regex("^立直 荣(.+) / 摸(.+)$").matchEntire(text)?.let {
            val ron = scoreText(it.groupValues[1], language)
            val tsumo = scoreText(it.groupValues[2], language)
            return when (language) {
                ManagerLanguage.ENGLISH -> "Riichi · ron $ron · tsumo $tsumo"
                ManagerLanguage.JAPANESE -> "リーチ ロン$ron · ツモ$tsumo"
                ManagerLanguage.TRADITIONAL -> "立直 榮$ron · 摸$tsumo"
                else -> null
            }
        }
        // A score fragment on its own: "2番30符 2000点" / "役满 32000点"
        if (SCORE.matches(text) || YAKUMAN.matches(text)) return scoreText(text, language)
        // "拔北 ×2：已计入三麻番数"
        Regex("^拔北 ×(\\d+)：已计入三麻番数$").matchEntire(text)?.let {
            val n = it.groupValues[1]
            return when (language) {
                ManagerLanguage.ENGLISH -> "Kita x$n: counted in the 3-player hand value"
                ManagerLanguage.JAPANESE -> "北抜き ×$n：三麻の点数に反映済み"
                ManagerLanguage.TRADITIONAL -> "拔北 ×$n：已計入三麻番數"
                else -> null
            }
        }
        // "估计和率 12.3%" / "和率 12.3%"
        Regex("^(估计和率|和率) ([0-9.]+)%$").matchEntire(text)?.let {
            val rate = it.groupValues[2]
            return when (language) {
                ManagerLanguage.ENGLISH -> "Win rate $rate%"
                ManagerLanguage.JAPANESE -> "和了率 $rate%"
                ManagerLanguage.TRADITIONAL ->
                    (if (text.startsWith("估计")) "估計" else "") + "和率 $rate%"
                else -> null
            }
        }
        // "进张 8 枚"
        Regex("^进张 (\\d+) 枚$").matchEntire(text)?.let {
            val n = it.groupValues[1]
            return when (language) {
                ManagerLanguage.ENGLISH -> "$n winning tiles"
                ManagerLanguage.JAPANESE -> "有効牌 $n 枚"
                ManagerLanguage.TRADITIONAL -> "進張 $n 枚"
                else -> null
            }
        }
        // "放铳风险指数 3.4"
        Regex("^放铳风险指数 (.+)$").matchEntire(text)?.let {
            val n = it.groupValues[1]
            return when (language) {
                ManagerLanguage.ENGLISH -> "Deal-in risk index $n"
                ManagerLanguage.JAPANESE -> "放銃リスク指数 $n"
                ManagerLanguage.TRADITIONAL -> "放銃風險指數 $n"
                else -> null
            }
        }
        // "4人" / "3人" — the round line tail
        Regex("^(\\d+)人$").matchEntire(text)?.let {
            val n = it.groupValues[1]
            return when (language) {
                ManagerLanguage.ENGLISH -> "$n players"
                ManagerLanguage.JAPANESE -> "${n}人"
                ManagerLanguage.TRADITIONAL -> "${n}人"
                else -> null
            }
        }
        return null
    }

    private val SCORE = Regex("^(-?\\d+)番(\\d+)符 (-?\\d+)点$")
    private val YAKUMAN = Regex("^役满 (-?\\d+)点$")

    /** "2番30符 2000点" -> "2 han 30 fu, 2000 pts" / "2翻 30符 2000点". *\/ */
    internal fun scoreText(zh: String, language: ManagerLanguage): String {
        SCORE.matchEntire(zh)?.let {
            val han = it.groupValues[1]
            val fu = it.groupValues[2]
            val points = it.groupValues[3]
            return when (language) {
                ManagerLanguage.ENGLISH -> "$han han $fu fu, $points pts"
                ManagerLanguage.JAPANESE -> "${han}翻 ${fu}符 ${points}点"
                // Chinese either way: the Traditional row for this exact shape, digits untouched.
                ManagerLanguage.TRADITIONAL -> TraditionalChinese.text(zh)
                ManagerLanguage.SIMPLIFIED -> zh
            }
        }
        YAKUMAN.matchEntire(zh)?.let {
            val points = it.groupValues[1]
            return when (language) {
                ManagerLanguage.ENGLISH -> "yakuman, $points pts"
                ManagerLanguage.JAPANESE -> "役満 ${points}点"
                ManagerLanguage.TRADITIONAL -> TraditionalChinese.text(zh)
                ManagerLanguage.SIMPLIFIED -> zh
            }
        }
        return zh
    }

    /**
     * Renders one tile code (`5m`, `0m`, `5z`, `E`, `P`, `5sr`, …) for display.
     *
     * The code is data, not phrasing: only the suit and honour letters the engine
     * already uses are mapped, so an unrecognised code is echoed instead of guessed.
     */
    fun tileName(tile: String, language: ManagerLanguage): String {
        if (tile.isEmpty()) return tile
        val bare = tile.removeSuffix("r")
        val honour = when (bare) {
            "E", "1z" -> "东"
            "S", "2z" -> "南"
            "W", "3z" -> "西"
            "N", "4z" -> "北"
            "P", "5z" -> "白"
            "F", "6z" -> "发"
            "C", "7z" -> "中"
            else -> null
        }
        if (honour != null) return text(honour, language)
        if (bare.length < 2) return tile
        val rank = bare[0]
        val suitZh = when (bare[1]) {
            'm' -> "万"
            'p' -> "筒"
            's' -> "索"
            else -> return tile
        }
        val red = rank == '0'
        val face = if (red) null else rank.toString()
        return when (language) {
            ManagerLanguage.SIMPLIFIED -> if (red) "赤5" + text(suitZh, language) else face + text(suitZh, language)
            // Same shape as the Simplified face, with the suit and the red five converted; the
            // rank character and the tile code itself are copied, never converted.
            ManagerLanguage.TRADITIONAL -> {
                val suit = TraditionalChinese.text(suitZh)
                if (red) TraditionalChinese.text("赤5") + suit else face + suit
            }
            ManagerLanguage.ENGLISH -> if (red) "Red 5" + text(suitZh, language) else face + text(suitZh, language)
            ManagerLanguage.JAPANESE -> if (red) "赤" + text(suitZh, language) else face + text(suitZh, language)
        }
    }

    /**
     * The round shorthand ("E1", "S2", "W3-1") with its seat letter spelled out for
     * [language]. Only the letters are mapped; an unknown notation is echoed unchanged.
     */
    fun roundLabel(round: String, language: ManagerLanguage): String {
        if (round.isEmpty()) return round
        val upper = when (language) {
            ManagerLanguage.ENGLISH -> listOf("E" to "East ", "S" to "South ", "W" to "West ", "N" to "North ")
            ManagerLanguage.JAPANESE -> listOf("E" to "東", "S" to "南", "W" to "西", "N" to "北")
            ManagerLanguage.SIMPLIFIED -> listOf("E" to "东", "S" to "南", "W" to "西", "N" to "北")
            // The seat letters are the engine's own notation; only the letter is spelled out, the
            // round number that follows it stays exactly as received.
            ManagerLanguage.TRADITIONAL -> listOf("E" to "東", "S" to "南", "W" to "西", "N" to "北")
        }
        var text = round
        for ((letter, word) in upper) text = text.replace(letter, word)
        return text
    }

    /**
     * Reads the language tag the manager reported in a reply. An absent, empty or
     * unknown tag keeps [current], so a provider that has not been rebuilt yet can never
     * flip a surface into a third state.
     */
    fun languageOf(tag: String?, current: ManagerLanguage): ManagerLanguage {
        val raw = tag?.trim().orEmpty()
        if (raw.isEmpty()) return current
        val lower = raw.lowercase()
        return when {
            // Chinese is split by script and region, using the same resolver as the manager's
            // preference: zh-Hant / zh-TW / zh-HK / zh-MO select traditional Chinese, while
            // zh-Hans / zh-CN / zh-SG / plain zh stay simplified, and an explicit Hans wins over
            // a Traditional region. An unknown Chinese shape keeps [current] instead of guessing.
            lower == "zh" || lower.startsWith("zh-") || lower.startsWith("zh_") ->
                ManagerLanguageChoice.fromTag(raw).explicitOrNull() ?: current
            lower == "en" || lower.startsWith("en-") || lower.startsWith("en_") -> ManagerLanguage.ENGLISH
            lower == "ja" || lower.startsWith("ja-") || lower.startsWith("ja_") -> ManagerLanguage.JAPANESE
            else -> current
        }
    }

    /** The `uiLanguage` field of a hint snapshot; absent keeps [current]. *\/ */
    fun languageOf(snapshot: JSONObject?, current: ManagerLanguage): ManagerLanguage =
        languageOf(snapshot?.optString("uiLanguage", ""), current)
}
