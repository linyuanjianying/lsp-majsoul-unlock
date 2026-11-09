package com.yefeng.majmax.hookprobe.manager

import org.json.JSONArray
import org.json.JSONObject

/** Runs only synthetic commands with an injected clock; no game input or connection. */
internal object TelemetryRegressionChecks {
    fun run() {
        var time = 0L
        val logs = mutableListOf<String>()
        val events = mutableListOf<AutoDiscardEvent>()
        val gate = AutoDiscardController({ logs.add(it) }, { time }, { 0L }, event = { events.add(it) })
        fun live() = JSONObject("""{"status":"live","canAct":true,"connection":"7","revision":1,
          "sourceGeneration":3,"sourceSequence":8,"seat":0,"players":4,"hand":["5mr"],
          "legalOperations":[{"type":1,"combination":[]}],"autoContext":{"riichi":false},
          "recommendations":[{"kind":"discard","tile":"5mr","tsumogiri":false}]}""")
        fun ack(id: String, state: String) = JSONArray().put(JSONObject().put("id", id)
            .put("state", state).put("reason", "{}")).toString()
        gate.observe(live()); gate.toggle()
        val id = JSONObject(checkNotNull(gate.poll(null))).getString("id")
        repeat(10) {
            time += 250
            for (state in listOf("hand_coords", "hand_geometry", "button_coords")) {
                check(JSONObject(checkNotNull(gate.poll(ack("", state)))).getString("id") == id)
                check(JSONObject(checkNotNull(gate.poll(ack(id, state)))).getString("id") == id)
            }
        }
        check(gate.isEnabled())
        check(events.none { it.code == "GAME_REJECTED" || it.code == "ACK_UNMATCHED" })
        check(logs.none { it.startsWith("WAITING") })
        gate.poll(ack(id, "submitted")); gate.poll(ack(id, "accepted"))
        check(events.count { it.code == "GAME_SUBMITTED" && it.ackId == id } == 1)
        check(events.count { it.code == "SERVER_ACCEPTED" && it.ackId == id } == 1)
        gate.pause("test")
        val count = events.size
        repeat(10) { gate.poll(ack("", "hand_geometry")); gate.poll(ack("", "button_coords")) }
        check(events.size == count && !gate.isEnabled())
        // Enabled without a pending command must not interpret telemetry as a failure.
        gate.toggle()
        val before = logs.size
        gate.poll(ack("", "hand_geometry")); gate.poll(ack("", "button_coords"))
        check(logs.drop(before).none { it.startsWith("WAITING") })
        check(events.none { it.code == "GAME_REJECTED" || it.code == "ACK_UNMATCHED" })
    }
}
