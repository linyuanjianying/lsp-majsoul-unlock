package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

internal data class ImportedPolicy(
    val players: Int,
    val name: String,
    val bytes: Long,
    val sha256: String,
    val format: String = "akagi-onnx",
)

/**
 * Validates and runs Akagi (.onnx) and Mortal (.pth / .onnx) policy models
 * for both 4-player and 3-player mahjong inside app-private storage.
 */
internal object OnnxModelStore {
    private const val MAX_BYTES = 128L * 1024 * 1024
    private const val PREFS = "onnx-policy-models"
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private val timeoutScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "majmax-onnx-timeout").apply { isDaemon = true }
    }

    internal data class OnnxSpec(
        val players: Int,
        val description: String,
        val contractKind: Int, // 0 = Akagi ONNX, 1 = Mortal ONNX
        val obsVersion: Int,
        val obsChannels: Int,
        val tileDim: Int,
        val actionDim: Int,
    )

    private data class Cached(val file: File, val session: OrtSession, val spec: OnnxSpec)
    private val sessions = mutableMapOf<String, Cached>()
    private val activeSessions = mutableMapOf<Int, Cached>()
    private val failedPlayers = mutableSetOf<Int>()
    private val mortal4pChannels = mapOf(1012 to 4, 948 to 3, 938 to 2, 450 to 1)
    private const val MORTAL_3P_CHANNELS = 775
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) { appContext = context.applicationContext }

    fun info(context: Context, players: Int): ImportedPolicy? {
        require(players == 3 || players == 4)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = selectedFile(context, players)
        if (!path.isFile) return null
        val defaultFormat = if (path.name.endsWith(".pth", ignoreCase = true) || path.name.endsWith(".pt", ignoreCase = true)) {
            "mortal-pth"
        } else {
            "akagi-onnx"
        }
        return ImportedPolicy(
            players = players,
            name = prefs.getString("name_$players", path.name) ?: path.name,
            bytes = path.length(),
            sha256 = prefs.getString("sha_$players", "") ?: "",
            format = prefs.getString("format_$players", defaultFormat) ?: defaultFormat,
        )
    }

    fun selectedCustom(context: Context, players: Int): Boolean =
        info(context, players) != null && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("enabled_$players", false)

    fun enabledMask(context: Context): Int =
        (if (selectedCustom(context, 4)) 1 else 0) or (if (selectedCustom(context, 3)) 2 else 0)

    fun prepareSelected(context: Context) {
        initialize(context)
        for (players in listOf(4, 3)) {
            if (!selectedCustom(context, players)) {
                synchronized(sessions) { activeSessions.remove(players) }
                continue
            }
            val result = activate(players)
            if (result != 0) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("enabled_$players", false).apply()
                DiagnosticsStore.appendAssistant(context, "ERROR", "assistant.model", "MODEL_STARTUP_FALLBACK",
                    org.json.JSONObject().put("count", players))
            }
        }
    }

    fun selectBuiltIn(context: Context, players: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled_$players", false).apply()
        DiagnosticsStore.appendAssistant(context, "INFO", "assistant.model", "MODEL_BUILTIN",
            org.json.JSONObject().put("count", players))
    }

    fun selectCustom(context: Context, players: Int) {
        check(info(context, players) != null) { "请先导入有效的 .onnx 或 .pth 模型" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled_$players", true).apply()
        DiagnosticsStore.appendAssistant(context, "INFO", "assistant.model", "MODEL_CUSTOM_SELECTED",
            org.json.JSONObject().put("count", players))
    }

    fun delete(context: Context, players: Int) {
        val oldFile = selectedFile(context, players)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("enabled_$players")
            .remove("name_$players")
            .remove("sha_$players")
            .remove("file_$players")
            .remove("format_$players")
            .apply()
        synchronized(sessions) {
            if (sessions.values.none { it.file.absolutePath == oldFile.absolutePath }) oldFile.delete()
        }
        DiagnosticsStore.appendAssistant(context, "INFO", "assistant.model", "MODEL_DELETED",
            org.json.JSONObject().put("count", players))
    }

    fun import(context: Context, uri: Uri): ImportedPolicy {
        initialize(context)
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: "policy.onnx"
        val lowerName = displayName.lowercase()
        val isPthExt = lowerName.endsWith(".pth") || lowerName.endsWith(".pt")
        val isOnnxExt = lowerName.endsWith(".onnx")
        require(isPthExt || isOnnxExt) { "请选择 .onnx、.pth 或 .pt 模型文件" }

        val parent = File(context.filesDir, "models/incoming/${UUID.randomUUID()}").apply { mkdirs() }
        val staged = File(parent, if (isPthExt) "policy.pth" else "policy.onnx")
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        var rejectionReason = "import_failed"
        var treatAsPth = isPthExt
        try {
            val input = checkNotNull(context.contentResolver.openInputStream(uri)) { "无法读取所选文件" }
            input.buffered().use { source ->
                source.mark(4)
                val signature = IntArray(4) { source.read() }
                source.reset()
                if (isZipArchive(signature)) {
                    // PyTorch .pth checkpoints are ZIP archives containing data.pkl and data/* tensors.
                    treatAsPth = true
                }
                staged.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= MAX_BYTES) { "模型文件超过 128 MiB 限制" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(bytes > 0) { "模型文件为空" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }

            if (treatAsPth) {
                val detectedPlayers = AiNative.loadMortalPth(0, staged.absolutePath)
                if (detectedPlayers != 3 && detectedPlayers != 4) {
                    rejectionReason = "invalid_mortal_pth_$detectedPlayers"
                    throw IllegalArgumentException("无法解析 Mortal .pth 权重文件或结构不受支持 (状态码: $detectedPlayers)")
                }
                val players = detectedPlayers
                val folder = File(File(context.filesDir, "models"), "$players-$hash").apply { mkdirs() }
                val target = File(folder, "policy.pth")
                val temp = File(folder, "policy.tmp")
                staged.copyTo(temp, overwrite = true)
                if (target.exists()) temp.delete() else check(temp.renameTo(target)) { "无法保存模型文件" }
                // Point Rust runtime at the permanent target path.
                check(AiNative.loadMortalPth(players, target.absolutePath) == players) { "无法激活已保存的 Mortal .pth 模型" }
                synchronized(sessions) {
                    activeSessions.remove(players)
                    failedPlayers.remove(players)
                }
                val savedName = displayName.take(64)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("name_$players", savedName)
                    .putString("sha_$players", hash)
                    .putString("file_$players", target.relativeTo(context.filesDir).path)
                    .putString("format_$players", "mortal-pth")
                    .putBoolean("enabled_$players", true)
                    .apply()
                DiagnosticsStore.appendAssistant(context, "INFO", "assistant.model", "MODEL_IMPORTED",
                    org.json.JSONObject().put("count", players).put("reason", "pth:${hash.take(12)}"))
                return ImportedPolicy(players, savedName, bytes, hash, "mortal-pth")
            }

            rejectExternalWeights(staged)
            val spec = validateOnnx(staged)
            val players = spec.players
            val folder = File(File(context.filesDir, "models"), "$players-$hash").apply { mkdirs() }
            val target = File(folder, "policy.onnx")
            val temp = File(folder, "policy.tmp")
            staged.copyTo(temp, overwrite = true)
            if (target.exists()) temp.delete() else check(temp.renameTo(target)) { "无法保存模型文件" }
            synchronized(sessions) {
                if (target.absolutePath !in sessions) {
                    sessions[target.absolutePath] = createValidatedCached(target, players)
                }
            }
            val formatTag = if (spec.contractKind == 1) "mortal-onnx" else "akagi-onnx"
            val savedName = spec.description.ifBlank { displayName }.take(64)
            AiNative.configureCustomContract(players, spec.contractKind, spec.obsVersion, spec.obsChannels, spec.actionDim)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("name_$players", savedName)
                .putString("sha_$players", hash)
                .putString("file_$players", target.relativeTo(context.filesDir).path)
                .putString("format_$players", formatTag)
                .putBoolean("enabled_$players", true)
                .apply()
            DiagnosticsStore.appendAssistant(context, "INFO", "assistant.model", "MODEL_IMPORTED",
                org.json.JSONObject().put("count", players).put("reason", "$formatTag:${hash.take(12)}"))
            return ImportedPolicy(players, savedName, bytes, hash, formatTag)
        } catch (error: Throwable) {
            DiagnosticsStore.appendAssistant(context, "ERROR", "assistant.model", "MODEL_IMPORT_REJECTED",
                org.json.JSONObject().put("reason", rejectionReason))
            throw error
        } finally {
            staged.delete()
            parent.delete()
        }
    }

    private fun isZipArchive(signature: IntArray): Boolean =
        signature[0] == 0x50 && signature[1] == 0x4b &&
            ((signature[2] == 0x03 && signature[3] == 0x04) ||
                (signature[2] == 0x05 && signature[3] == 0x06) ||
                (signature[2] == 0x07 && signature[3] == 0x08))

    /** JNI calls this on the assistant worker thread for .onnx models (Akagi or Mortal). */
    @JvmStatic
    fun infer(players: Int, observation: FloatArray): FloatArray? {
        val context = appContext ?: return null
        if (players != 3 && players != 4) return null
        return try {
            synchronized(sessions) {
                if (players in failedPlayers) return null
                val cached = activeSessions[players] ?: return null
                run(cached.session, cached.spec, observation)
            }
        } catch (error: Throwable) {
            DiagnosticsStore.appendAssistant(context, "ERROR", "assistant.model", "MODEL_INFERENCE_FAILED",
                org.json.JSONObject().put("count", players))
            synchronized(sessions) { failedPlayers += players; activeSessions.remove(players) }
            null
        }
    }

    /** Called once by Rust when a complete new game is synchronized. */
    @JvmStatic
    fun activate(players: Int): Int {
        val context = appContext ?: return -1
        if (players != 3 && players != 4 || !selectedCustom(context, players)) return -1
        return try {
            val file = selectedFile(context, players)
            check(file.isFile) { "模型文件不存在" }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val format = prefs.getString("format_$players", "") ?: ""
            if (format == "mortal-pth" || file.name.endsWith(".pth", ignoreCase = true) || file.name.endsWith(".pt", ignoreCase = true)) {
                val rc = AiNative.loadMortalPth(players, file.absolutePath)
                check(rc == players) { "无法加载 Mortal .pth 模型 (状态码: $rc)" }
                synchronized(sessions) {
                    activeSessions.remove(players)
                    failedPlayers.remove(players)
                }
                return 0
            }
            synchronized(sessions) {
                val cached = sessions[file.absolutePath] ?: createValidatedCached(file, players)
                    .also { sessions[file.absolutePath] = it }
                AiNative.configureCustomContract(
                    players,
                    cached.spec.contractKind,
                    cached.spec.obsVersion,
                    cached.spec.obsChannels,
                    cached.spec.actionDim,
                )
                activeSessions[players] = cached
                failedPlayers.remove(players)
            }
            0
        } catch (error: Throwable) {
            DiagnosticsStore.appendAssistant(context, "ERROR", "assistant.model", "MODEL_ACTIVATION_FAILED",
                org.json.JSONObject().put("count", players))
            -1
        }
    }

    fun selfTest(context: Context, players: Int): String {
        val started = System.nanoTime()
        val file = selectedFile(context, players)
        check(file.isFile) { "尚未导入${players}人模型" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val format = prefs.getString("format_$players", "") ?: ""
        if (format == "mortal-pth" || file.name.endsWith(".pth", ignoreCase = true) || file.name.endsWith(".pt", ignoreCase = true)) {
            val rc = AiNative.loadMortalPth(players, file.absolutePath)
            check(rc == players) { "Mortal .pth 模型自检失败 (状态码: $rc)" }
            return "${players}人 Mortal (.pth) 模型可用 · ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} ms"
        }
        val cached = createValidatedCached(file, players)
        cached.session.use {
            run(it, cached.spec, FloatArray(cached.spec.obsChannels * cached.spec.tileDim))
        }
        val kindLabel = if (cached.spec.contractKind == 1) "Mortal (.onnx)" else "Akagi (.onnx)"
        return "${players}人 $kindLabel 模型可用 · ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} ms"
    }

    private fun validateOnnx(file: File, expectedPlayers: Int? = null): OnnxSpec {
        val session = createSession(file)
        session.use {
            return inspectAndVerifyOnnx(session, expectedPlayers)
        }
    }

    private fun createValidatedCached(file: File, players: Int): Cached {
        val session = createSession(file)
        try {
            val spec = inspectAndVerifyOnnx(session, players)
            return Cached(file, session, spec)
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    private fun inspectAndVerifyOnnx(session: OrtSession, expectedPlayers: Int?): OnnxSpec {
        require(session.inputNames == setOf("obs") && session.outputNames == setOf("logits")) {
            "ONNX 输入/输出节点名必须为 obs 和 logits"
        }
        val inInfo = tensorInfo(session.inputInfo.getValue("obs"))
        val outInfo = tensorInfo(session.outputInfo.getValue("logits"))
        require(inInfo.type == OnnxJavaType.FLOAT && outInfo.type == OnnxJavaType.FLOAT) {
            "输入与输出节点必须是 float32 张量"
        }
        val inShape = inInfo.shape
        val outShape = outInfo.shape
        require(inShape.size == 3 && inShape[0] == 1L && outShape.size == 2 && outShape[0] == 1L) {
            "张量形状必须为 obs[1, C, T] 与 logits[1, A]"
        }
        val c = inShape[1].toInt()
        val t = inShape[2].toInt()
        val a = outShape[1].toInt()

        val metadata = session.metadata
        val contractMeta = metadata.getCustomMetadataValue("majmax.contract").orElse("")

        // 1. Check standard Akagi contract ([1, 39, 34] -> [1, 82] for 4P, [1, 37, 27] -> [1, 60] for 3P)
        val spec = when {
            c == 39 && t == 34 && a == 82 -> {
                if (contractMeta.isNotEmpty() && contractMeta != "akagi-policy-v1") {
                    throw IllegalArgumentException("模型协议不匹配: $contractMeta")
                }
                OnnxSpec(4, metadata.description, 0, 1, 39, 34, 82)
            }
            c == 37 && t == 27 && a == 60 -> {
                if (contractMeta.isNotEmpty() && contractMeta != "akagi-policy-v1") {
                    throw IllegalArgumentException("模型协议不匹配: $contractMeta")
                }
                OnnxSpec(3, metadata.description, 0, 1, 37, 27, 60)
            }
            // 2. Check Mortal 4P ONNX ([1, C, 34] -> [1, 46])
            t == 34 && a == 46 && mortal4pChannels.containsKey(c) -> {
                OnnxSpec(4, metadata.description, 1, mortal4pChannels.getValue(c), c, 34, 46)
            }
            // 3. Check Mortal 3P ONNX ([1, 775, 34] -> [1, 44])
            c == MORTAL_3P_CHANNELS && t == 34 && a == 44 -> {
                OnnxSpec(3, metadata.description, 1, 5, c, 34, 44)
            }
            else -> throw IllegalArgumentException(
                "不支持的 ONNX 模型张量维度: obs=[1, $c, $t], logits=[1, $a]"
            )
        }

        if (expectedPlayers != null) {
            require(spec.players == expectedPlayers) { "模型适用人数 (${spec.players}人) 与槽位 (${expectedPlayers}人) 不匹配" }
        }
        run(session, spec, FloatArray(spec.obsChannels * spec.tileDim))
        return spec
    }

    private fun run(session: OrtSession, spec: OnnxSpec, observation: FloatArray): FloatArray {
        val expectedInput = spec.obsChannels * spec.tileDim
        require(observation.size == expectedInput) { "观察特征长度不匹配: ${observation.size} != $expectedInput" }
        require(observation.all(Float::isFinite)) { "观察特征无效" }
        val shape = longArrayOf(1L, spec.obsChannels.toLong(), spec.tileDim.toLong())
        val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(observation), shape)
        val options = OrtSession.RunOptions()
        val timeout = timeoutScheduler.schedule({ runCatching { options.setTerminate(true) } }, 500, TimeUnit.MILLISECONDS)
        try {
            tensor.use {
                session.run(mapOf("obs" to it), setOf("logits"), options).use { results ->
                    val output = results.get("logits").orElseThrow() as OnnxTensor
                    val values = output.floatBuffer
                    val logits = FloatArray(values.remaining()).also { values.get(it) }
                    require(logits.size == spec.actionDim && logits.all(Float::isFinite)) { "模型输出无效" }
                    return logits
                }
            }
        } finally {
            timeout.cancel(false)
            options.close()
        }
    }

    private fun tensorInfo(info: ai.onnxruntime.NodeInfo): TensorInfo = info.info as? TensorInfo
        ?: throw IllegalArgumentException("只支持稠密张量模型")

    /** TensorProto.external_data uses field 13 containing a StringStringEntry key named location. */
    private fun rejectExternalWeights(file: File) {
        val bytes = file.readBytes()
        val key = "location".toByteArray(Charsets.US_ASCII)
        for (start in 4..bytes.size - key.size) {
            var matches = true
            for (offset in key.indices) if (bytes[start + offset] != key[offset]) { matches = false; break }
            if (matches && bytes[start - 2] == 0x0a.toByte() && bytes[start - 1] == key.size.toByte() &&
                bytes[start - 4] == 0x6a.toByte()) {
                throw IllegalArgumentException("不支持引用外置权重文件的 ONNX 模型")
            }
        }
    }

    private fun createSession(file: File): OrtSession {
        val options = OrtSession.SessionOptions()
        return try { environment.createSession(file.absolutePath, options) }
        finally { options.close() }
    }

    fun closeAll() = synchronized(sessions) {
        sessions.values.forEach { runCatching { it.session.close() } }
        sessions.clear(); activeSessions.clear(); failedPlayers.clear()
    }

    private fun selectedFile(context: Context, players: Int): File {
        val relative = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("file_$players", null)
        return relative?.let { File(context.filesDir, it) }
            ?: File(File(context.filesDir, "models"), "policy-$players.onnx")
    }
}
