package com.yefeng.majmax.hookprobe.manager

import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Regression checks for the v0.14.20 "log export cannot be shared" report.
 *
 * Three properties are asserted against the real platform, not a mock:
 *  1. `FileProvider` resolves the archive and the send intent really grants a
 *     read permission to the receiving UID (the old intent relied solely on
 *     `EXTRA_STREAM` + a flag and dropped the grant at the share sheet).
 *  2. The archive is a valid, readable ZIP whose entries are the allow-listed
 *     JSONL streams (a truncated/empty zip is what made exports useless).
 *  3. Manager export events keep their own code instead of collapsing into
 *     `MANAGER_ACTION`, so a failed delivery is diagnosable from the next export.
 *
 * Runs inside the instrumentation process (package = target package), so the
 * FileProvider authority and URI grants are the ones used at runtime. Everything
 * is written under a throw-away cache root and removed afterwards.
 */
internal object DiagnosticsExportChecks {
    fun run(realTarget: Context) {
        val root = File(realTarget.cacheDir, "diagnostics-export-${UUID.randomUUID()}").apply { mkdirs() }
        val target = object : ContextWrapper(realTarget) {
            override fun getFilesDir(): File = root
            // packageZip writes into cacheDir/diagnostics/share, which the manifest
            // FileProvider maps to this same directory.
            override fun getCacheDir(): File = realTarget.cacheDir
        }
        var exported: File? = null
        try {
            val session = UUID.randomUUID().toString()
            val hook = JSONObject().put("schema", 1).put("timeUtcMs", System.currentTimeMillis())
                .put("session", session).put("seq", 1).put("level", "INFO")
                .put("component", "hook.entry").put("code", "MODULE_LOADED")
            check(DiagnosticsStore.acceptHookBatch(target, JSONArray().put(hook).toString()) == 1L) {
                "hook record was rejected"
            }
            DiagnosticsStore.appendAssistant(target, "INFO", "manager", "TEST_EVENT")
            DiagnosticsStore.appendAssistant(target, "INFO", "manager", "EXPORT_STARTED")
            DiagnosticsStore.appendAssistant(target, "ERROR", "manager", "EXPORT_FAILED")

            // 1. Export codes survive the allow-list on write and after a read round trip.
            val codes = DiagnosticsStore.read(target, DiagnosticKind.Assistant).map { it.code }
            check("EXPORT_STARTED" in codes) { "EXPORT_STARTED collapsed: $codes" }
            check("EXPORT_FAILED" in codes) { "EXPORT_FAILED collapsed: $codes" }

            // 2. Package real records and read the archive back through the same
            //    stream a receiving app would open.
            val zip = DiagnosticsStore.packageZip(target, 24 * 60 * 60 * 1000L)
            exported = zip
            check(zip.isFile && zip.length() > 0) { "empty archive" }
            check(zip.parentFile?.canonicalFile == File(realTarget.cacheDir, "diagnostics/share").canonicalFile) {
                "archive escaped the provider root: ${zip.parentFile}"
            }
            ZipFile(zip).use { archive ->
                val names = archive.entries().toList().map { it.name }
                check("manifest.json" in names && "hook.jsonl" in names &&
                    "assistant.jsonl" in names && "README.txt" in names) { "entries: $names" }
                val manifest = JSONObject(archive.getInputStream(archive.getEntry("manifest.json"))
                    .bufferedReader().readText())
                check(manifest.getInt("hookEntries") == 1) { "hook count ${manifest.getInt("hookEntries")}" }
                check(manifest.getInt("assistantEntries") >= 2) { "assistant count" }
                val hookLine = JSONObject(archive.getInputStream(archive.getEntry("hook.jsonl"))
                    .bufferedReader().readLine())
                check(hookLine.getString("code") == "MODULE_LOADED") { "hook line lost its code" }
            }

            // 3. The send intent resolves to a file URI inside the provider root and
            //    carries the read grant on the URI itself (clip data), not only in extras.
            //    The authority must be backed by its own FileProvider component; two
            //    manifest entries naming one class share a component and one path config.
            val provider = target.packageManager
                .resolveContentProvider("${target.packageName}.diagnostics", 0)
            check(provider?.name == DiagnosticsFileProvider::class.java.name) {
                "diagnostics authority resolved to ${provider?.name}"
            }
            val intent = DiagnosticsStore.shareIntent(target, zip)
            check(intent.action == Intent.ACTION_SEND && intent.type == "application/zip")
            check(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "missing read flag" }
            val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            check(stream != null) { "EXTRA_STREAM is not a Uri" }
            check(stream!!.scheme == "content" &&
                stream.authority == "${target.packageName}.diagnostics") { "authority $stream" }
            val clip: ClipData? = intent.clipData
            check(clip != null && clip.itemCount == 1 && clip.getItemAt(0).uri == stream) {
                "share sheet would re-send without the granted URI"
            }
            // Round trip the intent the way the chooser forwards it: a receiver that
            // sees only clip data must still find the stream.
            val forwarded = Intent(intent)
            check((forwarded.clipData ?: intent.clipData) != null)
            //    Read the archive back through ContentResolver the way the receiving app
            //    would, and require a complete ZIP — an opener that answered but served
            //    nothing is exactly the reported failure.
            (target.contentResolver.openInputStream(stream)
                ?: error("provider refused to open $stream")).use { input ->
                check(input.read() == 'P'.code) { "provider did not serve the ZIP header" }
            }
            DiagnosticsStore.verifyReadableArchive(target, stream)
            (target.contentResolver.openInputStream(stream)
                ?: error("provider refused to reopen $stream")).use { input ->
                // ZipInputStream, not ZipFile: the provider hands out a stream and
                // ZipFile only accepts a path. Every entry must be reachable this way.
                ZipInputStream(input).use { served ->
                    val names = generateSequence { served.nextEntry }.map { it.name }.toList()
                    check("manifest.json" in names && "hook.jsonl" in names) { "served entries: $names" }
                }
            }

            // 4. Delivery bookkeeping must name the failure instead of folding into
            //    MANAGER_ACTION (what made the report unactionable).
            val resolved = DiagnosticsStore.read(target, DiagnosticKind.Assistant)
                .filter { it.code.startsWith("EXPORT") }.map { it.code }.distinct()
            check(resolved.containsAll(listOf("EXPORT_STARTED", "EXPORT_FAILED"))) { "$resolved" }

            // 5. A missing share target is detected before packaging instead of after.
            check(DiagnosticsStore.canShare(target)) {
                "no application/zip handler on this device; share fallback covers it"
            }
        } finally {
            exported?.delete()
            root.deleteRecursively()
        }
    }
}
