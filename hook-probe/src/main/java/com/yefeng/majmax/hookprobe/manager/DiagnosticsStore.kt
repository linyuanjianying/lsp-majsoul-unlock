package com.yefeng.majmax.hookprobe.manager

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.yefeng.majmax.hookprobe.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class DiagnosticRecord(
    val timeUtcMs: Long,
    val session: String,
    val seq: Long,
    val level: String,
    val component: String,
    val code: String,
    val fields: JSONObject = JSONObject(),
) {
    val displayTime: String get() = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
        .format(Date(timeUtcMs))
    val summary: String get() = code.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
}

internal enum class DiagnosticKind(val filename: String) { Hook("hook"), Assistant("assistant") }

/** Private, bounded JSONL stores. The export path always passes through the same allow-list. */
internal object DiagnosticsStore {
    private const val MAX_FILE_BYTES = 1024 * 1024L
    private const val FILE_COUNT = 4
    /** "PK\x03\x04": the bytes every ZIP begins with, as served over content://. */
    internal val ZIP_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val hookCodes = setOf(
        "MODULE_LOADED", "NATIVE_LOADED", "NATIVE_LOAD_FAILED", "ACTIVITY_HOOK_INSTALLED",
        "ACTIVITY_HOOK_FAILED", "CONFIG_STARTED", "CONFIGURED", "CONFIG_FAILED",
        "CAPTURE_ENDPOINT_READY", "CAPTURE_ENDPOINT_LOST", "LOG_DROPPED", "HOOK_BATCH_REJECTED",
    )
    /** Bounded badge-geometry trace codes emitted by the assistant overlay. */
    private val hintCodes = setOf(
        "HOOK_COORDS", "HAND_SHAPE", "BADGE_PARTIAL", "BADGE_HIDDEN", "GAME_GEOMETRY", "HINT_ORDER",
        "COORD_UNSTABLE", "BADGE_PLACED",
    )
    /**
     * The controller's structured vocabulary. Codes are kept only when they match
     * `[A-Z0-9_]{2,48}`, so without this list `ACK_UNMATCHED_REPEAT` and
     * `GAME_REJECTED_REPEAT` would silently collapse into `UNKNOWN_EVENT` and the
     * folded counts would disappear from an export.
     */
    private val autoplayCodes = setOf(
        "ENABLED", "QUEUED", "HANDOVER", "PAUSED", "WAITING", "GAME_WAITING", "GAME_SUBMITTED",
        "SERVER_ACCEPTED", "UPLINK_CONFIRMED", "GAME_REJECTED", "GAME_REJECTED_REPEAT",
        "ACK_UNMATCHED", "ACK_UNMATCHED_REPEAT", "ACK_OVERSIZE", "CLAIM_PROBE", "ROUND_CONTINUED",
        "RESYNC_REQUESTED", "GAME_RELAUNCHED", "GAME_RELAUNCH_FAILED",
    )
    private val levels = setOf("DEBUG", "INFO", "WARN", "ERROR")
    /**
     * The manager's own event vocabulary. `EXPORT_*` is part of it because those
     * codes are what makes an aborted share separate from a failed archive build.
     * An unknown manager code is kept verbatim only when it is a structured
     * `NAME_TAG` identifier; free-form text still collapses to `MANAGER_ACTION`
     * so ad-hoc messages cannot smuggle content into the allow-listed log.
     */
    private val managerCodes = setOf(
        "EXPORT_FAILED", "EXPORT_SHARE_UNAVAILABLE", "EXPORT_SAVED", "EXPORT_SHARED",
        "EXPORT_INTENT_UNAVAILABLE", "EXPORT_URI_UNREADABLE",
        "EXPORT_STARTED", "MANAGER_ACTION", "GAME_LAUNCH_REQUESTED", "DIAGNOSTICS_REFRESH",
        "GAME_SETTINGS_OPENED",
    )
    private val structuredCode = Regex("[A-Z][A-Z0-9]*(_[A-Z0-9]+)+")
    private val components = setOf("hook.entry", "hook.native", "hook.capture", "assistant.service", "assistant.model", "assistant.engine", "assistant.autoplay", "assistant.rematch", "assistant.hint", "manager")
    private val allowedFieldNames = setOf("api", "moduleVersion", "count", "reason",
        "importId", "stage", "format", "bytes", "elapsedMs", "nativeStatus", "errorClass", "errorMessage",
        // Export flow: which delivery step succeeded or failed (plain reason word).
        "event",
        // Badge geometry diagnostics: fixed-shape counters, sizes, and the tile
        // order/coordinate snapshot needed to validate hand-to-view mapping.
        "kept", "rejected", "ambiguous", "missing", "placed", "hand", "views", "hints",
        "screenW", "screenH", "overlayW", "overlayH", "open", "baida", "invalid", "ortho", "tiles",
        "order", "reds", "behind", "minNx", "maxNx", "minNy", "maxNy", "sample", "views_n",
        "cam", "cams", "pairs", "frame", "targets",
        // Badge mapping trace (GeometryTrace.structuredKeys emits these). They were
        // missing here, so every BADGE_HIDDEN / BADGE_PLACED record silently lost
        // the source viewport, frame origin and viewport — the exact values needed
        // to tell "source dims never arrived" from "frame not measured yet".
        "sourceW", "sourceH", "frameOrigin", "viewport",
        "anchor", "cardH", "off", "badgeH", "gap", "top", "skipped", "frameH",
        // Build identity of the installed Lua UI asset, so a stale cached script
        // (which kept sending the legacy bare hand_coords array, without the
        // {screenW, screenH, coords} wrapper) is visible in the log instead of
        // looking like a Kotlin parsing fault.
        "assetVersion",
        // Autoplay protocol flow: which link of the ACK chain produced the record and
        // how much of that chain has been confirmed so far. Booleans only.
        "stage", "ack_id", "wire", "submitted", "accepted",
        // Rejection rate limiting: how many identical rejections were folded into the
        // previous record, so a silent tick loop is still visible as a count.
        "suppressed")

    private fun directory(context: Context) = File(context.filesDir, "diagnostics")
    private fun logFile(context: Context, kind: DiagnosticKind, index: Int = 1) =
        File(directory(context), "${kind.filename}-$index.jsonl")

    @Synchronized
    fun appendAssistant(context: Context, level: String, component: String, code: String,
        fields: JSONObject = JSONObject()) {
        val safeComponent = safeComponent(component)
        val record = DiagnosticRecord(System.currentTimeMillis(), "manager", System.nanoTime(),
            safeLevel(level), safeComponent, managerCode(code, safeComponent), safeFields(fields))
        append(context, DiagnosticKind.Assistant, record)
    }

    @Synchronized
    fun acceptHookBatch(context: Context, payload: String): Long {
        val incoming = runCatching { JSONArray(payload) }.getOrNull() ?: return 0
        if (incoming.length() > 64 || payload.toByteArray().size > 64 * 1024) return 0
        var accepted = 0L
        var highestValidated = 0L
        val known = read(context, DiagnosticKind.Hook).asSequence()
            .map { "${it.session}:${it.seq}" }.toHashSet()
        for (i in 0 until incoming.length()) {
            val source = incoming.optJSONObject(i) ?: continue
            if (source.optInt("schema", -1) != 1) continue
            val session = source.optString("session").takeIf { it.matches(Regex("[A-Fa-f0-9-]{16,64}")) } ?: continue
            val seq = source.optLong("seq", -1).takeIf { it >= 0 } ?: continue
            val code = source.optString("code")
            if (code !in hookCodes) continue
            val component = source.optString("component")
            if (component !in components) continue
            highestValidated = maxOf(highestValidated, seq)
            if ("${session}:$seq" in known) continue
            val level = source.optString("level")
            val record = DiagnosticRecord(source.optLong("timeUtcMs").takeIf { it > 0 } ?: System.currentTimeMillis(),
                session, seq, safeLevel(level), safeComponent(component), code,
                safeFields(source.optJSONObject("fields") ?: JSONObject()))
            append(context, DiagnosticKind.Hook, record)
            known += "${session}:$seq"
            accepted = maxOf(accepted, seq)
        }
        return maxOf(accepted, highestValidated)
    }

    @Synchronized
    fun read(context: Context, kind: DiagnosticKind): List<DiagnosticRecord> {
        migrateLegacy(context)
        val records = mutableListOf<DiagnosticRecord>()
        for (index in FILE_COUNT downTo 1) {
            logFile(context, kind, index).takeIf(File::isFile)?.forEachLine { line ->
                runCatching { parseAndSanitize(JSONObject(line), kind) }.getOrNull()?.let(records::add)
            }
        }
        return records.sortedWith(compareBy<DiagnosticRecord> { it.timeUtcMs }.thenBy { it.seq })
    }

    /** Clear only diagnostic JSONL files; serialize with reads, appends and rotation. */
    @Synchronized
    fun clear(context: Context, kind: DiagnosticKind? = null) {
        // Complete migration first so a subsequent read cannot restore legacy logs.
        migrateLegacy(context)
        val kinds = kind?.let { listOf(it) } ?: DiagnosticKind.entries
        for (selected in kinds) {
            for (index in 1..FILE_COUNT) {
                val file = logFile(context, selected, index)
                check(!file.exists() || file.delete()) { "部分日志未能清理，请重试" }
            }
        }
    }

    fun packageZip(context: Context, rangeMs: Long?): File {
        val now = System.currentTimeMillis()
        val hook = read(context, DiagnosticKind.Hook).filter { rangeMs == null || now - it.timeUtcMs <= rangeMs }
        val assistant = read(context, DiagnosticKind.Assistant).filter { rangeMs == null || now - it.timeUtcMs <= rangeMs }
        val shareDir = File(context.cacheDir, "diagnostics/share").apply { mkdirs() }
        // Purge before creating this archive: the expiry check uses lastModified, and the
        // file this call writes must not be a candidate for it.
        DiagnosticsStore.cleanExpiredExports(context)
        // The receiving app shows this name in its own UI, so it carries the app name and
        // a sortable UTC stamp instead of the raw epoch millis.
        val zip = File(shareDir, exportFileName(now))
        val manifest = JSONObject().apply {
            put("applicationId", context.packageName)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("androidApi", Build.VERSION.SDK_INT)
            put("gameVersion", runCatching {
                val packageInfo = context.packageManager.getPackageInfo(AiOverlayService.GAME, 0)
                packageInfo.versionName ?: "unknown"
            }.getOrDefault("not_installed"))
            put("exportedAtUtcMs", now)
            put("rangeMs", rangeMs ?: JSONObject.NULL)
            put("hookEntries", hook.size)
            put("assistantEntries", assistant.size)
            put("oldestUtcMs", (hook + assistant).minOfOrNull { it.timeUtcMs } ?: JSONObject.NULL)
            put("newestUtcMs", (hook + assistant).maxOfOrNull { it.timeUtcMs } ?: JSONObject.NULL)
            put("rotated", logFile(context, DiagnosticKind.Hook, 2).exists() || logFile(context, DiagnosticKind.Assistant, 2).exists())
        }
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            fun entry(name: String, text: String) {
                out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray(Charsets.UTF_8)); out.closeEntry()
            }
            entry("manifest.json", manifest.toString(2))
            entry("hook.jsonl", hook.joinToString("\n", postfix = if (hook.isNotEmpty()) "\n" else "") { encode(it).toString() })
            entry("assistant.jsonl", assistant.joinToString("\n", postfix = if (assistant.isNotEmpty()) "\n" else "") { encode(it).toString() })
            entry("README.txt", "雀魂 Max Hook 诊断包。仅包含应用产生的结构化事件，不包含原始网络帧、牌局数据、账号信息或模型文件。\n")
        }
        if (zip.length() > 8 * 1024 * 1024L) {
            zip.delete()
            error("故障包超过 8 MiB，请缩短导出时间范围")
        }
        return zip
    }

    /** Stable, MIME-safe archive name shared by the share intent and Downloads fallback. */
    internal fun exportFileName(nowUtcMs: Long): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(Date(nowUtcMs))
        return "majmax-diagnostics-$stamp.zip"
    }

    fun estimateBytes(hook: List<DiagnosticRecord>, assistant: List<DiagnosticRecord>): Long {
        fun size(records: List<DiagnosticRecord>): Long = records.sumOf {
            encode(it).toString().toByteArray(Charsets.UTF_8).size.toLong() + 1L
        }
        return size(hook) + size(assistant) + 1024L
    }

    fun cleanExpiredExports(context: Context) {
        // Two days, not one: the receiving app may keep the content:// URI (the user can
        // pick "send later"), and deleting the backing file breaks that grant. An archive
        // is a few KiB at most, so the extra day costs nothing.
        val cutoff = System.currentTimeMillis() - 48 * 60 * 60 * 1000L
        File(context.cacheDir, "diagnostics/share").listFiles()?.filter {
            it.isFile && it.lastModified() < cutoff
        }?.forEach(File::delete)
    }

    /**
     * Builds the send intent for an exported package.
     *
     * Two invariants are enforced here instead of being assumed by the caller:
     *
     *  1. The authority is chosen by asking the platform, not by string convention.
     *     `PackageManager.resolveContentProvider` must return a provider whose class is
     *     `DiagnosticsFileProvider`, and `getUriForFile` must still land in the
     *     diagnostics share directory after that resolution. A manifest that shares one
     *     provider class across authorities (or points the authority at the update
     *     paths) fails this check instead of producing an unreadable URI.
     *  2. The URI is really readable *right now*: it is opened through
     *     `ContentResolver` and the ZIP local-file header is read back. Opening the
     *     chooser proves nothing — the receiving app is the one that fails, and a
     *     "分享没反应" report is exactly what a provider that answers the manifest but
     *     serves no bytes looks like.
     *
     * @throws IllegalStateException with a user-visible Chinese reason when the
     *   archive cannot be handed to another app; callers fall back to Downloads.
     */
    fun shareIntent(context: Context, file: File): Intent {
        val authority = "${context.packageName}.diagnostics"
        val provider = context.packageManager.resolveContentProvider(authority, 0)
        check(provider != null && DiagnosticsFileProvider::class.java.name == provider.name) {
            "故障包分享组件未就绪（${provider?.name ?: "missing"}），请重装模块"
        }
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        check(uri.authority == authority) { "故障包 URI 指向了错误的组件：${uri.authority}" }
        verifyReadableArchive(context, uri)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Reads [uri] back through `ContentResolver` the way a receiving app would.
     *
     * A truncated or empty answer is a hard failure: the share sheet would open, the
     * user would pick an app, and the app would report a broken file, which is the
     * bug this check exists to stop. The full ZIP structure is validated earlier on
     * the local file by [packageZip]; here only delivery through the provider matters.
     */
    internal fun verifyReadableArchive(context: Context, uri: Uri) {
        val header = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ByteArray(4).also { buffer ->
                    var read = 0
                    while (read < buffer.size) {
                        val count = input.read(buffer, read, buffer.size - read)
                        if (count <= 0) break
                        read += count
                    }
                    if (read < buffer.size) null else buffer
                }
            }
        }.getOrNull()
        check(header != null && header.contentEquals(ZIP_HEADER)) {
            "故障包无法通过分享组件读取，请稍后重试或使用“保存到下载”"
        }
    }

    /**
     * True when at least one installed app can accept [shareIntent]'s payload.
     * Callers must check this *before* building the archive: a device without any
     * `application/zip` handler (or without a share sheet at all) otherwise turns
     * every export into a packaged file that can never be delivered.
     */
    fun canShare(context: Context): Boolean {
        val probe = Intent(Intent.ACTION_SEND).setType("application/zip")
        return runCatching { context.packageManager.queryIntentActivities(probe, 0) }
            .getOrDefault(emptyList()).isNotEmpty()
    }

    /**
     * Fallback delivery that needs no chooser and no third-party app: copy the
     * archive into the public Downloads collection, which keeps the bytes even when
     * the share sheet is missing. Returns the created document URI, or null when the
     * platform refused to create the entry.
     */
    fun saveToDownloads(context: Context, zip: File, displayName: String): Uri? = runCatching {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }) ?: return@runCatching null
        val copied = runCatching {
            resolver.openOutputStream(target)?.use { output ->
                zip.inputStream().use { input -> input.copyTo(output) }
            } ?: error("openOutputStream returned null")
        }
        if (copied.isFailure) {
            runCatching { resolver.delete(target, null, null) }
            return@runCatching null
        }
        resolver.update(target, ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }, null, null)
        target
    }.getOrNull()

    private fun append(context: Context, kind: DiagnosticKind, record: DiagnosticRecord) {
        val dir = directory(context)
        if (!dir.exists()) dir.mkdirs()
        val file = logFile(context, kind)
        if (file.exists() && file.length() >= MAX_FILE_BYTES) {
            for (index in FILE_COUNT downTo 2) {
                val previous = logFile(context, kind, index - 1)
                val target = logFile(context, kind, index)
                target.delete()
                if (previous.exists()) previous.renameTo(target)
            }
        }
        file.appendText(encode(record).toString() + "\n", Charsets.UTF_8)
    }

    private fun encode(record: DiagnosticRecord) = JSONObject().apply {
        put("schema", 1); put("timeUtcMs", record.timeUtcMs); put("session", record.session)
        put("seq", record.seq); put("level", record.level); put("component", record.component)
        put("code", record.code); put("fields", record.fields)
    }

    private fun parseAndSanitize(value: JSONObject, kind: DiagnosticKind): DiagnosticRecord? {
        if (value.optInt("schema", -1) != 1) return null
        val component = safeComponent(value.optString("component"))
        val code = managerCode(value.optString("code"), component)
        if (kind == DiagnosticKind.Hook && code !in hookCodes) return null
        return DiagnosticRecord(value.optLong("timeUtcMs").takeIf { it > 0 } ?: return null,
            value.optString("session").take(64), value.optLong("seq"), safeLevel(value.optString("level")),
            component, code,
            safeFields(value.optJSONObject("fields") ?: JSONObject()))
    }

    private fun safeFields(source: JSONObject): JSONObject = JSONObject().apply {
        for (key in allowedFieldNames) {
            if (!source.has(key)) continue
            when (val value = source.opt(key)) {
                is Number -> put(key, value)
                is String -> {
                    // Order/coordinate snapshots need separators and room for ~14 tiles.
                    // `reason` carries the controller's rendered ACK detail: the old 64
                    // character cap turned every `hand_geometry` rejection into the same
                    // 54 character fragment (`tiles:1m,7z,cams:UICamera|o`), so a real
                    // rejection and its duplicate were indistinguishable. The value is
                    // still filtered to the same separator set and remains a fixed
                    // vocabulary ([AutoAckDetails]), never a raw adapter payload.
                    val limit = fieldLimit(key)
                    put(key, value.take(limit).filter { it.isLetterOrDigit() || it in " _-./()：:@,+|;=!" })
                }
            }
        }
    }

    /**
     * One autoplay record for an in-game ACK. [detail] is already rendered by
     * [AutoAckDetails] (fixed vocabulary, bounded), never the raw adapter payload.
     * [stage] names the ACK-chain link the record came from so a later reader does
     * not have to guess whether a line was a rejection, a duplicate, or an ack
     * that carried no id at all.
     */
    @Synchronized
    fun appendAutoplay(context: Context, code: String, stage: String, detail: String,
        ackId: String = "", wire: Boolean? = null, submitted: Boolean? = null,
        accepted: Boolean? = null, suppressed: Int = 0) {
        val fields = JSONObject().apply {
            if (detail.isNotEmpty()) put("reason", detail)
            if (stage.isNotEmpty()) put("stage", stage)
            if (ackId.isNotEmpty()) put("ack_id", ackId.take(ACK_ID_LIMIT))
            wire?.let { put("wire", if (it) 1 else 0) }
            submitted?.let { put("submitted", if (it) 1 else 0) }
            accepted?.let { put("accepted", if (it) 1 else 0) }
            if (suppressed > 0) put("suppressed", suppressed)
        }
        appendAssistant(context, "INFO", "assistant.autoplay", code, fields)
    }

    private const val AUTO_REASON_LIMIT = 512
    private const val CAMERAS_LIMIT = 320

    /**
     * Field length caps. [appendAutoplay] truncates an `ack_id` to the same
     * [ACK_ID_LIMIT] the sanitiser applies, so a caller can never hand over a value
     * the store would then cut a second time: a controller id is `UUID-sequence`
     * (38+ characters), and cutting it mid-UUID is why the QUEUED rows of an older
     * build could not be joined to their submit/accept records.
     */
    private const val ACK_ID_LIMIT = 96

    private fun fieldLimit(key: String): Int = when (key) {
        "errorMessage" -> 240
        "reason" -> AUTO_REASON_LIMIT
        "order", "views", "tiles", "pairs", "targets" -> 512
        "cams" -> CAMERAS_LIMIT
        "ack_id" -> ACK_ID_LIMIT
        else -> 64
    }

    private fun safeLevel(value: String) = value.takeIf { it in levels } ?: "INFO"
    private fun safeComponent(value: String) = value.takeIf { it in components } ?: "manager"
    private fun safeCode(value: String) =
        value.takeIf { it.matches(Regex("[A-Z0-9_]{2,48}")) } ?: "UNKNOWN_EVENT"

    /** Manager events are validated against the UI's own code list; others got generic. */
    private fun managerCode(value: String, component: String): String {
        val code = safeCode(value)
        // An autoplay code outside the controller's vocabulary is still a protocol
        // event, not manager chatter: keep it out of the manager fallback so the
        // repeat summaries survive, but never let free text through.
        if (component == "assistant.autoplay" && code !in autoplayCodes) return "UNKNOWN_EVENT"
        if (component != "manager") return code
        if (code in managerCodes || code in hookCodes) return code
        return if (code.matches(structuredCode)) code else "MANAGER_ACTION"
    }

    @Synchronized
    private fun migrateLegacy(context: Context) {
        val legacy = File(context.filesDir, "manager-diagnostics.log")
        val marker = File(context.filesDir, "manager-diagnostics.migrated")
        if (marker.exists() || !legacy.isFile) return
        val recent = ArrayDeque<String>(2_000)
        legacy.forEachLine { line ->
            if (recent.size == 2_000) recent.removeFirst()
            recent.addLast(line)
        }
        recent.forEach { line ->
            val parts = line.split('|', limit = 4)
            if (parts.size == 4) {
                val source = parts[2].take(32).replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val record = DiagnosticRecord(parts[0].toLongOrNull() ?: System.currentTimeMillis(),
                    "legacy-${UUID.randomUUID()}", 0, safeLevel(parts[1]), "manager",
                    "LEGACY_${source.uppercase().take(24)}", JSONObject().put("reason", "migrated"))
                append(context, DiagnosticKind.Assistant, record)
            }
        }
        marker.writeText("done")
        legacy.delete()
    }
}
