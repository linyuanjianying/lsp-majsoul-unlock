package com.yefeng.majmax.hookprobe.manager

import com.yefeng.majmax.hookprobe.manager.icons.Article
import com.yefeng.majmax.hookprobe.manager.icons.Delete
import com.yefeng.majmax.hookprobe.manager.icons.Home
import com.yefeng.majmax.hookprobe.manager.icons.Icons
import com.yefeng.majmax.hookprobe.manager.icons.Info
import com.yefeng.majmax.hookprobe.manager.icons.Refresh
import com.yefeng.majmax.hookprobe.manager.icons.Share
import com.yefeng.majmax.hookprobe.manager.icons.SmartToy
import com.yefeng.majmax.hookprobe.manager.icons.SystemUpdate
import com.yefeng.majmax.hookprobe.manager.icons.Warning
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yefeng.majmax.hookprobe.BuildConfig
import com.yefeng.majmax.hookprobe.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val Green = Color(0xFF007A46)
private val Cream = Color(0xFFF7F7EF)

class MainActivity : ComponentActivity() {
    /** Export work for this activity instance; cancelled in [onDestroy]. */
    internal val exportScope: CoroutineScope = newExportScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticsAccess.grantToGame(this)
        // Before setContent, so the first frame is already in the stored language: the stored
        // option first, then the device locale behind it (only when the option is "follow system").
        ManagerLanguageState.load(this)
        ManagerLanguageState.syncSystemLanguage()
        DiagnosticsStore.cleanExpiredExports(this)
        CatalogUpdates.check(applicationContext)
        setContent { HookManagerApp(exportScope) }
    }

    /**
     * Coming back from the language settings is a system language change in practice; a
     * recreate also re-reads the stored option, in case it changed while the UI was away.
     */
    override fun onResume() {
        super.onResume()
        ManagerLanguageState.syncSystemLanguage()
    }

    /**
     * Reports device locale changes to the composition.
     *
     * The manager reads `Resources.getSystem()` rather than its own configuration (see
     * [ManagerSystemLanguage]), so a locale change does not otherwise invalidate anything and
     * a "follow system" choice would go unnoticed until the next start. Republishing the
     * effective language in [ManagerLanguageState] is what makes the open screens recompose.
     * The change is passed in directly instead of re-reading the system resources, which may
     * not have settled yet at this point.
     */
    @Suppress("DEPRECATION")
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val locale = if (newConfig.locales.size() > 0) newConfig.locales.get(0) else null
        ManagerLanguageState.syncSystemLanguage(ManagerSystemLanguage.resolve(locale))
    }

    override fun onDestroy() {
        super.onDestroy()
        // This scope captures UI state belonging to this activity instance.
        // Cancel on rotation too, so a destroyed activity cannot launch a late chooser.
        exportScope.cancel()
    }
}

/**
 * Bottom-bar destinations. The Chinese title is first by contract: it is what the manager
 * showed before the language switch existed, and [title] falls back to it for any language
 * that has no entry.
 */
private enum class Destination(val zh: String, val en: String, val ja: String) {
    Overview("概览", "Overview", "概要"),
    Assistant("助手", "Assistant", "アシスト"),
    Logs("日志", "Logs", "ログ"),
    Updates("更新", "Updates", "更新"),
    ;

    fun title(language: ManagerLanguage): String = language.tr(zh, en, ja)
}

private data class ModuleStatus(
    val diagnosticAvailable: Boolean,
    val lastEvent: String?,
)

private sealed interface UpdateState {
    data object NotConfigured : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val versionCode: Int, val versionName: String, val notes: String, val pageUrl: String,
        val downloadUrl: String = "", val bytes: Long = 0, val sha256: String = "") : UpdateState
    data class Error(val message: String) : UpdateState
}

private object ManagerDiagnostics {
    fun append(context: Context, level: String, source: String, message: String, code: String? = null) {
        val resolved = code ?: when {
            source == "diagnostics" -> "EXPORT_FAILED"
            message.contains("启动目标游戏") -> "GAME_LAUNCH_REQUESTED"
            message.contains("刷新诊断状态") -> "DIAGNOSTICS_REFRESH"
            message.contains("设置仍由游戏内页面管理") -> "GAME_SETTINGS_OPENED"
            else -> "MANAGER_ACTION"
        }
        DiagnosticsStore.appendAssistant(context, level, "manager", resolved,
            JSONObject().put("reason", source.take(24)))
    }

    fun read(context: Context): List<DiagnosticRecord> = DiagnosticsStore.read(context, DiagnosticKind.Assistant)
}

/**
 * Export work deliberately does not live in `rememberCoroutineScope()`.
 *
 * In Compose 1.7.x the remembered scope's job is cancelled as soon as its
 * composition leaves the tree, for example when the user switches tabs.
 * Keep packaging alive across tab/dialog disposal, but cancel when the activity
 * is destroyed. Opening a chooser alone does not dispose the composition.
 */
private fun newExportScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** What the export dialog should tell the user after a delivery attempt. */
private sealed interface ExportOutcome {
    data object Shared : ExportOutcome
    data class Saved(val name: String) : ExportOutcome
    data class Failed(val reason: String) : ExportOutcome
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HookManagerApp(exportScope: CoroutineScope) {
    val context = LocalContext.current
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var languageMenu by remember { mutableStateOf(false) }
    val language = managerLanguage()
    val languageChoice = managerLanguageChoice()
    val destination = Destination.entries[selectedIndex]
    val configured = BuildConfig.UPDATE_OWNER.isNotBlank() && BuildConfig.UPDATE_REPO.isNotBlank()
    var updateState by remember(configured) {
        mutableStateOf<UpdateState>(if (configured) UpdateState.Checking else UpdateState.NotConfigured)
    }
    val updateScope = rememberCoroutineScope()

    LaunchedEffect(configured) {
        if (configured) {
            updateState = UpdateState.Checking
            updateState = withContext(Dispatchers.IO) { checkForUpdate(context) }
        }
    }

    HookManagerTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(R.drawable.app_icon),
                                contentDescription = null,
                                modifier = Modifier.size(38.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                // The product name stays as-is in every language.
                                Text("雀魂 Max Hook", fontWeight = FontWeight.SemiBold)
                                Text(language.tr("独立模块管理", "Module manager", "モジュール管理"),
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    actions = {
                        Box {
                            // Short tag: the top bar is narrow on small screens, and the full
                            // names (including "follow system") are in the dropdown this opens.
                            TextButton(onClick = { languageMenu = true }) {
                                Text(topBarLanguageTag(languageChoice, language))
                            }
                            DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                                ManagerLanguageChoice.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.describe(language)) },
                                        leadingIcon = {
                                            RadioButton(selected = option == languageChoice, onClick = null)
                                        },
                                        onClick = {
                                            languageMenu = false
                                            // Stores the *choice*; the effective language is
                                            // re-derived (device locale or explicit) right here.
                                            ManagerLanguageState.select(context, option)
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { showAbout = true }) {
                            Icon(Icons.Default.Info,
                                contentDescription = language.tr("关于", "About", "このアプリについて"))
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Destination.entries.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = selectedIndex == index,
                            onClick = { selectedIndex = index },
                            icon = {
                                Icon(
                                    when (item) {
                                        Destination.Overview -> Icons.Default.Home
                                        Destination.Assistant -> Icons.Default.SmartToy
                                        Destination.Logs -> Icons.AutoMirrored.Filled.Article
                                        Destination.Updates -> Icons.Default.SystemUpdate
                                    },
                                    contentDescription = item.title(language),
                                )
                            },
                            label = {
                                Text(item.title(language), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            },
                        )
                    }
                }
            },
        ) { padding ->
            when (destination) {
                Destination.Overview -> OverviewScreen(padding)
                Destination.Assistant -> AiScreen(padding)
                Destination.Logs -> LogsScreen(padding, exportScope)
                Destination.Updates -> UpdatesScreen(
                    padding = padding,
                    updateState = updateState,
                    onCheck = {
                        updateState = UpdateState.Checking
                        updateScope.launch {
                            updateState = withContext(Dispatchers.IO) { checkForUpdate(context) }
                        }
                    },
                )
            }
        }
        if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val language = managerLanguage()
    var showLicense by remember { mutableStateOf(false) }
    val versionText = "雀魂 Max Hook ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
        "${BuildConfig.UPDATE_CHANNEL} · ${BuildConfig.BUILD_SHA} · ${BuildConfig.APPLICATION_ID}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(language.tr("关于雀魂 Max Hook", "About Majsoul Max Hook", "雀魂 Max Hook について")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoRow(language.tr("应用", "App", "アプリ"), "雀魂 Max Hook")
                InfoRow(language.tr("版本", "Version", "バージョン"),
                    "${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}")
                InfoRow(language.tr("发布通道", "Channel", "リリースチャンネル"), BuildConfig.UPDATE_CHANNEL)
                InfoRow(language.tr("构建提交", "Build commit", "ビルドコミット"), BuildConfig.BUILD_SHA)
                InfoRow(language.tr("包名", "Package", "パッケージ名"), BuildConfig.APPLICATION_ID)
                InfoRow(language.tr("开发者", "Developer", "開発者"), "YeFeng233")
                ManagerOutlinedButton(onClick = { openUrl(context, "https://github.com/YeFeng233/lsp-majsoul-unlock") }) {
                    Text(language.tr("项目地址 · GitHub", "Project on GitHub", "プロジェクト · GitHub"))
                }
                ManagerOutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(
                        language.tr("版本信息", "Version info", "バージョン情報"), versionText))
                }) { Text(language.tr("复制版本信息", "Copy version info", "バージョン情報をコピー")) }
                ManagerOutlinedButton(onClick = { showLicense = true }) {
                    Text(language.tr("开源许可", "Open-source licenses", "オープンソースライセンス"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(language.tr("关闭", "Close", "閉じる")) }
        },
    )
    if (showLicense) {
        val license = remember {
            listOf("licenses/Akagi-NOTICE.txt", "licenses/Akagi-LICENSE.txt").joinToString("\n\n") { name ->
                runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault(name)
            }
        }
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text(language.tr("Akagi 开源许可", "Akagi licenses", "Akagi ライセンス")) },
            text = { Text(license, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { showLicense = false }) { Text(language.tr("关闭", "Close", "閉じる")) }
            },
        )
    }
}

@Composable
private fun OverviewScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val language = managerLanguage()
    var status by remember { mutableStateOf(readStatus(context)) }
    ManagerPage(padding) {
        Text(language.tr("概览", "Overview", "概要"),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        StatusCard(status) { status = readStatus(context) }
        ManagerCard {
            Text(language.tr("当前安装", "Installed now", "現在のインストール"),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            InfoRow(language.tr("模块版本", "Module version", "モジュールのバージョン"),
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            // Package name: a machine identifier, never translated.
            InfoRow(language.tr("目标游戏", "Target game", "対象ゲーム"), "com.soulgamechst.majsoul")
            InfoRow(
                language.tr("更新通道", "Update channel", "更新チャンネル"),
                if (BuildConfig.UPDATE_OWNER.isBlank()) {
                    language.tr("尚未配置更新仓库", "No update repository configured",
                        "更新リポジトリは未設定です")
                } else BuildConfig.UPDATE_CHANNEL,
            )
        }
        ManagerCard(
            onClick = { ManagerDiagnostics.append(context, "INFO", "ui", "MOD 设置仍由游戏内页面管理") },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(language.tr("MOD 选项在游戏内设置中调整",
                        "MOD options live in the in-game settings",
                        "MOD の設定はゲーム内で行います"), fontWeight = FontWeight.Medium)
                    Text(language.tr("打开雀魂后进入 设置 → MOD设置",
                            "Open Majsoul, then Settings → MOD settings",
                            "雀魂を開き、設定 → MOD 設定 の順に進みます"),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: ModuleStatus, onRefresh: () -> Unit) {
    val context = LocalContext.current
    // Subscribes this card so an open screen re-translates on a language change.
    val language = managerLanguage()
    val installed = runCatching {
        context.packageManager.getLaunchIntentForPackage("com.soulgamechst.majsoul") != null
    }.getOrDefault(false)
    ManagerCard(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B6B4A), contentColor = Color.White)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (status.diagnosticAvailable) Icons.Default.Info else Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFF2C46B),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (status.diagnosticAvailable) language.tr("已有最近诊断记录",
                        "Recent diagnostic records found", "最近の診断記録があります")
                    else language.tr("尚未读取游戏运行记录", "No game run records yet",
                        "ゲーム実行の記録はまだありません"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    // A stored diagnostic line: translated only when its shape is known,
                    // otherwise shown as produced.
                    status.lastEvent?.let { localizedKnown(it) }
                        ?: language.tr("启动游戏并在 LSPosed 中启用模块后可产生记录",
                            "Start the game with the module enabled in LSPosed to produce records",
                            "LSPosed でモジュールを有効にしてゲームを起動すると記録が残ります"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFE8F5EE),
                )
            }
        }
        HorizontalDivider()
        InfoRow(language.tr("目标游戏", "Target game", "対象ゲーム"),
            if (installed) language.tr("已安装", "Installed", "インストール済み")
            else language.tr("未检测到", "Not found", "見つかりません"), labelColor = Color(0xFFE1F2E9))
        ManagerDisclosure(language.tr("日志来源", "Log source", "ログの取得元"), labelColor = Color(0xFFFFDF98)) {
        InfoRow(language.tr("日志来源", "Log source", "ログの取得元"),
            language.tr("Hook 事件通过游戏 UID 校验通道提交；不读取全局 logcat",
                "Hook events arrive over a UID-checked channel; global logcat is never read",
                "Hook イベントはゲーム UID を検証した経路で届きます。全体の logcat は読みません"), labelColor = Color(0xFFE1F2E9))
        }
        ManagerActions(first = { modifier ->
            androidx.compose.material3.Button(modifier = modifier, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFF2C46B), contentColor = Color(0xFF3C331B)), onClick = {
                ManagerDiagnostics.append(context, "INFO", "ui", "启动目标游戏")
                context.packageManager.getLaunchIntentForPackage("com.soulgamechst.majsoul")?.let { context.startActivity(it) }
            }, enabled = installed) { Text(language.tr("打开雀魂", "Open Majsoul", "雀魂を開く")) }
        }, second = { modifier ->
            androidx.compose.material3.OutlinedButton(modifier = modifier, colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White), onClick = {
                ManagerDiagnostics.append(context, "INFO", "ui", "刷新诊断状态")
                onRefresh()
            }) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(language.tr("刷新诊断", "Refresh", "診断を更新"))
            }
        })

    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LogsScreen(padding: PaddingValues, exportScope: CoroutineScope) {
    val context = LocalContext.current
    // Subscribes this screen (and its dialogs) to the language.
    val language = managerLanguage()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var hookLines by remember { mutableStateOf(DiagnosticsStore.read(context, DiagnosticKind.Hook)) }
    var assistantLines by remember { mutableStateOf(ManagerDiagnostics.read(context)) }
    var hookQuery by rememberSaveable { mutableStateOf("") }
    var assistantQuery by rememberSaveable { mutableStateOf("") }
    // Level filters store a stable key ("ALL"/"DEBUG"/…), never the label: the label is
    // translated, and rememberSaveable keeps this value across a language switch.
    var hookLevel by rememberSaveable { mutableStateOf(LOG_LEVEL_ALL) }
    var assistantLevel by rememberSaveable { mutableStateOf(LOG_LEVEL_ALL) }
    var followLatest by rememberSaveable { mutableStateOf(true) }
    val hookScroll = rememberLazyListState()
    val assistantScroll = rememberLazyListState()
    var showExport by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var clearAll by remember { mutableStateOf(false) }
    var clearBusy by remember { mutableStateOf(false) }
    var clearError by remember { mutableStateOf<String?>(null) }
    val clearScope = rememberCoroutineScope()
    val lines = if (selectedTab == 0) hookLines else assistantLines
    val query = if (selectedTab == 0) hookQuery else assistantQuery
    val level = if (selectedTab == 0) hookLevel else assistantLevel
    val visible = lines.filter { log ->
        (level == LOG_LEVEL_ALL || log.level == level) &&
            (query.isBlank() || "${log.component} ${log.code} ${log.fields}".contains(query, ignoreCase = true))
    }
    val activeScroll = if (selectedTab == 0) hookScroll else assistantScroll
    LaunchedEffect(selectedTab, visible.size, followLatest) {
        if (followLatest && visible.isNotEmpty()) activeScroll.animateScrollToItem(visible.lastIndex)
    }
    fun refresh() {
        hookLines = DiagnosticsStore.read(context, DiagnosticKind.Hook)
        assistantLines = ManagerDiagnostics.read(context)
    }
    ManagerContent(padding) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = ManagerLayout.pagePadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(language.tr("日志", "Logs", "ログ"), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        clearAll = false
                        clearError = null
                        showClear = true
                    }, enabled = hookLines.isNotEmpty() || assistantLines.isNotEmpty()) {
                        Icon(Icons.Default.Delete, contentDescription = language.tr("清理日志", "Clear logs", "ログを消去"))
                    }
                    IconButton(onClick = ::refresh) {
                        Icon(Icons.Default.Refresh, language.tr("刷新", "Refresh", "更新"))
                    }
                    IconButton(onClick = { showExport = true }) {
                        Icon(Icons.Default.Share, language.tr("打包发送", "Package and send", "まとめて送信"))
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    logTabs(language).forEachIndexed { index, label ->
                        FilterChip(selected = selectedTab == index, onClick = { selectedTab = index }, label = { Text(label) })
                    }
                    FilterChip(selected = followLatest, onClick = { followLatest = !followLatest },
                        label = { Text(language.tr("跟随最新", "Follow latest", "最新に追従")) })
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { if (selectedTab == 0) hookQuery = it else assistantQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    // Filters the manager's own records; the record text itself is untouched.
                    label = { Text(language.tr("搜索消息或事件", "Search messages or events",
                        "メッセージやイベントを検索")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Level names are machine fields written by the service; only "all" translates.
                    logLevelFilters(language).forEach { (key, label) ->
                        FilterChip(selected = key == (if (selectedTab == 0) hookLevel else assistantLevel),
                            onClick = { if (selectedTab == 0) hookLevel = key else assistantLevel = key },
                            label = { Text(label) })
                    }
                }
                Text(
                    if (lines.isEmpty()) {
                        if (selectedTab == 0) language.tr(
                            "尚未收到游戏 Hook 事件；请确认 LSPosed 已启用模块并重启游戏",
                            "No hook events received yet. Check that the module is enabled in LSPosed, then restart the game.",
                            "ゲームの Hook イベントはまだ届いていません。LSPosed でモジュールを有効にしてゲームを再起動してください。")
                        else language.tr(
                            "助手尚未运行，或还没有产生诊断事件",
                            "The assistant has not run yet, or has produced no diagnostic events.",
                            "アシストはまだ実行されていないか、診断イベントがありません。")
                    } else language.tr(
                        "已载入 ${visible.size} / ${lines.size} 条",
                        "Showing ${visible.size} of ${lines.size}",
                        "${lines.size} 件中 ${visible.size} 件を表示")
                    ,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (visible.isEmpty()) {
                EmptyState(
                    language.tr("暂无匹配日志", "No matching entries", "一致するログはありません"),
                    if (lines.isEmpty()) language.tr(
                        "可刷新页面，或在游戏/助手运行后再次查看。",
                        "Refresh the page, or look again once the game or the assistant has run.",
                        "ページを更新するか、ゲームやアシストの実行後に再度ご確認ください。")
                    else language.tr(
                        "调整搜索内容或级别筛选。",
                        "Adjust the search text or the level filter.",
                        "検索語やレベル絞り込みを変更してください。"),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = activeScroll,
                    contentPadding = PaddingValues(horizontal = ManagerLayout.pagePadding, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(ManagerLayout.contentGap),
                ) {
                    items(visible) { log ->
                        var expanded by rememberSaveable(log.session, log.seq, log.code) { mutableStateOf(false) }
                        ManagerCard(onClick = { expanded = !expanded }) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(log.displayTime, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                ManagerBadge(log.level, warning = log.level == "WARN")
                                Text(log.component, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            // The record itself (summary, code, session, fields) is service or
                            // game data and stays in the language it was written in. Only the
                            // labels around it are translated.
                            Text(log.summary)
                            if (expanded) {
                                Text(
                                    language.tr(
                                        "事件：${log.code} · 会话：${log.session} · 序号：${log.seq}",
                                        "Event: ${log.code} · Session: ${log.session} · Seq: ${log.seq}",
                                        "イベント: ${log.code} · セッション: ${log.session} · 連番: ${log.seq}",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (log.fields.length() > 0) {
                                    Text(
                                        language.tr("诊断字段：${log.fields}", "Fields: ${log.fields}",
                                            "診断フィールド: ${log.fields}"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                Text(language.tr("点按查看诊断详情", "Tap to see details",
                                        "タップで診断の詳細を表示"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showClear) {
        val currentKind = if (selectedTab == 0) DiagnosticKind.Hook else DiagnosticKind.Assistant
        val currentLabel = logTabs(language)[selectedTab]
        AlertDialog(
            onDismissRequest = { if (!clearBusy) showClear = false },
            title = { Text(language.tr("清理日志", "Clear logs", "ログを消去")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(selected = !clearAll, onClick = { clearAll = false }, enabled = !clearBusy,
                        modifier = Modifier.fillMaxWidth(), label = {
                            Text(language.tr("当前分类：$currentLabel（${lines.size} 条）",
                                "This category: $currentLabel (${lines.size})",
                                "この分類: $currentLabel（${lines.size} 件）"))
                        })
                    FilterChip(selected = clearAll, onClick = { clearAll = true }, enabled = !clearBusy,
                        modifier = Modifier.fillMaxWidth(), label = {
                            Text(language.tr("全部日志（${hookLines.size + assistantLines.size} 条）",
                                "All logs (${hookLines.size + assistantLines.size})",
                                "すべてのログ（${hookLines.size + assistantLines.size} 件）"))
                        })
                    Text(
                        language.tr(
                            "将删除所选范围的全部历史日志，包括被搜索或级别筛选隐藏的记录。清理后无法恢复，新日志仍会正常记录。",
                            "Every historical entry in the selected scope is deleted, including those hidden by the search or the level filter. This cannot be undone; new entries are still recorded normally.",
                            "選択した範囲の過去ログをすべて削除します。検索やレベル絞り込みで非表示の記録も対象です。消去は元に戻せませんが、新しいログはこれまでどおり記録されます。",
                        ))
                    clearError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !clearBusy, onClick = {
                    val kind = if (clearAll) null else currentKind
                    clearBusy = true
                    clearError = null
                    clearScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                DiagnosticsStore.clear(context, kind)
                                DiagnosticsStore.read(context, DiagnosticKind.Hook) to
                                    DiagnosticsStore.read(context, DiagnosticKind.Assistant)
                            }
                        }.onSuccess { (hook, assistant) ->
                            hookLines = hook
                            assistantLines = assistant
                            showClear = false
                        }.onFailure {
                            clearError = language.tr("清理失败，请重试。", "Clearing failed. Try again.",
                                "消去に失敗しました。もう一度お試しください。")
                        }
                        clearBusy = false
                    }
                }) {
                    Text(
                        if (clearBusy) language.tr("正在清理…", "Clearing…", "消去中…")
                        else language.tr("确认清理", "Clear", "消去する"),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !clearBusy, onClick = { showClear = false }) {
                    Text(language.tr("取消", "Cancel", "キャンセル"))
                }
            },
        )
    }
    // Export state lives outside the dialog's composition: see the note on exportBusy.
    var exportBusy by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var exportOutcome by remember { mutableStateOf<ExportOutcome?>(null) }
    if (showExport) {
        var range by remember { mutableIntStateOf(0) }
        val options = listOf(
            language.tr("最近 1 小时", "Last hour", "直近 1 時間"),
            language.tr("最近 24 小时", "Last 24 hours", "直近 24 時間"),
            language.tr("全部保留日志", "All retained logs", "保存されているすべてのログ"),
        )
        val durations = listOf(60 * 60 * 1000L, 24 * 60 * 60 * 1000L, null)
        val previewRange = durations[range]
        val now = System.currentTimeMillis()
        val hookExport = hookLines.filter { previewRange == null || now - it.timeUtcMs <= previewRange }
        val assistantExport = assistantLines.filter { previewRange == null || now - it.timeUtcMs <= previewRange }
        val estimateBytes = DiagnosticsStore.estimateBytes(hookExport, assistantExport)
        val estimateLabel = if (estimateBytes < 1024 * 1024) "${estimateBytes / 1024} KiB"
            else "${"%.1f".format(estimateBytes / (1024f * 1024f))} MiB"
        // Never package an archive that has no destination: on a device with no
        // application/zip handler the old flow silently produced a file and then
        // failed at startActivity, which the user only saw as "分享没反应".
        val shareAvailable = remember { DiagnosticsStore.canShare(context) }
        val appContext = context.applicationContext
        AlertDialog(
            onDismissRequest = { if (!exportBusy) { showExport = false; exportOutcome = null } },
            title = { Text(language.tr("打包发送", "Package and send", "まとめて送信")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEachIndexed { index, label ->
                        FilterChip(selected = range == index, onClick = { range = index },
                            enabled = !exportBusy, label = { Text(label) })
                    }
                    Text(
                        language.tr(
                            "故障包包含 Hook 日志 ${hookExport.size} 条、助手日志 ${assistantExport.size} 条，与当前搜索筛选无关。",
                            "The package contains ${hookExport.size} hook entries and ${assistantExport.size} assistant entries; the current search filter does not apply.",
                            "パッケージには Hook ログ ${hookExport.size} 件、アシストログ ${assistantExport.size} 件が含まれます（現在の検索条件は適用されません）。",
                        ))
                    Text(
                        language.tr(
                            "预计未压缩约 $estimateLabel；分享前会再次执行字段白名单检查，文件上限 8 MiB。",
                            "About $estimateLabel before compression; the field allowlist is checked again before sharing, and the file is capped at 8 MiB.",
                            "非圧縮で約 $estimateLabel。共有前にフィールドの許可リストを再確認し、ファイルは 8 MiB までです。",
                        ),
                        style = MaterialTheme.typography.bodySmall)
                    if (!shareAvailable) {
                        Text(
                            language.tr(
                                "当前系统没有可接收 ZIP 的分享目标，将改为保存到系统“下载”目录。",
                                "This system has no share target for ZIP files, so the package will be saved to the system Downloads folder instead.",
                                "ZIP を受け取れる共有先がないため、システムの「ダウンロード」フォルダに保存します。",
                            ),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    exportOutcome?.let { outcome ->
                        Text(
                            when (outcome) {
                                ExportOutcome.Shared -> language.tr(
                                    "已打开分享面板，请选择要发送的应用。",
                                    "The share sheet is open; choose the app to send it with.",
                                    "共有画面を開きました。送信先のアプリを選んでください。")
                                is ExportOutcome.Saved -> language.tr(
                                    "已保存到“下载”：${outcome.name}。可自行转发给开发者。",
                                    "Saved to Downloads: ${outcome.name}. You can forward it to the developer yourself.",
                                    "「ダウンロード」に保存しました: ${outcome.name}。開発者へご自身で転送できます。")
                                is ExportOutcome.Failed -> language.tr(
                                    "打包或发送失败：${localizedKnown(outcome.reason, language)}",
                                    "Packaging or sending failed: ${localizedKnown(outcome.reason, language)}",
                                    "パッケージ作成または送信に失敗しました: ${localizedKnown(outcome.reason, language)}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !exportBusy, onClick = {
                    exportBusy = true
                    exportError = null
                    exportOutcome = null
                    ManagerDiagnostics.append(context, "INFO", "diagnostics", "创建故障包", "EXPORT_STARTED")
                    // exportScope, not rememberCoroutineScope: see the note above the
                    // scope. The lambda captures only stable values / appContext.
                    exportScope.launch {
                        // The busy flag must be cleared on every exit path, including
                        // cancellation by onDestroy; otherwise the button stays disabled
                        // for the rest of the activity's life.
                        try {
                            val file = withContext(Dispatchers.IO) {
                                DiagnosticsStore.packageZip(appContext, durations[range])
                            }
                            deliverExport(appContext, file) { outcome -> exportOutcome = outcome }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            val reason = error.message?.take(120) ?: error.javaClass.simpleName
                            exportError = language.tr(
                                "打包失败：${localizedKnown(reason, language)}",
                                "Packaging failed: ${localizedKnown(reason, language)}",
                                "パッケージ作成に失敗しました: ${localizedKnown(reason, language)}",
                            )
                            ManagerDiagnostics.append(appContext, "ERROR", "diagnostics",
                                "故障包生成失败", "EXPORT_FAILED")
                        } finally {
                            exportBusy = false
                        }
                    }
                }) {
                    Text(
                        if (exportBusy) language.tr("正在打包…", "Packaging…", "パッケージ作成中…")
                        else if (shareAvailable) language.tr("创建并分享", "Create and share", "作成して共有")
                        else language.tr("创建并保存", "Create and save", "作成して保存"),
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !exportBusy, onClick = { showExport = false; exportOutcome = null }) {
                    Text(
                        if (exportOutcome == null) language.tr("取消", "Cancel", "キャンセル")
                        else language.tr("关闭", "Close", "閉じる"),
                    )
                }
            },
        )
    }
}

/**
 * Hands [file] to the share sheet, falling back to a public Downloads copy when no
 * chooser can be started (no ZIP handler, share restricted by policy, or the
 * platform refuses the activity). Every branch appends an EXPORT_* event so the
 * next package explains itself, and the callback is invoked on the main thread.
 */
private suspend fun deliverExport(
    appContext: Context,
    file: File,
    report: (ExportOutcome) -> Unit,
) {
    val name = file.name
    // Build and *verify* the share intent off the main thread: diagnostics/ and
    // chooser-adjacent work here is I/O plus a ContentResolver round trip, and the
    // check is what decides between sharing and the Downloads fallback. Opening the
    // chooser is deliberately not treated as proof that the file can be read — the
    // receiving app is the one that would fail, which is the reported symptom.
    val prepared = if (!DiagnosticsStore.canShare(appContext)) {
        null to "no share target"
    } else {
        withContext(Dispatchers.IO) {
            runCatching { DiagnosticsStore.shareIntent(appContext, file) }
        }.fold({ it to null }, { null to (it.message ?: it.javaClass.simpleName) })
    }
    val chooser = prepared.first?.let { intent ->
        runCatching {
            // The label the share sheet shows; read non-composably, which is why it goes
            // through the language state directly rather than through managerLanguage().
            appContext.startActivity(
                Intent.createChooser(intent, ManagerLanguageState.current.tr(
                    "发送故障排查包", "Send diagnostic package", "診断パッケージを送信"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    } ?: false
    if (chooser) {
        ManagerDiagnostics.append(appContext, "INFO", "diagnostics", "打开分享面板", "EXPORT_SHARED")
        report(ExportOutcome.Shared)
        return
    }
    ManagerDiagnostics.append(appContext, "ERROR", "diagnostics", "分享组件无法提供故障包",
        "EXPORT_URI_UNREADABLE")
    val saved = withContext(Dispatchers.IO) { DiagnosticsStore.saveToDownloads(appContext, file, name) }
    if (saved != null) {
        ManagerDiagnostics.append(appContext, "INFO", "diagnostics", "保存到下载目录", "EXPORT_SAVED")
        report(ExportOutcome.Saved(name))
    } else {
        ManagerDiagnostics.append(appContext, "ERROR", "diagnostics", "无法交付故障包", "EXPORT_INTENT_UNAVAILABLE")
        report(ExportOutcome.Failed(prepared.second ?: ManagerLanguageState.current.tr(
            "系统无法打开分享面板，也没能写入下载目录",
            "The system could not open the share sheet, and the Downloads copy failed too.",
            "共有画面を開けず、「ダウンロード」への書き込みにも失敗しました。")))
    }
}

@Composable
private fun UpdatesScreen(
    padding: PaddingValues,
    updateState: UpdateState,
    onCheck: () -> Unit,
) {
    val context = LocalContext.current
    val language = managerLanguage()
    val configured = BuildConfig.UPDATE_OWNER.isNotBlank() && BuildConfig.UPDATE_REPO.isNotBlank()
    ManagerPage(padding) {
        Text(language.tr("更新", "Updates", "更新"),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        // Shared cards: their own text stays as it is for now (owned by another component).
        ManagerCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.app_icon), contentDescription = null, Modifier.size(52.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("雀魂 Max Hook", fontWeight = FontWeight.SemiBold)
                    Text("${BuildConfig.VERSION_NAME} · ${BuildConfig.UPDATE_CHANNEL}", style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
            if (configured) {
                Text(updateState.label(language), color = MaterialTheme.colorScheme.primary)
                ManagerButton(
                    enabled = updateState !is UpdateState.Checking,
                    onClick = onCheck,
                ) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (updateState is UpdateState.Checking) language.tr("检查中…", "Checking…", "確認中…")
                        else language.tr("检查更新", "Check for updates", "更新を確認"),
                    )
                }
                when (val state = updateState) {
                    is UpdateState.Available -> {
                        Text(
                            language.tr("发现新版本 ${state.versionName}",
                                "New version ${state.versionName} is available",
                                "新しいバージョン ${state.versionName} があります"),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        )
                        // Release notes are written on GitHub, in whatever language the author
                        // used: only the label around them is translated.
                        if (state.notes.isNotBlank()) Text(state.notes.take(500), maxLines = 6)
                        if (state.downloadUrl.isNotBlank()) ApkUpdateActions(
                            ApkRelease(state.versionCode, state.downloadUrl, state.bytes, state.sha256))
                        ManagerOutlinedButton(onClick = { openUrl(context, state.pageUrl) }) {
                            Text(language.tr("打开发布页面", "Open the release page", "リリースページを開く"))
                        }
                    }
                    // The message explains a check that failed; translated only when the
                    // manager wrote it itself, otherwise (network errors, HTTP status) verbatim.
                    is UpdateState.Error -> androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
                        Text(localizedUpdateFailure(state.message, language), Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    else -> Unit
                }
            } else {
                Text(language.tr("尚未配置更新仓库", "No update repository configured",
                        "更新リポジトリは未設定です"),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(language.tr("当前构建没有配置 GitHub Releases 更新源。",
                        "This build has no GitHub Releases update source configured.",
                        "このビルドには GitHub Releases の更新元が設定されていません。"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        UnlockCatalogCard()
        GitHubConnectionCard()
        Text(language.tr("安装新模块后需重启游戏，才能载入新的 Hook 版本。",
                "After installing the new module, restart the game to load the new hook version.",
                "新しいモジュールをインストールした後、ゲームを再起動すると新しい Hook が読み込まれます。"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The one-line summary above the update button. Only the manager's own wording is translated;
 * [UpdateState.Error.message] is shown elsewhere and is left to [localizedKnown].
 */
private fun UpdateState.label(language: ManagerLanguage): String = when (this) {
    UpdateState.NotConfigured -> language.tr("尚未配置更新仓库", "No update repository configured",
        "更新リポジトリは未設定です")
    UpdateState.Idle -> language.tr("更新源已配置，可以检查新版本。",
        "The update source is configured; you can check for a new version.",
        "更新元は設定済みです。新しいバージョンを確認できます。")
    UpdateState.Checking -> language.tr("正在检查新仓库…", "Checking the repository…",
        "リポジトリを確認中…")
    UpdateState.UpToDate -> language.tr("当前已是最新稳定版。", "You are on the latest stable version.",
        "最新の安定版を使用中です。")
    is UpdateState.Available -> language.tr("发现可用更新。", "An update is available.",
        "利用可能な更新があります。")
    is UpdateState.Error -> language.tr("检查失败。", "The check failed.", "確認に失敗しました。")
}

private fun checkForUpdate(context: Context): UpdateState {
    val owner = BuildConfig.UPDATE_OWNER.trim()
    val repo = BuildConfig.UPDATE_REPO.trim()
    if (owner.isBlank() || repo.isBlank()) return UpdateState.NotConfigured
    val validPart = Regex("[A-Za-z0-9_.-]+")
    // Manager-owned Chinese source text; translated at render time by localizedKnown().
    if (!validPart.matches(owner) || !validPart.matches(repo)) return UpdateState.Error("更新源配置无效。")
    val endpoint = "https://api.github.com/repos/$owner/$repo/releases?per_page=100"
    return runCatching {
        val releases = GitHubConnection.requestJson(context, endpoint, "application/vnd.github+json")
        val array = JSONArray(releases)
        var sawRelease = false
        var sawMetadata = false
        var best: UpdateState.Available? = null
        for (index in 0 until array.length()) {
            val release = array.optJSONObject(index) ?: continue
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val tag = release.optString("tag_name")
            if (!tag.startsWith("v")) continue
            sawRelease = true
            val assets = release.optJSONArray("assets") ?: continue
            var metadataUrl: String? = null
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                if (asset.optString("name") == "hook-update.json") {
                    metadataUrl = asset.optString("browser_download_url").takeIf(String::isNotBlank)
                    break
                }
            }
            val metadata = metadataUrl?.takeIf { isAllowedGitHubUrl(it) && it.startsWith("https://github.com/$owner/$repo/releases/download/") }
                ?.let { GitHubConnection.requestJson(context, it, "application/octet-stream") } ?: continue
            sawMetadata = true
            val info = JSONObject(metadata)
            if (info.optString("applicationId") != BuildConfig.APPLICATION_ID) continue
            if (info.optString("channel", BuildConfig.UPDATE_CHANNEL) != BuildConfig.UPDATE_CHANNEL) continue
            if (info.optInt("versionCode", -1) <= BuildConfig.VERSION_CODE) continue
            if (!release.optString("html_url").startsWith("https://github.com/$owner/$repo/releases/tag/")) continue
            val primaryAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull()
            val variants = info.optJSONArray("variants") ?: JSONArray()
            val names = (0 until variants.length()).mapNotNull { variants.optJSONObject(it) }
                .filter { variant -> (0 until (variant.optJSONArray("abis")?.length() ?: 0)).any { variant.getJSONArray("abis").optString(it) == primaryAbi } }
                .sortedBy { it.getJSONArray("abis").length() }.map { it.optString("assetName") }
            val selectedName = names.firstOrNull() ?: info.optString("assetName")
            val asset = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name") == selectedName }
            val download = asset?.optString("browser_download_url").orEmpty()
            val candidate = UpdateState.Available(
                versionCode = info.optInt("versionCode", -1),
                versionName = info.optString("versionName", tag.removePrefix("v")),
                notes = release.optString("body"),
                pageUrl = release.optString("html_url"),
                downloadUrl = download.takeIf { isAllowedGitHubUrl(it) && it.startsWith("https://github.com/$owner/$repo/releases/download/") } ?: "",
                bytes = asset?.optLong("size", 0)?.coerceAtLeast(0) ?: 0,
                sha256 = asset?.optString("digest")?.removePrefix("sha256:")
                    ?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }.orEmpty(),
            )
            if (best == null || candidate.versionCode > best!!.versionCode) best = candidate
        }
        best ?: when {
            !sawRelease -> UpdateState.UpToDate
            !sawMetadata -> UpdateState.Error("发布中没有符合协议的 hook-update.json。")
            else -> UpdateState.UpToDate
        }
    }.getOrElse { error ->
        UpdateState.Error(error.message?.take(160) ?: "无法连接更新源。")
    }
}

private fun openUrl(context: Context, url: String) {
    if (!url.startsWith("https://github.com/")) return
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}

private fun isAllowedGitHubUrl(value: String): Boolean {
    return GitHubConnection.isOrigin(value)
}

@Composable
private fun EmptyState(title: String, message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = labelColor)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HookManagerTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Color(0xFF0B6B4A), onPrimary = Color.White,
        primaryContainer = Color(0xFFDDEFE5), onPrimaryContainer = Color(0xFF0B6B4A),
        secondary = Color(0xFF0B6B4A), secondaryContainer = Color(0xFFDDEFE5),
        onSecondaryContainer = Color(0xFF0B6B4A),
        tertiary = Color(0xFF926515), tertiaryContainer = Color(0xFFFBECC9),
        background = Color(0xFFF4F1E8), onBackground = Color(0xFF1D2B27),
        surface = Color.White, onSurface = Color(0xFF1D2B27),
        surfaceVariant = Color(0xFFE9E5D7), onSurfaceVariant = Color(0xFF4E6058),
        outline = Color(0xFFB8B9AB), outlineVariant = Color(0xFFE4E0D4),
        error = Color(0xFFAE2924), errorContainer = Color(0xFFFBE0DD), onErrorContainer = Color(0xFF80211D))
    val base = androidx.compose.material3.Typography()
    val typography = androidx.compose.material3.Typography(
        bodyLarge = base.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Default, fontSize = 14.sp, lineHeight = 21.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Default, fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = base.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Default, fontSize = 12.sp, lineHeight = 18.sp),
        titleMedium = base.titleMedium.copy(fontSize = 17.sp),
        headlineSmall = base.headlineSmall.copy(fontSize = 23.sp))
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

private fun readStatus(context: Context): ModuleStatus {
    val last = ManagerDiagnostics.read(context).lastOrNull()
    return ModuleStatus(last != null, last?.let { "${it.displayTime} · ${it.summary}" })
}
