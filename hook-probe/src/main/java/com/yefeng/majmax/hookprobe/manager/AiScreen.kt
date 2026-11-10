package com.yefeng.majmax.hookprobe.manager

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The assistant page.
 *
 * Every static line goes through [tr] with its Chinese text first, so Chinese stays the
 * default and an untranslated case can only fall back to the original. The two status lines
 * that come from service state are rendered with [localizedTrimmed]: those strings are also
 * sent to the game and the floating window and stay Chinese at the source; only the manager's
 * own shapes are translated, anything else is shown verbatim.
 */
@Composable
internal fun AiScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Subscribes this screen to the language, so a switch re-translates it in place, and to the
    // stored choice, which is what the radio rows and the "current:" line follow.
    val language = managerLanguage()
    val languageChoice = managerLanguageChoice()
    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var displayMode by remember { mutableStateOf(AssistantDisplayModeSettings.read(context)) }
    var showLicense by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var languageMenu by remember { mutableStateOf(false) }
    var autoDelay by remember { mutableStateOf(AutoDelaySettings.read(context)) }
    // Lives in AutoRematchState so a change made from the floating window or the in-game
    // capsule shows up here as soon as this screen is composed/resumed.
    val autoRematch by AutoRematchState.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        AutoRematchState.mutable.value = AutoRematchSettings.read(context)
        onDispose { }
    }
    var fourModel by remember { mutableStateOf(OnnxModelStore.info(context, 4)) }
    var threeModel by remember { mutableStateOf(OnnxModelStore.info(context, 3)) }
    // Kept as the Chinese source text; ModelStatusLine re-renders it in the selected language
    // at display time, so the choice also applies to messages produced before the switch.
    var modelMessage by remember {
        mutableStateOf(
            "支持 Akagi ONNX，以及实验性四麻/三麻 Mortal v4（ONNX/PTH/PT，单文件上限 ${OnnxModelStore.MAX_MODEL_MIB} MiB）。" +
                "自动识别人数并保存到对应槽位；兼容的自定义模型仍需手动开启无人值守。模型仅保存在本机。",
        )
    }
    val scope = rememberCoroutineScope()
    val status by AiStatus.state.collectAsStateWithLifecycle()
    val autoStatus by AutoDiscardState.state.collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            modelMessage = "正在复制并检查模型…"
            runCatching { withContext(Dispatchers.IO) { OnnxModelStore.import(context, uri) } }
                .onSuccess { imported ->
                    fourModel = OnnxModelStore.info(context, 4)
                    threeModel = OnnxModelStore.info(context, 3)
                    runCatching { AiNative.configurePolicy(OnnxModelStore.enabledMask(context)) }
                    modelMessage = "${imported.players} 人模型已校验并暂存；下一次完整牌局同步时生效。"
                }
                .onFailure { error -> modelMessage = error.message?.take(160) ?: "模型导入失败，继续使用内置模型。" }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayAllowed = Settings.canDrawOverlays(context)
                // The stored selection only. It is what the next service start will use;
                // resuming this screen never pushes a mode into a running service.
                displayMode = AssistantDisplayModeSettings.read(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun start(demo: Boolean) {
        AiOverlayService.start(context, demo)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    ManagerPage(padding) {
        // ---- language switch: the first card on the page, the only new control ----
        LanguageBar(
            language = language,
            choice = languageChoice,
            expanded = languageMenu,
            onExpandedChange = { languageMenu = it },
            onSelect = { chosen ->
                languageMenu = false
                // Stores the choice only; the effective language is re-derived immediately
                // (device locale for "follow system", the explicit language otherwise).
                ManagerLanguageState.select(context, chosen)
            },
            onShowDetails = { showLanguage = true },
        )
        Text(language.tr("本地牌局助手", "Local game assistant", "ローカル対局アシスト"),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(language.tr("在手机本地分析牌局并提供无人值守控制。",
                "Analyzes the match on this phone and runs the controls while you are away.",
                "端末内で対局を分析し、放置中の操作を自動で行います。"),
            style = MaterialTheme.typography.bodyMedium)
        ManagerCard {
            Text(
                if (status.running) language.tr("助手已开启", "Assistant is running", "アシストは起動中です")
                else language.tr("助手未开启", "Assistant is off", "アシストは停止中です"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(localizedTrimmed(status.message))
            Text(language.tr("启动助手后进入雀魂，点击屏幕顶部的把手打开控制菜单。",
                    "Start the assistant, then open Majsoul and tap the handle at the top of the screen to open the control menu.",
                    "アシストを起動して雀魂を開き、画面上部のハンドルをタップすると操作メニューが開きます。"),
                style = MaterialTheme.typography.bodySmall)
            ManagerButton(onClick = { start(false) }) {
                Text(language.tr("开启实时助手", "Start live assistant", "リアルタイムアシストを起動"))
            }
            ManagerActions(
                first = {
                    ManagerOutlinedButton(onClick = { start(true) }, modifier = it) {
                        Text(language.tr("本地模型自检", "Local model self-check", "ローカルモデルの自己診断"))
                    }
                },
                second = {
                    ManagerOutlinedButton(onClick = { AiOverlayService.stop(context) }, modifier = it,
                        enabled = status.running) {
                        Text(language.tr("停止助手", "Stop assistant", "アシストを停止"))
                    }
                },
            )
            if (language != ManagerLanguage.SIMPLIFIED) {
                // Deliberately limited claim: the manager UI is translated, the in-game menu,
                // the hint text drawn on the tiles and some engine messages are not.
                Text(language.tr(
                        "游戏内菜单、说明牌上的提示和部分引擎消息仍保留原文；本应用只切换管理界面的语言。",
                        "The in-game menu, the hints drawn on the tiles and some engine messages stay in their original language; only this manager UI switches language.",
                        "ゲーム内メニュー、牌に表示されるヒント、一部のエンジンメッセージは元の言語のままです。本アプリでは管理画面の表示言語のみを切り替えます。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ManagerCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(language.tr("无人值守模式", "Unattended mode", "放置モード"),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = autoStatus.enabled, enabled = status.running,
                    onCheckedChange = { AiOverlayService.toggleAuto() })
            }
            Text(localizedTrimmed(autoStatus.message))
            Text(language.tr("操作延迟 ${localizedDelayLabel(autoDelay, language)}",
                    "Move delay: ${localizedDelayLabel(autoDelay, language)}",
                    "打牌ディレイ：${localizedDelayLabel(autoDelay, language)}"),
                style = MaterialTheme.typography.bodyMedium)
            Text(language.tr("最小延迟 ${autoDelay.seconds(autoDelay.minTenths)} 秒",
                    "Minimum delay ${autoDelay.seconds(autoDelay.minTenths)} s",
                    "最小ディレイ ${autoDelay.seconds(autoDelay.minTenths)} 秒"),
                style = MaterialTheme.typography.bodySmall)
            Slider(value = autoDelay.minTenths.toFloat(),
                onValueChange = { autoDelay = autoDelay.withMinimum(it.roundToInt()) },
                onValueChangeFinished = { AutoDelaySettings.save(context, autoDelay) },
                valueRange = 0f..50f, steps = 49,
                modifier = Modifier.semantics {
                    contentDescription = language.tr("最小操作延迟", "Minimum move delay",
                        "最小ディレイ")
                })
            Text(language.tr("最大延迟 ${autoDelay.seconds(autoDelay.maxTenths)} 秒",
                    "Maximum delay ${autoDelay.seconds(autoDelay.maxTenths)} s",
                    "最大ディレイ ${autoDelay.seconds(autoDelay.maxTenths)} 秒"),
                style = MaterialTheme.typography.bodySmall)
            Slider(value = autoDelay.maxTenths.toFloat(),
                onValueChange = { autoDelay = autoDelay.withMaximum(it.roundToInt()) },
                onValueChangeFinished = { AutoDelaySettings.save(context, autoDelay) },
                valueRange = 0f..50f, steps = 49,
                modifier = Modifier.semantics {
                    contentDescription = language.tr("最大操作延迟", "Maximum move delay",
                        "最大ディレイ")
                })
            Text(language.tr(
                    "范围 0–5 秒，步进 0.1 秒；两端相同时为固定延迟。默认 2–5 秒，设置自动保存，下次操作生效，当前倒计时不变。出牌、立直、鸣牌、和牌、跳过和结算继续共用此设置。",
                    "Range 0–5 s in steps of 0.1 s; equal ends mean a fixed delay. Defaults to 2–5 s. Changes are saved automatically and apply from the next action; a countdown already running is unchanged. Discard, riichi, calls, win, skip and settlement all share this setting.",
                    "範囲は 0〜5 秒、0.1 秒刻み。両端が同じなら固定ディレイです。初期値は 2〜5 秒。設定は自動保存され、次の操作から適用されます（進行中の待機は変わりません）。打牌・立直・副露・和了・スキップ・精算はすべてこの設定を共有します。"),
                style = MaterialTheme.typography.bodySmall)
            Text(language.tr(
                    "首次默认关闭；开启状态会保存。按 AI 首选操作，结算后继续下一局；断线或游戏退出后尝试恢复，取得完整牌局后继续。所有操作按上方范围等待。手动出牌或停止助手会关闭此模式。",
                    "Off by default; the switch state is remembered. It plays the AI's preferred move and moves on to the next hand after settlement. After a disconnect or game exit it tries to recover and continues once a full game state is available. Every move waits within the range above. Playing manually or stopping the assistant turns the mode off.",
                    "初期状態はオフで、オン・オフは保存されます。AI の推奨手を打ち、精算後は次の局へ進みます。切断やゲーム終了の後は復帰を試み、対局状態が揃い次第続行します。すべての操作は上の範囲で待機します。手動で打牌するかアシストを停止すると、このモードはオフになります。"),
                style = MaterialTheme.typography.bodySmall)
            Text(language.tr(
                    "关闭模式后可正常退出游戏；也可通过游戏内顶部菜单直接关闭，关闭时本应用服务不会停止。整场结束后是否继续匹配由下方独立开关控制。",
                    "With the mode off you can exit the game normally. You can also turn it off from the in-game top menu; that never stops this app's service. Whether to keep matching after the whole game ends is a separate switch below.",
                    "モードをオフにすれば通常どおりゲームを終了できます。ゲーム内の上部メニューからもオフにでき、その場合も本アプリのサービスは停止しません。対局終了後に続けてマッチングするかは、下の独立したスイッチで設定します。"),
                style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(language.tr("整场结束后自动匹配（实验性）",
                        "Auto-match after the game ends (experimental)",
                        "対局終了後に自動マッチング（試験的）"),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = autoRematch, onCheckedChange = { value ->
                    // Preference only: never starts/stops the service, never flips the
                    // unattended opt-in and never touches the display mode.
                    AiOverlayService.publishRematch(context, value)
                }, modifier = Modifier.semantics {
                    contentDescription = language.tr("整场结束后自动匹配（实验性）",
                        "Auto-match after the game ends (experimental)",
                        "対局終了後に自動マッチング（試験的）")
                })
            }
            Text(
                if (!autoRematch) language.tr("当前未开启；也可在悬浮窗或游戏内顶部菜单直接切换。",
                    "Currently off. You can also switch it from the floating window or the in-game top menu.",
                    "現在はオフです。フローティングウィンドウやゲーム内の上部メニューからも切り替えられます。")
                else language.tr("已开启，与无人值守开关独立。",
                    "On. Independent of the unattended switch.",
                    "オンです。放置モードのスイッチとは独立しています。"),
                style = MaterialTheme.typography.bodySmall)
            Text(language.tr(
                    "默认关闭。与无人值守开关独立；手动打牌也可自动续局，模型自检时不生效。只调用游戏已有的结算、收益和下一局原按钮，不新增或伪造请求；遇到未知界面、对局中断或手动操作时暂停并等待。本场自动匹配失败或手动取消后不再重试；未知收益弹窗可能需要手动处理。",
                    "Off by default and independent of the unattended switch. It also works when you play manually, but not during a model self-check. It only presses the game's own settlement, reward and next-game buttons; no new or forged requests. It pauses and waits on unknown screens, interrupted games or manual input. It does not retry after a failed or manually cancelled match, and an unknown reward dialog may need to be handled by hand.",
                    "初期状態はオフで、放置モードのスイッチとは独立しています。手動で打つ場合も自動で次局へ進みますが、モデル自己診断中は動作しません。ゲーム本来の精算・報酬・次局のボタンを押すだけで、新たな要求や偽の要求は送りません。未知の画面、対局の中断、手動操作を検知すると一時停止して待機します。この対局で失敗した、または手動でキャンセルした後は再試行しません。未知の報酬ダイアログは手動での対応が必要な場合があります。"),
                style = MaterialTheme.typography.bodySmall)
        }
        Text(language.tr("实验性 Mortal v4 支持四麻和三麻，可手动开启无人值守；特征含近似计算，尚未完成与上游的完整一致性验证。",
                "Experimental Mortal v4 supports four-player and three-player games and can be used with unattended mode. Its features include approximations and have not yet been fully validated against upstream.",
                "試験的な Mortal v4 は四人打ち・三人打ちに対応し、放置モードも手動で有効にできます。特徴量には近似計算が含まれ、上流との完全な一致検証はまだ完了していません。"),
            style = MaterialTheme.typography.bodySmall)
        Text(language.tr("策略模型", "Policy models", "戦略モデル"),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        PolicyModelCard(
            players = 4, model = fourModel, selectedCustom = OnnxModelStore.selectedCustom(context, 4),
            onImport = { modelPicker.launch(arrayOf("*/*")) },
            onBuiltIn = {
                OnnxModelStore.selectBuiltIn(context, 4)
                runCatching { AiNative.configurePolicy(OnnxModelStore.enabledMask(context)) }
                modelMessage = "四麻已选择内置策略；当前牌局保持原模型，下一次完整牌局同步时生效。"
            },
            onCustom = {
                runCatching {
                    OnnxModelStore.selectCustom(context, 4)
                    AiNative.configurePolicy(OnnxModelStore.enabledMask(context))
                    modelMessage = "四麻自定义策略将在下一次完整牌局同步时生效。"
                }.onFailure { modelMessage = it.message ?: "无法启用模型" }
            },
            onDelete = {
                OnnxModelStore.delete(context, 4); fourModel = null
                runCatching { AiNative.configurePolicy(OnnxModelStore.enabledMask(context)) }
                modelMessage = "四麻自定义模型已从可选槽位移除。"
            },
            onSelfTest = {
                scope.launch {
                    modelMessage = withContext(Dispatchers.IO) {
                        runCatching { OnnxModelStore.selfTest(context, 4) }.getOrElse { it.message ?: "自检失败" }
                    }
                }
            },
        )
        PolicyModelCard(
            players = 3, model = threeModel, selectedCustom = OnnxModelStore.selectedCustom(context, 3),
            onImport = { modelPicker.launch(arrayOf("*/*")) },
            onBuiltIn = {
                OnnxModelStore.selectBuiltIn(context, 3)
                runCatching { AiNative.configurePolicy(OnnxModelStore.enabledMask(context)) }
                modelMessage = "三麻已选择内置策略；当前牌局保持原模型，下一次完整牌局同步时生效。"
            },
            onCustom = {
                runCatching {
                    OnnxModelStore.selectCustom(context, 3)
                    AiNative.configurePolicy(OnnxModelStore.enabledMask(context))
                    modelMessage = "三麻自定义策略将在下一次完整牌局同步时生效。"
                }.onFailure { modelMessage = it.message ?: "无法启用模型" }
            },
            onDelete = {
                OnnxModelStore.delete(context, 3); threeModel = null
                runCatching { AiNative.configurePolicy(OnnxModelStore.enabledMask(context)) }
                modelMessage = "三麻自定义模型已从可选槽位移除。"
            },
            onSelfTest = {
                scope.launch {
                    modelMessage = withContext(Dispatchers.IO) {
                        runCatching { OnnxModelStore.selfTest(context, 3) }.getOrElse { it.message ?: "自检失败" }
                    }
                }
            },
        )
        Text(modelMessageText(modelMessage, language),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ManagerCard {
            Text(language.tr("显示与控制模式", "Display and control mode", "表示と操作のモード"),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(language.tr("提示和无人值守控制只用一套界面：游戏内 Hook 或系统悬浮窗，二选一，不会同时出现。",
                    "Hints and unattended controls use exactly one surface: the in-game hook or the system floating window, never both.",
                    "ヒントと放置操作の表示はどちらか一方のみです。ゲーム内フックとシステムのフローティングウィンドウが同時に出ることはありません。"),
                style = MaterialTheme.typography.bodySmall)
            // A running service keeps the mode it snapshotted when it started; the selection
            // below is therefore stored for the *next* start and the buttons are disabled
            // while the assistant runs, so no click can create the impression of a live switch.
            val modeLocked = status.running
            ManagerActions(
                first = {
                    ManagerOutlinedButton(onClick = {
                        if (modeLocked) return@ManagerOutlinedButton
                        displayMode = AssistantDisplayMode.HOOK
                        AssistantDisplayModeSettings.save(context, displayMode)
                    }, modifier = it, enabled = !modeLocked) {
                        Text(
                            if (displayMode == AssistantDisplayMode.HOOK) language.tr("已选：游戏内 Hook",
                                "Selected: in-game hook", "選択中：ゲーム内フック")
                            else language.tr("游戏内 Hook", "In-game hook", "ゲーム内フック"),
                        )
                    }
                },
                second = {
                    ManagerOutlinedButton(onClick = {
                        if (modeLocked) return@ManagerOutlinedButton
                        displayMode = AssistantDisplayMode.OVERLAY
                        AssistantDisplayModeSettings.save(context, displayMode)
                    }, modifier = it, enabled = !modeLocked) {
                        Text(
                            if (displayMode == AssistantDisplayMode.OVERLAY) language.tr("已选：系统悬浮窗",
                                "Selected: floating window", "選択中：フローティング")
                            else language.tr("系统悬浮窗", "Floating window", "フローティング"),
                        )
                    }
                },
            )
            if (modeLocked) {
                Text(language.tr("助手正在运行：以上选择将在下次开启助手时生效。本次运行固定使用启动时读取的模式，运行中不会切换，也不会重建界面。",
                        "The assistant is running: the choice above takes effect the next time you start it. This run keeps the mode it read at startup; it never switches or rebuilds its UI while running.",
                        "アシスト実行中です。上の選択は次回アシスト起動時に反映されます。実行中のモードは起動時に読み込んだものに固定され、切り替えや UI の再構築は行いません。"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(language.tr("当前选择将在下一次开启助手（以及随后启动的游戏）时生效。",
                        "The current choice takes effect the next time the assistant starts (and in the game started afterwards).",
                        "現在の選択は、次にアシストを起動したとき（およびその後に起動したゲーム）から反映されます。"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (displayMode) {
                AssistantDisplayMode.HOOK -> {
                    Text(language.tr(
                        "游戏内 Hook（默认）：控制把手、胶囊菜单和牌面提示都画在游戏窗口内，不需要悬浮窗权限。改选后请完全重启游戏，游戏内界面才会按新选择创建。",
                        "In-game hook (default): the control handle, capsule menu and tile hints are drawn inside the game window, so no overlay permission is needed. After changing this, fully restart the game so its UI is created with the new choice.",
                        "ゲーム内フック（既定）：操作ハンドル、カプセルメニュー、牌のヒントはゲームウィンドウ内に描画されるため、フローティング権限は不要です。変更後はゲームを完全に再起動すると、新しい選択で UI が作成されます。"))
                }
                AssistantDisplayMode.OVERLAY -> {
                    Text(language.tr(
                        "系统悬浮窗：只使用独立的悬浮窗和牌面提示浮层，不会在游戏窗口内创建把手或提示。",
                        "Floating window: only the standalone window and the hint overlay are used; no handle or hint is created inside the game window.",
                        "フローティングウィンドウ：独立したウィンドウとヒントのオーバーレイのみを使用し、ゲームウィンドウ内にハンドルやヒントは作成しません。"))
                    if (overlayAllowed) {
                        Text(language.tr("已获得悬浮窗权限，助手开启后悬浮窗即可显示。",
                            "Overlay permission granted; the window appears as soon as the assistant starts.",
                            "フローティング権限を取得済みです。アシストを起動するとウィンドウが表示されます。"))
                    } else {
                        Text(language.tr(
                            "尚未获得悬浮窗权限：助手仍按悬浮窗模式运行，但不会显示界面，也不会自动改用游戏内 Hook；日志会记录原因。",
                            "Overlay permission is not granted: the assistant still runs in window mode but shows no UI, and it never falls back to the in-game hook on its own; the reason is recorded in the log.",
                            "フローティング権限がありません。アシストはウィンドウモードのまま動作しますが UI は表示されず、自動でゲーム内フックに切り替わることもありません。理由はログに記録されます。"))
                        ManagerTextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")))
                        }) {
                            Text(language.tr("授权悬浮窗权限", "Grant overlay permission", "フローティング権限を許可"))
                        }
                    }
                }
            }
            Text(language.tr(
                    "进入雀魂后，游戏内模式点屏幕顶部把手展开胶囊，再点胶囊打开菜单：菜单里可直接开启或关闭无人值守。关闭无人值守不会停止本应用；前台通知仍保留。",
                    "In Majsoul, tap the handle at the top of the screen to expand the capsule, then tap the capsule to open the menu, where you can turn unattended mode on or off. Turning it off never stops this app; the foreground notification stays.",
                    "雀魂では画面上部のハンドルをタップしてカプセルを開き、さらにカプセルをタップするとメニューが開きます。メニューから放置モードを直接オン・オフできます。オフにしても本アプリは停止せず、常駐通知も残ります。"),
                style = MaterialTheme.typography.bodySmall)
            Text(language.tr(
                    "可直接进入雀魂，在游戏内开启助手；更新模块后请重启游戏。中途开启或丢失消息时，可能需要重新进入牌局恢复完整状态。",
                    "You can open Majsoul first and start the assistant from inside the game; restart the game after updating the module. If you start it mid-match or messages were lost, you may need to re-enter the match to restore the full state.",
                    "雀魂を先に開いて、ゲーム内からアシストを起動することもできます。モジュール更新後はゲームを再起動してください。途中で起動した場合やメッセージが欠落した場合は、対局に入り直して状態を復元する必要があります。"),
                style = MaterialTheme.typography.bodySmall)
            ManagerTextButton(onClick = {
                context.packageManager.getLaunchIntentForPackage(AiOverlayService.GAME)?.let(context::startActivity)
            }) { Text(language.tr("打开雀魂", "Open Majsoul", "雀魂を開く")) }
        }
        ManagerCard {
            Text(language.tr("如何理解这些指标", "How to read these numbers", "これらの数値の見方"),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(language.tr("和牌率仅在听牌时显示；未听牌时显示向听数和进张。放铳风险指数包含打点权重，越低越好，并非实际放铳概率。分析只使用己方手牌与公开牌面。", "Win rate is shown only in tenpai. Otherwise, you’ll see shanten and tile acceptance. The deal-in risk score also factors in hand value: lower is better, but it is not a deal-in probability. Analysis uses only your hand and visible tiles.", "和了率はテンパイ時のみ表示されます。テンパイ前はシャンテン数と受け入れを表示します。放銃リスク指数は打点も加味した目安で、低いほど安全ですが、実際の放銃率ではありません。分析には自分の手牌と公開情報のみを使います。"))
            Text(language.tr(
                    "使用 Akagi 的本地轻量模型，支持普通四麻和三麻。自检使用内置样例，窗口会明确标记“非当前牌局”。",
                    "Uses Akagi's lightweight local models for standard four-player and three-player games. The self-check runs on a built-in sample and the window is clearly marked as not being your current match.",
                    "Akagi の軽量ローカルモデルを使用し、通常の四人打ちと三人打ちに対応します。自己診断は内蔵サンプルで実行され、ウィンドウには「現在の対局ではない」ことが明示されます。"))
            ManagerTextButton(onClick = { showLicense = true }) {
                Text(language.tr("助手开源许可", "Open-source licenses", "オープンソースライセンス"))
            }
        }
    }
    if (showLicense) {
        val license = remember {
            listOf("licenses/Akagi-NOTICE.txt", "licenses/Akagi-LICENSE.txt", "licenses/Mortal-NOTICE.txt", "licenses/Mortal-LICENSE.txt").joinToString("\n\n") { name ->
                runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault(name)
            }
        }
        AlertDialog(onDismissRequest = { showLicense = false },
            title = { Text(language.tr("Akagi / Mortal 开源许可", "Akagi / Mortal licenses", "Akagi / Mortal のライセンス")) },
            text = { Text(license, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { showLicense = false }) {
                    Text(language.tr("关闭", "Close", "閉じる"))
                }
            })
    }
    if (showLanguage) {
        AlertDialog(
            onDismissRequest = { showLanguage = false },
            title = { Text(language.tr("语言设置", "Language", "言語設定")) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(language.tr(
                        "只影响本管理应用的界面。助手行为、服务开关、显示模式和游戏内菜单不受影响；切换后立即生效并自动保存。",
                        "Affects this manager app's UI only. Assistant behaviour, service switches, display mode and the in-game menu are unchanged. The choice applies immediately and is saved automatically.",
                        "この管理アプリの表示のみに影響します。アシストの動作、サービスのオン・オフ、表示モード、ゲーム内メニューは変わりません。切り替えるとすぐに反映され、自動保存されます。"))
                    Text(language.tr(
                        "默认“跟随系统”，按系统语言自动选择：日语 → 日文，中文 → 简体中文，其他语言 → 英文。也可以手动固定为某一种语言，固定后不再跟随系统变化。",
                        "The default is “Follow system”: Japanese → Japanese, Chinese → Simplified Chinese, anything else → English. You can also pin one language; a pinned language stops following the system.",
                        "初期値は「システムに従う」で、システム言語に応じて自動で選ばれます：日本語 → 日本語、中国語 → 簡体字中国語、その他 → 英語。特定の言語に固定することもでき、固定後はシステムの変更に従いません。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(language.tr("游戏内菜单、说明牌上的提示和部分引擎消息由游戏进程绘制，仍保留原文；这些文字不随本应用的界面语言变化，未知诊断信息也保持原文。",
                            "The in-game menu, the hints on the tiles and some engine messages are drawn by the game process and stay in their original language: they do not follow this app's interface language, and unknown diagnostics are left verbatim as well.",
                            "ゲーム内メニュー、牌に表示されるヒント、一部のエンジンメッセージはゲームプロセスが描画するため、元の言語のままです。本アプリの表示言語には従わず、不明な診断情報も原文のまま表示されます。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(language.tr("当前：${languageChoice.describe(language)}",
                            "Current: ${languageChoice.describe(language)}",
                            "現在：${languageChoice.describe(language)}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguage = false }) {
                    Text(language.tr("关闭", "Close", "閉じる"))
                }
            },
        )
    }
}

/**
 * The language switch at the very top of the assistant page.
 *
 * One full-width button opens a dropdown with the three languages: nothing wraps, nothing
 * scrolls sideways, and the row stays inside the card width at any font scale. The trailing
 * text button opens the short explanation dialog.
 */
@Composable
private fun LanguageBar(
    language: ManagerLanguage,
    choice: ManagerLanguageChoice,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (ManagerLanguageChoice) -> Unit,
    onShowDetails: () -> Unit,
) {
    ManagerCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(language.tr("界面语言", "Interface language", "表示言語"),
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            ManagerTextButton(
                onClick = onShowDetails,
                modifier = Modifier.widthIn(max = 140.dp),
            ) { Text(language.tr("说明", "Details", "詳細")) }
        }
        Box(Modifier.fillMaxWidth()) {
            ManagerOutlinedButton(onClick = { onExpandedChange(true) }) {
                // "跟随系统（简体中文）" when following, the language's own name otherwise.
                Text(choice.describe(language))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
                ManagerLanguageChoice.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.describe(language)) },
                        leadingIcon = { RadioButton(selected = option == choice, onClick = null) },
                        onClick = { onSelect(option) },
                    )
                }
            }
        }
        if (choice == ManagerLanguageChoice.SYSTEM) {
            Text(language.tr(
                    "当前跟随系统：日语 → 日文，中文 → 简体中文，其他语言 → 英文。系统语言变化时会自动切换。",
                    "Following the system: Japanese → Japanese, Chinese → Simplified Chinese, anything else → English. It switches as soon as the system language changes.",
                    "システムに従います：日本語 → 日本語、中国語 → 簡体字中国語、その他 → 英語。システム言語の変更にすぐ追従します。",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the manager's model status line can say, so translation never guesses. */
private enum class ModelMessageShape(val en: String, val ja: String) {
    IMPORTING("Copying and checking the model…", "モデルをコピーして確認中…"),
    IMPORT_FAILED("Model import failed; the built-in model stays in use.",
        "モデルの取り込みに失敗しました。内蔵モデルのまま続行します。"),
    UNAVAILABLE("The self-check could not run on this model.", "このモデルの自己診断を実行できませんでした。"),
}

/**
 * Translates the manager's model message at display time.
 *
 * Only messages this screen produced are mapped; tool and engine output keeps its original
 * wording, which is why an unknown string is returned unchanged.
 */
internal fun modelMessageText(raw: String, language: ManagerLanguage): String {
    if (language == ManagerLanguage.SIMPLIFIED) return raw
    val text = raw.trim()
    fun copy(en: String, ja: String) = language.tr("", en, ja)
    fun shape(value: ModelMessageShape) = copy(value.en, value.ja)

    when (text) {
        "正在复制并检查模型…" -> return shape(ModelMessageShape.IMPORTING)
        "模型导入失败，继续使用内置模型。" -> return shape(ModelMessageShape.IMPORT_FAILED)
        "无法启用模型" -> return copy("Cannot enable this model", "このモデルを有効にできません")
        "自检失败" -> return copy("Self-check failed", "自己診断に失敗しました")
    }

    Regex("^支持 Akagi ONNX").find(text)?.let {
        return copy(
            "Supports Akagi ONNX and the experimental four-/three-player Mortal v4 (ONNX/PTH/PT, " +
                "up to ${OnnxModelStore.MAX_MODEL_MIB} MiB per file). The player count is detected and saved to the " +
                "matching slot; a compatible custom model still needs unattended mode switched on by hand. " +
                "Models stay on this device.",
            "Akagi ONNX と試験的な四人打ち・三人打ち用 Mortal v4（ONNX/PTH/PT、1 ファイル上限 " +
                "${OnnxModelStore.MAX_MODEL_MIB} MiB）に対応します。人数は自動判別して対応する枠に保存します。" +
                "互換性のあるカスタムモデルは、手動で放置モードをオンにする必要があります。モデルは端末内にのみ保存されます。",
        )
    }

    Regex("^([0-9]+) 人模型已校验并暂存；下一次完整牌局同步时生效。$").matchEntire(text)?.let {
        val players = it.groupValues[1]
        return copy("$players-player model verified and staged; it applies at the next full game sync.",
            "$players 人用モデルを検証して保存しました。次回の対局状態の完全同期から適用されます。")
    }

    Regex("^([0-9]+)人 (.+) 加载与推理自检通过（实验性）· ([0-9]+) ms$").matchEntire(text)?.let {
        val (players, kind, ms) = it.destructured
        return copy("$players-player $kind loaded and passed the self-check (experimental) · $ms ms",
            "$players 人用 $kind の読み込みと推論の自己診断に成功しました（試験的）· $ms ms")
    }

    Regex("^([0-9]+)人 (.+) 模型可用 · ([0-9]+) ms$").matchEntire(text)?.let {
        val (players, kind, ms) = it.destructured
        return copy("$players-player $kind model is ready · $ms ms",
            "$players 人用 $kind モデルが使用できます · $ms ms")
    }

    Regex("^(四麻|三麻)(.+)$").matchEntire(text)?.let {
        val seatEn = if (it.groupValues[1] == "四麻") "Four-player" else "Three-player"
        val seatJa = if (it.groupValues[1] == "四麻") "四人打ち" else "三人打ち"
        when (it.groupValues[2]) {
            "已选择内置策略；当前牌局保持原模型，下一次完整牌局同步时生效。" ->
                return copy(
                    "$seatEn: built-in policy selected. The current match keeps its model; the change applies at the next full game sync.",
                    "$seatJa：内蔵ポリシーを選択しました。現在の対局は元のモデルのままで、次回の完全同期から適用されます。",
                )
            "自定义策略将在下一次完整牌局同步时生效。" ->
                return copy("$seatEn: the custom policy applies at the next full game sync.",
                    "$seatJa：カスタムポリシーは次回の対局状態の完全同期から適用されます。")
            "自定义模型已从可选槽位移除。" ->
                return copy("$seatEn: the custom model was removed from its slot.",
                    "$seatJa：カスタムモデルをスロットから削除しました。")
        }
    }

    // Engine or tool output (error messages with status codes, file checks, …): keep it.
    return raw
}

@Composable
private fun PolicyModelCard(
    players: Int,
    model: ImportedPolicy?,
    selectedCustom: Boolean,
    onImport: () -> Unit,
    onBuiltIn: () -> Unit,
    onCustom: () -> Unit,
    onDelete: () -> Unit,
    onSelfTest: () -> Unit,
) {
    val language = managerLanguage()
    val builtIn = language.tr("内置 Akagi", "built-in Akagi", "内蔵 Akagi")
    ManagerCard {
        Text(
            if (players == 4) language.tr("四麻模型", "Four-player model", "四人打ちモデル")
            else language.tr("三麻模型", "Three-player model", "三人打ちモデル"),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        )
        Text(
            language.tr(
                "当前：${if (selectedCustom && model != null) model.format else builtIn}",
                "Current: ${if (selectedCustom && model != null) model.format else builtIn}",
                "現在：${if (selectedCustom && model != null) model.format else builtIn}",
            ),
        )
        if (model != null) {
            Text("${model.name} · ${"%.1f".format(model.bytes / (1024f * 1024f))} MiB · SHA-256 ${model.sha256.take(12)}",
                style = MaterialTheme.typography.bodySmall)
            ManagerActions(
                first = {
                    ManagerOutlinedButton(onClick = onCustom, modifier = it) {
                        Text(
                            if (selectedCustom) language.tr("已选自定义", "Custom selected", "カスタム選択中")
                            else language.tr("使用自定义", "Use custom", "カスタムを使用"),
                        )
                    }
                },
                second = {
                    ManagerOutlinedButton(onClick = onBuiltIn, modifier = it) {
                        Text(language.tr("切换内置", "Use built-in", "内蔵に切り替え"))
                    }
                },
            )
            ManagerActions(
                first = {
                    ManagerOutlinedButton(onClick = onSelfTest, modifier = it) {
                        Text(language.tr("模型自检", "Model self-check", "モデル自己診断"))
                    }
                },
                second = {
                    ManagerOutlinedButton(onClick = onDelete, modifier = it) {
                        Text(language.tr("删除模型", "Delete model", "モデルを削除"))
                    }
                },
            )
        }
        ManagerButton(onClick = onImport) {
            Text(language.tr("导入 .onnx / .pth", "Import .onnx / .pth", ".onnx / .pth を読み込む"))
        }
    }
}
