package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.UUID

/**
 * Device-side checks for the autoplay ACK diagnostics.
 *
 * The pure controller behaviour lives in [AutoAckChainProbe] (JVM). What is left to
 * a device is the part that needs Android's real `org.json` and a writable
 * directory: the store must keep a long, rendered ACK `reason` intact (the reported
 * package lost it to a 64-character cap), keep the ACK-chain stage, and never turn a
 * controller id or a repeat code into something else. No game process, socket or
 * input is involved.
 *
 * The sample strings are exactly what [AutoAckDetails.render] produces for the two
 * adapter states, so a change in the renderer or the store's alphabet fails here.
 */
internal object AutoAckDiagnosticsChecks {
    fun run(realTarget: Context) {
        val root = File(realTarget.cacheDir, "auto-ack-diagnostics-${UUID.randomUUID()}").apply { mkdirs() }
        val target = object : ContextWrapper(realTarget) {
            override fun getFilesDir(): File = root
        }
        try {
            DiagnosticsStore.clear(target)

            // 1. A rendered hand_geometry rejection is stored in full: the camera
            //    fingerprint is what identifies the frame, and 54 characters were not
            //    enough for it.
            val rendered = AutoAckDetails.render("hand_geometry", """
                {"screenW":2414,"screenH":1080,"handpai":13,"concealed":0,"invalid":13,
                 "minNx":2,"maxNx":-2,"cam":"camera","ortho":false,
                 "cams":"UICamera|ortho|0/13|-273.19,-2.00,0.88,0.92|0.92+camera|ortho|13/13|0.21,0.69,0.88,0.92|0.92"}
            """.trimIndent())
            check(rendered.length > 120) { "sample must exceed the old 54 character cap: $rendered" }
            check(rendered.contains("concealed=0")) { rendered }
            val id = "0f8f3f80-8d0f-4c95-9d3e-8f3a1c9b7e21-12"
            DiagnosticsStore.appendAutoplay(target, "GAME_REJECTED", "command", rendered, id)
            val rejection = DiagnosticsStore.read(target, DiagnosticKind.Assistant).last()
            check(rejection.code == "GAME_REJECTED") { rejection.code }
            check(rejection.fields.optString("stage") == "command") { rejection.fields.toString() }
            // The id must survive whole (UUID + sequence), not be cut at 40 characters.
            check(rejection.fields.optString("ack_id") == id) {
                "ack_id was stored as ${rejection.fields.optString("ack_id")}"
            }
            // The reason may lose characters the store's alphabet forbids, but it must
            // keep the fields that identify the frame.
            val stored = rejection.fields.optString("reason")
            check(stored.contains("concealed=0")) { stored }
            check(stored.contains("camera|ortho|13/13")) { stored }

            // 2. The stage of an ACK chain link is preserved, and an empty detail does
            //    not create a blank reason.
            DiagnosticsStore.appendAutoplay(target, "SERVER_ACCEPTED", "accepted", "", id)
            val accepted = DiagnosticsStore.read(target, DiagnosticKind.Assistant).last()
            check(accepted.code == "SERVER_ACCEPTED") { accepted.code }
            check(accepted.fields.optString("stage") == "accepted") { accepted.fields.toString() }
            check(!accepted.fields.has("reason")) { "an empty detail must not create a blank reason" }

            // 3. Repeat codes survive the code allow-list instead of collapsing into
            //    UNKNOWN_EVENT.
            DiagnosticsStore.appendAutoplay(target, "GAME_REJECTED_REPEAT", "command", "repeats=7")
            val codes = DiagnosticsStore.read(target, DiagnosticKind.Assistant).map { it.code }
            check("GAME_REJECTED_REPEAT" in codes) { codes.toString() }
            check("UNKNOWN_EVENT" !in codes) { codes.toString() }

            // 4. The allow-list still filters: a hostile reason is length-capped and
            //    stripped of characters the log alphabet forbids.
            val hostile = "x".repeat(700) + "\u0000<script>"
            DiagnosticsStore.appendAutoplay(target, "ACK_UNMATCHED", "ack_no_id", hostile)
            val sanitized = DiagnosticsStore.read(target, DiagnosticKind.Assistant).last()
                .fields.optString("reason")
            check(sanitized.length <= 512) { "reason grew to ${sanitized.length}" }
            check(!sanitized.contains("<")) { sanitized }
            check(!sanitized.contains("\u0000")) { "control characters must not survive" }
        } finally {
            runCatching { root.deleteRecursively() }
        }
    }
}
