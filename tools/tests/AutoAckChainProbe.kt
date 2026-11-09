// JVM-only regression probe for the autoplay ACK chain and its diagnostics.
//
// Compiles the production sources it exercises together with tools/tests/jvm-shims
// (android.os.SystemClock, kotlinx.coroutines.flow and a real org.json), which sort
// first on the runtime classpath and never reach the APK. No device, socket, Context
// or game action is involved.
//
// The root cause this probe pins down first: `hand_geometry` and `button_coords` are
// COORDINATE TELEMETRY the adapter emits on every capture tick with an empty id, and
// AiOverlayService.pullAuto parses them on its own. They used to fall through the
// controller's `when` into the rejection arm, which is where the 5604 "rejected"
// records of the reported package came from (5311 hand_geometry + 289 button_coords).
// They must never be classified as a command verdict.
package com.yefeng.majmax.hookprobe.manager

import org.json.JSONArray
import org.json.JSONObject

private var checks = 0
private val failures = ArrayList<String>()

private fun check(condition: Boolean, message: String = "check failed") {
    checks++
    if (!condition) failures.add(message)
}

private fun checkEquals(expected: Any?, actual: Any?, what: String) {
    checks++
    if (expected != actual) failures.add("$what: expected <$expected> got <$actual>")
}

private fun scenario(name: String, body: () -> Unit) {
    try {
        body()
    } catch (t: Throwable) {
        failures.add("$name threw ${t.javaClass.simpleName}: ${t.message}")
    }
}

/** Mirrors the service: `log` for non-structured lines, `event` for structured ones. */
private class Recorder {
    val logLines = ArrayList<String>()
    val events = ArrayList<AutoDiscardEvent>()
    var time: Long = 0L

    fun gate(enabled: Boolean = false, structured: Boolean = true): AutoDiscardController {
        val clock: () -> Long = { this.time }
        return if (structured) {
            AutoDiscardController({ logLines.add(it) }, clock, { 0L },
                initialEnabled = enabled, event = { events.add(it) })
        } else {
            AutoDiscardController({ logLines.add(it) }, clock, { 0L }, initialEnabled = enabled)
        }
    }

    fun records(): List<String> = logLines.map { "log:$it" } + events.map { "event:${it.code}" }
    fun rejections(): List<AutoDiscardEvent> = events.filter {
        it.code == "GAME_REJECTED" || it.code == "GAME_REJECTED_REPEAT"
    }
    fun waiting(): List<String> = logLines.filter { it.startsWith("WAITING") }
}

private fun live(revision: Int = 1, hand: String = "5mr", kind: String = "discard"): JSONObject =
    JSONObject(
        """{"status":"live","canAct":true,"connection":"7","revision":$revision,
            "sourceGeneration":3,"sourceSequence":${7 + revision},"step":4,"seat":0,"players":4,
            "hand":["$hand"],
            "legalOperations":[{"type":1,"combination":[]}],
            "autoContext":{"riichi":false,"phase":"wait_act","dealer":0},
            "recommendations":[{"kind":"$kind","tile":"$hand","tsumogiri":false}]}""")

private fun ack(id: String, state: String, reason: String = ""): String {
    val row = JSONObject().put("id", id).put("state", state).put("reason", reason)
    return JSONArray().put(row).toString()
}

/** Exact shape of `reportHandCoords`'s `hand_geometry` payload. */
private val handGeometry = JSONObject()
    .put("screenW", 2414).put("screenH", 1080).put("handpai", 13).put("concealed", 0)
    .put("open", 0).put("baida", 0).put("invalid", 13).put("behind", 0)
    .put("minNx", 2).put("maxNx", -2).put("minNy", 2).put("maxNy", -2)
    .put("cam", "").put("ortho", false)
    .put("cams", "UICamera|ortho|0/13|-273.19,-2.00,0.88,0.92|0.92+" +
        "camera|ortho|13/13|0.21,0.69,0.88,0.92|0.92")
    .put("tiles", "")
    .toString()

/** Exact shape of `reportButtonCoords` when no button is visible. */
private val buttonCoords = JSONObject()
    .put("screenW", 2414).put("screenH", 1080)
    .put("buttons", JSONArray())
    .toString()

/** A telemetry tick: both coordinate rows the adapter sends before the command frame. */
private fun telemetryTick(): String =
    JSONArray().put(JSONObject().put("id", "").put("state", "hand_geometry").put("reason", handGeometry))
        .put(JSONObject().put("id", "").put("state", "button_coords").put("reason", buttonCoords))
        .toString()

fun runAutoAckChainProbe() {
    // ---------------------------------------------------------------------------------
    // 1. The root cause: coordinate telemetry is never a command verdict.
    // ---------------------------------------------------------------------------------
    scenario("armed with no pending command: telemetry writes no rejection and no WAITING") {
        val rec = Recorder()
        val gate = rec.gate()
        gate.observe(live())
        gate.toggle()
        gate.poll(null) // arm, get the command
        // Make the command pending-but-travelling: submitted clears the arming gate.
        val id = JSONObject(checkNotNull(gate.poll(null))).getString("id")
        gate.poll(ack(id, "submitted"))
        gate.observe(live().put("input", JSONObject(
            """{"method":".lq.FastTest.inputOperation","payload":{"type":1,"tile":"0m","moqie":false}}""")))
        // Now every following telemetry tick must be inert.
        val before = rec.records().size
        repeat(20) {
            rec.time += 250
            gate.poll(telemetryTick())
            checkEquals(0, gate.lastAckTally().written, "telemetry wrote a record")
        }
        checkEquals(before, rec.records().size, "telemetry produced records: ${rec.records().drop(before)}")
        checkEquals(0, rec.rejections().size, "telemetry must not be a rejection")
        checkEquals(0, rec.waiting().size, "telemetry must not suspend the mode")
        check(gate.isEnabled()) { "telemetry must not disable the mode" }
    }

    scenario("telemetry while a command is pending keeps that command and its id") {
        val rec = Recorder()
        val gate = rec.gate()
        gate.observe(live())
        gate.toggle()
        val command = JSONObject(checkNotNull(gate.poll(null)))
        val id = command.getString("id")
        // Pending, not yet submitted: this is the ACK_UNMATCHED path in the old code.
        repeat(10) {
            rec.time += 250
            gate.poll(telemetryTick())
            checkEquals(0, gate.lastAckTally().written, "telemetry wrote a record")
        }
        checkEquals(0, rec.rejections().size, "telemetry must not be a rejection")
        checkEquals(0, rec.waiting().size, "telemetry must not suspend the pending command")
        // The same command is still the one to send, byte for byte.
        val resent = JSONObject(checkNotNull(gate.poll(null)))
        checkEquals(id, resent.getString("id"), "pending command id changed")
        // And the real network acks still drive the chain for that id.
        gate.poll(ack(id, "submitted"))
        gate.poll(ack(id, "accepted"))
        val stages = rec.events.associate { it.code to it.stage }
        checkEquals("submitted", stages["GAME_SUBMITTED"], "submitted stage")
        checkEquals("accepted", stages["SERVER_ACCEPTED"], "accepted stage")
        check(rec.events.filter { it.code == "GAME_SUBMITTED" || it.code == "SERVER_ACCEPTED" }
            .all { it.ackId == id }) { "acks must carry the pending id" }
        gate.observe(live().put("input", JSONObject(
            """{"method":".lq.FastTest.inputOperation","payload":{"type":1,"tile":"0m","moqie":false}}""")))
        checkEquals("uplink", rec.events.last { it.code == "UPLINK_CONFIRMED" }.stage, "uplink stage")
    }

    scenario("telemetry with an id is still telemetry") {
        val rec = Recorder()
        val gate = rec.gate()
        gate.observe(live())
        gate.toggle()
        val id = JSONObject(checkNotNull(gate.poll(null))).getString("id")
        rec.time += 250
        gate.poll(ack(id, "hand_geometry", handGeometry))
        rec.time += 250
        gate.poll(ack(id, "button_coords", buttonCoords))
        checkEquals(1, rec.events.count { it.code == "QUEUED" }, "no record besides the queue")
        checkEquals(0, rec.rejections().size, "telemetry with an id must not be a rejection")
        gate.pause("test")
        checkEquals(1, rec.events.count { it.code == "QUEUED" }, "pause wrote nothing either")
        checkEquals(0, rec.rejections().size, "telemetry with an id must not be a rejection")
    }

    scenario("after pause the same telemetry writes nothing at all") {
        val rec = Recorder()
        val gate = rec.gate()
        gate.observe(live())
        gate.toggle()
        gate.poll(null)
        gate.pause("test")
        val mark = rec.records().size
        repeat(8) {
            rec.time += 250
            gate.poll(telemetryTick())
            gate.poll(ack("", "hand_geometry", handGeometry))
        }
        checkEquals(mark, rec.records().size, "a paused controller wrote ${rec.records().drop(mark)}")
        checkEquals(0, rec.rejections().size, "paused: no rejection records")
        checkEquals(0, rec.waiting().size, "paused: no WAITING")
    }

    // ---------------------------------------------------------------------------------
    // 2. Single output channel.
    // ---------------------------------------------------------------------------------
    scenario("structured events are written once and never also through log") {
        val rec = Recorder()
        val gate = rec.gate()
        gate.observe(live())
        gate.toggle()
        val command = JSONObject(checkNotNull(gate.poll(null)))
        val id = command.getString("id")
        gate.observe(live().put("input", JSONObject(
            """{"method":".lq.FastTest.inputOperation","payload":{"type":1,"tile":"0m","moqie":false}}""")))
        gate.poll(ack(id, "submitted"))
        gate.poll(ack(id, "accepted"))
        checkEquals(listOf("QUEUED", "UPLINK_CONFIRMED", "GAME_SUBMITTED", "SERVER_ACCEPTED"),
            rec.events.map { it.code }, "structured codes")
        check(rec.logLines.none { it.startsWith("QUEUED") || it.startsWith("GAME_SUBMITTED") ||
            it.startsWith("SERVER_ACCEPTED") || it.startsWith("UPLINK_CONFIRMED") }) {
            "structured events leaked into the legacy log: ${rec.logLines}"
        }
        check(rec.logLines.contains("ENABLED")) { rec.logLines.toString() }
        check(rec.events.none { it.code == "ENABLED" }) { "ENABLED must stay on log only" }
        val delivered = rec.events.groupingBy { it.code }.eachCount()
        check(delivered.all { it.value == 1 }) { "duplicate delivery: $delivered" }
    }

    scenario("without a structured sink every event still reaches log") {
        val rec = Recorder()
        val gate = rec.gate(structured = false)
        gate.observe(live())
        gate.toggle()
        gate.poll(null)
        check(rec.logLines.any { it.startsWith("QUEUED: kind=discard") }) { rec.logLines.toString() }
        check(rec.events.isEmpty()) { rec.events.toString() }
    }

    // ---------------------------------------------------------------------------------
    // 3. Real rejections: written once per state, folded by (stage, code, id, detail).
    // ---------------------------------------------------------------------------------
    scenario("a real rejection verdict is written once and suspends the window") {
        val rec = Recorder()
        val gate = rec.gate()
        gate.observe(live())
        gate.toggle()
        val id = JSONObject(checkNotNull(gate.poll(null))).getString("id")
        rec.time += 250
        gate.poll(ack(id, "stale"))
        val rejected = rec.rejections()
        checkEquals(1, rejected.size, "one rejection record")
        checkEquals("command", rejected.single().stage, "rejection stage")
        checkEquals(id, rejected.single().ackId, "rejection id")
        check(rec.waiting().isNotEmpty()) { "a real rejection suspends the window" }
        check(gate.isEnabled()) { "a rejection keeps the opt-in" }
    }

    scenario("two different rejections of one command are both written") {
        val rec = Recorder()
        val gate = rec.gate()
        gate.observe(live())
        gate.toggle()
        val id = JSONObject(checkNotNull(gate.poll(null))).getString("id")
        rec.time += 200
        gate.poll(ack(id, "round_mismatch", "seat 1/1 wind 1/1 ju 1/1 ben 0/0"))
        gate.observe(live(2))
        val id2 = JSONObject(checkNotNull(gate.poll(null))).getString("id")
        rec.time += 200
        gate.poll(ack(id2, "illegal_tile"))
        val details = rec.rejections().map { it.detail }
        checkEquals(2, details.size, "two distinct rejections: $details")
        check(details[0].startsWith("seat 1/1")) { details[0] }
        check(details[1].startsWith("illegal_tile")) { details[1] }
        check(rec.rejections().map { it.ackId } == listOf(id, id2)) { rec.rejections().map { it.ackId }.toString() }
    }

    scenario("a repeating verdict folds on the 2 s window without waiting for pause") {
        val rec = Recorder()
        val gate = rec.gate()
        gate.observe(live())
        gate.toggle()
        var reported = 0
        var tick = 0L
        // 20 s of 250 ms ticks. Each verdict suspends the window, so a fresh command is
        // armed again; the *same* (state, detail) key keeps folding across windows.
        while (tick < 20_000) {
            tick += 250
            rec.time = tick
            var command = gate.poll(null)
            if (command == null) {
                gate.observe(live((tick / 250).toInt() + 1))
                command = gate.poll(null)
            }
            if (command != null) {
                gate.poll(ack(JSONObject(command).getString("id"), "stale"))
                reported++
            }
        }
        check(reported > 1) { "the probe must report more than one verdict ($reported)" }
        val summaries = rec.events.filter { it.code == "GAME_REJECTED_REPEAT" }
        check(summaries.isNotEmpty()) {
            "the window must expire on its own (reported=$reported): ${rec.events.map { it.code }}"
        }
    }

    scenario("repeat counts are exact and never include the first sighting") {
        val rec = Recorder()
        val gate = rec.gate()
        gate.observe(live())
        gate.toggle()
        var reported = 0
        var tick = 0L
        while (tick < 3_000) {
            tick += 250
            rec.time = tick
            var command = gate.poll(null)
            if (command == null) {
                gate.observe(live((tick / 250).toInt() + 1))
                command = gate.poll(null)
            }
            if (command != null) {
                gate.poll(ack(JSONObject(command).getString("id"), "stale"))
                reported++
            }
        }
        gate.pause("test")
        val written = rec.rejections().count { it.code == "GAME_REJECTED" }
        val folded = rec.events.filter { it.code == "GAME_REJECTED_REPEAT" }
            .sumOf { it.detail.removePrefix("repeats=").toInt() }
        checkEquals(written + folded, reported,
            "every row accounted for (written=$written folded=$folded of $reported)")
        if (reported > 1) check(folded > 0) { "repeats must be counted inside the window" }
    }

    scenario("the fold map is bounded") {
        val rec = Recorder()
        val gate = rec.gate()
        gate.observe(live())
        gate.toggle()
        var tick = 0L
        var distinct = 0
        val seen = HashSet<String>()
        // Distinct details, one per tick, inside one window: the map must flush at
        // FOLD_CAPACITY instead of growing without bound.
        while (tick < 20_000) {
            tick += 250
            rec.time = tick
            var command = gate.poll(null)
            if (command == null) {
                gate.observe(live((tick / 250).toInt() + 1))
                command = gate.poll(null)
            }
            if (command != null) {
                val detail = "n=$distinct"
                if (seen.add(detail)) distinct++
                gate.poll(ack(JSONObject(command).getString("id"), "unknown_state", detail))
            }
        }
        check(AutoDiscardController.FOLD_CAPACITY == 64) { "capacity" }
        check(distinct >= 64) { "the probe must exceed the capacity ($distinct)" }
        // Every distinct key is written when it is first seen.
        check(rec.events.count { it.code == "GAME_REJECTED" } >= 64) {
            "every distinct row written: ${rec.events.count { it.code == "GAME_REJECTED" }}"
        }
    }

    // ---------------------------------------------------------------------------------
    // 4. Renderer: real fields kept, nothing else copied.
    // ---------------------------------------------------------------------------------
    scenario("hand_geometry keeps concealed and the camera fingerprint") {
        val rendered = AutoAckDetails.render("hand_geometry", handGeometry)
        check(rendered.startsWith("hand_geometry ")) { rendered }
        check(rendered.contains("concealed=0")) { rendered }
        check(rendered.contains("handpai=13")) { rendered }
        check(rendered.contains("invalid=13")) { rendered }
        check(rendered.contains("cams=UICamera|ortho|0/13")) { rendered }
        check(rendered.contains("camera|ortho|13/13|0.21,0.69,0.88,0.92|0.92")) {
            "the 13/13 camera must survive intact: $rendered"
        }
        check(!rendered.contains("{")) { "no raw JSON: $rendered" }
        check(rendered.length < 400) { "bounded: ${rendered.length}" }
    }

    scenario("button points keep their keys and stay bounded") {
        val buttons = JSONArray()
        for (key in listOf("kan", "pass", "hora", "pon", "chi", "riichi", "zimo", "babei", "abort",
                "extra1", "extra2", "extra3", "extra4", "extra5", "extra6", "extra7", "extra8")) {
            buttons.put(JSONObject().put("key", key).put("nx", 0.5).put("ny", 0.75))
        }
        val rendered = AutoAckDetails.render("button_coords",
            JSONObject().put("screenW", 2414).put("screenH", 1080).put("buttons", buttons).toString())
        check(rendered.contains("kan:0.5,0.75")) { rendered }
        check(rendered.contains("n=17")) { "the cap must report the real count: $rendered" }
    }

    scenario("16 cameras survive; a raw payload never does") {
        val cams = (1..16).joinToString("+") { "cam$it|ortho|$it/13|0.21,0.69,0.88,0.92|0.92" }
        checkEquals(16, AutoAckDetails.cameras(cams).split('+').size, "all sixteen cameras kept")
        val hostile = JSONObject().put("account", "player@example.com").put("token", "abcdef0123456789")
            .put("cam", "camera").toString()
        val safe = AutoAckDetails.render("hand_geometry", hostile)
        check(!safe.contains("example.com")) { safe }
        check(!safe.contains("abcdef0123456789")) { safe }
        check(!safe.contains("account")) { safe }
        checkEquals("hand_geometry cam=camera", safe, "only the allow-listed key survives")
    }

    scenario("prose rejection states stay readable") {
        checkEquals("seat 1/1 wind 1/1 ju 1/1 ben 0/0",
            AutoAckDetails.sanitizeText("seat 1/1 wind 1/1 ju 1/1 ben 0/0"), "slashes and digits kept")
        val injected = """{"account":"x"}"""
        check(!AutoAckDetails.sanitizeText(injected).contains("account")) { "punctuated payload fingerprinted" }
    }

    scenario("numbers are bounded") {
        checkEquals("0.21375", AutoAckDetails.format(0.2137499999), "6 significant digits")
        checkEquals("2414", AutoAckDetails.format(2414.0), "integer")
        checkEquals("0", AutoAckDetails.format(0.0), "zero")
    }

    if (failures.isEmpty()) {
        println("PASS controller and ACK-chain probe: $checks checks")
    } else {
        println("FAIL $checks checks, ${failures.size} failures")
        failures.take(20).forEach { println("  - $it") }
        throw IllegalStateException("auto ack chain probe failed")
    }
}

fun main() = runAutoAckChainProbe()
