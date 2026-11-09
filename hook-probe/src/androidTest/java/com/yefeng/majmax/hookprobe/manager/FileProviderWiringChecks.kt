package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Regression checks for the v0.14.20 "log export cannot be shared" report whose root
 * cause was provider wiring, not the share intent.
 *
 * On a real device `dumpsys activity providers` showed a *single* registered
 * `androidx.core.content.FileProvider` component carrying both authorities
 * (`<id>.updates` and `<id>.diagnostics`), because two manifest entries named the same
 * class. The framework keys the provider instance by component, so one entry's
 * `PathStrategy` answered both authorities. Keeping two entries in the manifest is
 * therefore *not* sufficient; the component names have to differ, and the only way to
 * know is to ask `PackageManager.resolveContentProvider` and then actually open a URI.
 *
 * Asserted against the real platform, not a mock:
 *  1. Both authorities resolve to non-null providers with *different* component names,
 *     each a FileProvider subclass, none of them the shared base class.
 *  2. A diagnostics archive and an update package both become `content://` URIs that
 *     `ContentResolver` opens, and the bytes served are the ZIP header and a valid zip.
 *  3. The providers are not interchangeable: a file outside each provider's configured
 *     path fails to map, which proves the two authorities no longer share paths.
 *  4. A truncated archive fails the pre-share check instead of reaching the chooser.
 */
internal object FileProviderWiringChecks {
    fun run(context: Context) {
        val root = File(context.cacheDir, "provider-checks-${UUID.randomUUID()}").apply { mkdirs() }
        val created = mutableListOf<File>()
        try {
            val updatesAuthority = "${context.packageName}.updates"
            val diagnosticsAuthority = "${context.packageName}.diagnostics"
            val pm = context.packageManager

            // 1. Distinct components for distinct authorities.
            val updates = pm.resolveContentProvider(updatesAuthority, 0)
            val diagnostics = pm.resolveContentProvider(diagnosticsAuthority, 0)
            check(updates != null && diagnostics != null) { "provider missing: $updates / $diagnostics" }
            check(updates!!.name != diagnostics!!.name) {
                "both authorities share one component ${updates.name}: the framework will serve " +
                    "one entry's paths for the other"
            }
            check(updates.name != FileProvider::class.java.name &&
                diagnostics.name != FileProvider::class.java.name) {
                "authority still bound to the shared base provider: ${updates.name} / ${diagnostics.name}"
            }
            check(updates.grantUriPermissions && diagnostics.grantUriPermissions) {
                "providers must keep grantUriPermissions for the chooser to forward the grant"
            }
            check(updates.name == UpdatesFileProvider::class.java.name) { "updates -> ${updates.name}" }
            check(diagnostics.name == DiagnosticsFileProvider::class.java.name) {
                "diagnostics -> ${diagnostics.name}"
            }
            check(authorities(updates) == setOf(updatesAuthority)) { "updates authority list" }
            check(authorities(diagnostics) == setOf(diagnosticsAuthority)) { "diagnostics authority list" }

            // Do all work on a throw-away copy: packageZip() would otherwise create and
            // rotate the user's real diagnostics store.
            val store = File(root, "store").apply { mkdirs() }
            val target = object : android.content.ContextWrapper(context) {
                override fun getFilesDir(): File = store
                override fun getCacheDir(): File = context.cacheDir
            }

            // 2. Real archives/files become readable content URIs.
            val session = UUID.randomUUID().toString()
            val hook = JSONObject().put("schema", 1).put("timeUtcMs", System.currentTimeMillis())
                .put("session", session).put("seq", 1).put("level", "INFO")
                .put("component", "hook.entry").put("code", "MODULE_LOADED")
            check(DiagnosticsStore.acceptHookBatch(target, JSONArray().put(hook).toString()) == 1L)
            val archive = DiagnosticsStore.packageZip(target, 60 * 60 * 1000L)
            created += archive
            check(archive.isFile && archive.length() > 0) { "archive not written" }

            val intent = DiagnosticsStore.shareIntent(target, archive)
            check(intent.type == "application/zip")
            check(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "missing read flag" }
            val stream: Uri = checkNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM)) {
                "EXTRA_STREAM is not a Uri"
            }
            check(stream.authority == diagnosticsAuthority) { "share URI authority ${stream.authority}" }
            check(intent.clipData?.getItemAt(0)?.uri == stream) { "clip data lost the URI" }
            // NOTE: parenthesised — `x?.use { } ?: error()` parses as
            // `x?. (use { } ?: error())`, so a null stream would silently pass.
            (target.contentResolver.openInputStream(stream) ?: error(
                "diagnostics provider refused $stream")).use { input ->
                check(input.read() == 0x50) { "diagnostics provider did not serve the ZIP header" }
            }
            // The same bytes must still be a well-formed archive when read through the
            // provider (a FileProvider that served a truncated stream would pass the
            // one-byte probe above but fail here).
            val copy = File(root, archive.name)
            (target.contentResolver.openInputStream(stream)
                ?: error("diagnostics provider refused a second open of $stream")).use { input ->
                copy.outputStream().use(input::copyTo)
            }
            ZipFile(copy).use { zip ->
                val names = zip.entries().toList().map { it.name }
                check("manifest.json" in names && "hook.jsonl" in names) { "served entries: $names" }
            }

            // Updates use their own authority and their own paths directory.
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val packageFile = File(updateDir, "provider-check-${UUID.randomUUID()}.apk").apply { writeText("PK\u0003\u0004 not a real apk") }
            created += packageFile
            val installUri = ApkUpdates.installIntent(target, packageFile).data
            check(installUri != null && installUri.authority == updatesAuthority) {
                "install intent URI ${installUri?.authority}"
            }
            (target.contentResolver.openInputStream(checkNotNull(installUri))
                ?: error("updates provider refused $installUri")).use { input ->
                check(input.read() == 0x50) { "updates provider did not serve the package header" }
            }

            // 3. The two authorities no longer answer with each other's paths: a file
            //    living only in diagnostics/ must not map through .updates and vice versa.
            check(runCatching { FileProvider.getUriForFile(target, updatesAuthority, archive) }.isFailure) {
                ".updates resolved a diagnostics file — paths are still shared"
            }
            check(runCatching {
                FileProvider.getUriForFile(
                    target, "${target.packageName}.diagnostics", packageFile)
            }.isFailure) { ".diagnostics resolved an update file — paths are still shared" }

            // 4. A provider that cannot serve the archive must fail before the chooser,
            //    not after the user picked a receiving app.
            //    An empty file maps fine but serves zero bytes; that must be rejected.
            val empty = File(archive.parentFile, "empty-${UUID.randomUUID()}.zip")
                .apply { writeBytes(ByteArray(0)) }
            created += empty
            val mapped = FileProvider.getUriForFile(target, diagnosticsAuthority, empty)
            check(runCatching { DiagnosticsStore.verifyReadableArchive(target, mapped) }.isFailure) {
                "empty archive passed the pre-share readability check"
            }
            //    A path outside every configured root must not map at all.
            val outside = File(store, "outside.jsonl").apply { writeText("{}\n") }
            check(runCatching { FileProvider.getUriForFile(target, diagnosticsAuthority, outside) }.isFailure) {
                "provider served a path outside its configured root"
            }
        } finally {
            created.forEach { it.delete() }
            root.deleteRecursively()
        }
    }

    private fun authorities(info: ProviderInfo): Set<String> =
        info.authority?.split(';')?.filter { it.isNotBlank() }?.toSet().orEmpty()
}
