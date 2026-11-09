package com.yefeng.majmax.hookprobe.manager

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

data class AutoDiscardStatus(val enabled: Boolean = false, val message: String = "无人值守模式已关闭")
object AutoDiscardState {
    internal val mutable = MutableStateFlow(AutoDiscardStatus())
    val state = mutable.asStateFlow()
}

/**
 * A structured autoplay event: [code] is the diagnostics vocabulary, [detail] is an
 * already-bounded rendering (never a raw adapter payload), [stage] names the ACK-chain
 * link that produced it.
 */
internal data class AutoDiscardEvent(val code: String, val detail: String = "",
    val stage: String = "", val ackId: String = "")

/**
 * One pending command, one attempt per decision window; never retries an input.
 *
 * Output is a single channel. [log] keeps its historical contract (`"CODE: detail"`,
 * split by the service on the first space) and is used for every non-structured line.
 * [event] is optional: when it is supplied it receives *structured* records only
 * (QUEUED / GAME_SUBMITTED / SERVER_ACCEPTED / UPLINK_CONFIRMED / GAME_REJECTED and
 * their folded summaries), and those are never passed to [log] as well. When it is
 * absent the fallback renders them through [log], so a caller can never lose a record.
 */
internal class AutoDiscardController(private val log: (String) -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val delayMs: () -> Long = { AutoDelayRange().randomDelayMs() },
    initialEnabled: Boolean = false, private val modeChanged: (Boolean) -> Unit = {},
    event: ((AutoDiscardEvent) -> Unit)? = null) {
    /** Where a structured event goes: the injected sink, or [log] when there is none. */
    private val emit: (AutoDiscardEvent) -> Unit = event ?: { item ->
        log(if (item.detail.isEmpty()) item.code else "${item.code}: ${item.detail}")
    }
    private var enabled = initialEnabled
    private var latest: JSONObject? = null
    private var pending: JSONObject? = null
    private var attempted = ""
    private var pendingAt = 0L
    private var notBefore = 0L
    private var serial = UUID.randomUUID().toString()
    private var count = 0L
    private var wireConfirmed = false
    private var submitted = false
    private var accepted = false
    /** Decision windows already claimed by the user; kept even when the window closed before arming. */
    private val manual = mutableSetOf<String>()
    /**
     * Command rejection rows folded per (stage, code, detail) inside the current window.
     *
     * The adapter re-sends the same row on every capture tick, and a stuck table
     * alternates rows, so keyed counters fold repeated verdicts independently. Bounded to
     * [FOLD_CAPACITY] keys; when the map is full it is flushed (one summary each) and
     * restarted, so memory cannot grow with a pathological adapter.
     */
    private val folded = LinkedHashMap<List<String>, FoldedRow>()
    /** Start of the current fold window; 0 until the first rejection. */
    private var lastFlushAt = 0L
    /** Per-poll ack accounting: seen / folded / written as a new record. */
    private var ackSeen = 0
    private var ackFolded = 0
    private var ackWritten = 0

    /** One folded signature: repeats counted since [windowStart], which a flush resets. */
    private data class FoldedRow(val stage: String, val code: String, val detail: String,
        val ackId: String, var repeats: Int, val windowStart: Long)

    /**
     * Counts one rejection row and writes it when it is new to the current window.
     *
     * The window is [REJECT_DEDUPE_MS] measured from the last [flushFolded], not from
     * the previous sighting: a stuck frame repeats every 250 ms, so a per-row
     * "last seen" timer would never expire and the counts would only surface on
     * [pause]. The first row of a window is written; every later repeat of the same
     * key is counted. Once the window ends, the next row after it starts a new window
     * and the old counts are emitted as one `GAME_REJECTED_REPEAT` per key.
     */
    private fun noteRejection(stage: String, code: String, detail: String, ackId: String) {
        val nowMs = now()
        if (lastFlushAt == 0L) lastFlushAt = nowMs
        if (nowMs - lastFlushAt > REJECT_DEDUPE_MS) flushFolded()
        // The key deliberately excludes the ack id: a verdict restated for the next
        // command carries a new id but is still the same repeating condition, and
        // including the id made every row distinct (size grew 0,1,2,…). Stage, code
        // and the rendered detail are compared as a list, never as a joined string,
        // so a detail containing a separator cannot collide with a field boundary.
        val key = listOf(stage, code, detail)
        ackSeen++
        val existing = folded[key]
        if (existing != null) {
            existing.repeats++
            ackFolded++
            return
        }
        // First sighting inside this window: one record; repeats are counted from 2.
        folded[key] = FoldedRow(stage, code, detail, ackId, 1, lastFlushAt)
        ackWritten++
        emit(AutoDiscardEvent(code, detail, stage, ackId))
        if (folded.size > FOLD_CAPACITY) flushFolded()
    }

    /**
     * Emits one `GAME_REJECTED_REPEAT` per key that repeated inside the window, then
     * starts a new window. The first row of a key is already written, so a count of
     * `n` means "n repeats after it" (`repeats = sightings - 1`).
     */
    private fun flushFolded() {
        lastFlushAt = now()
        if (folded.isEmpty()) return
        for (row in folded.values) {
            val repeats = row.repeats - 1
            if (repeats <= 0) continue
            emit(AutoDiscardEvent("GAME_REJECTED_REPEAT", "repeats=$repeats", row.stage, row.ackId))
        }
        folded.clear()
    }

    /** generation:connection:revision — the only identity a fresh decision may be matched on. */
    private fun decisionKey(source: JSONObject) =
        "${source.optLong("sourceGeneration")}:${source.optString("connection")}:${source.optLong("revision")}"

    /** A manual input claims the window it happened in, so neither observe nor offer replays it. */
    private fun markManual(source: JSONObject?) {
        if (source != null) decisionKey(source).let { manual.add(it) }
        pending = null; latest = null; accepted = false
    }

    /**
     * Detected manual operation: gives up the current decision but keeps the opt-in.
     * Nothing is sent until a brand-new decision arrives; the explicit switch and the
     * in-game toggle still go through [pause].
     */
    @Synchronized fun yieldManually(reason: String) {
        if (!enabled) return
        log("HANDOVER: $reason")
        markManual(pending ?: latest)
        if (manual.size > 64) manual.remove(manual.first())
        AutoDiscardState.mutable.value = AutoDiscardStatus(true, reason)
    }

    @Synchronized fun pause(reason: String) {
        flushFolded()
        if (enabled || pending != null) log("PAUSED: $reason")
        enabled = false; pending = null
        modeChanged(false)
        AutoDiscardState.mutable.value = AutoDiscardStatus(false, reason)
    }

    init {
        AutoDiscardState.mutable.value = AutoDiscardStatus(enabled,
            if (enabled) "无人值守模式已恢复 · 等待完整牌局同步" else "无人值守模式已关闭")
    }

    @Synchronized fun isEnabled() = enabled
    @Synchronized fun needsSync() = enabled && latest?.optString("status") != "live"
    /** Temporary loss of trustworthy state keeps the user's opt-in, but revokes the action. */
    @Synchronized fun suspend(reason: String) {
        if (enabled && (pending != null || latest != null)) log("WAITING: $reason")
        pending = null; latest = null
        AutoDiscardState.mutable.value = AutoDiscardStatus(enabled, reason)
    }

    @Synchronized fun invalidate(reason: String) {
        latest = null
        suspend(reason)
    }

    @Synchronized fun toggle() {
        if (enabled) { pause("已手动关闭无人值守模式"); return }
        val state = latest
        enabled = true
        modeChanged(true)
        AutoDiscardState.mutable.value = AutoDiscardStatus(true, "无人值守模式已开启 · 等待完整牌局同步")
        log("ENABLED")
        if (state?.optString("status") == "live" && !state.optBoolean("modelFallback")) offer(state)
    }

    /**
     * The in-game switch. Idempotent because the capsule repeats the request while it
     * polls for progress, and it never touches the service lifetime: closing the mode
     * keeps the foreground service (and its notification) alive.
     */
    @Synchronized fun setEnabled(value: Boolean) {
        if (enabled == value) return
        if (value) toggle() else pause("已在游戏内关闭无人值守模式")
    }

    @Synchronized fun observe(result: JSONObject) {
        latest = JSONObject(result.toString())
        if (!enabled) return
        if (result.optString("status") != "live" || result.optBoolean("modelFallback")) {
            suspend("等待牌局同步或模型恢复 · 无人值守模式保持开启"); return
        }
        result.optJSONObject("input")?.let { input ->
            val payload = input.optJSONObject("payload")
            val command = pending
            if (payload != null && (payload.optBoolean("auto_operation") || payload.optLong("timeuse") >= 1_000_000)) {
                suspend("游戏已自动处理超时窗口，等待下一次同步")
            } else if (command == null || payload == null || !matchesInput(command, input.optString("method"), payload)) {
                yieldManually("检测到手动操作 · 等待新的一回合")
            } else {
                wireConfirmed = true
                emit(AutoDiscardEvent("UPLINK_CONFIRMED", "", "uplink", command.optString("id")))
                AutoDiscardState.mutable.value = AutoDiscardStatus(true, "已确认游戏发送操作 · 等待下一回合")
            }
            return
        }
        val command = pending
        if (command != null) {
            if (!submitted && !wireConfirmed) {
                if (result.optLong("revision") != command.optLong("revision") ||
                    result.optLong("step") != command.optLong("step") ||
                    result.optString("connection") != command.optString("connection") ||
                    result.optLong("sourceGeneration") != command.optLong("sourceGeneration")) {
                    suspend("等待期间牌局已变化，等待最新同步")
                    latest = JSONObject(result.toString())
                    offer(result)
                    return
                }
                // Only parsed frames with unchanged decision/step renew proof.
                command.put("sourceSequence", result.optLong("sourceSequence"))
            }
            if (now() - pendingAt > 12_000) {
                suspend("操作确认超时，等待游戏重新同步")
            } else if (wireConfirmed && accepted && result.optLong("revision") != command.optLong("revision")) {
                pending = null
                offer(result)
            }
            return
        }
        offer(result)
    }

    private fun offer(result: JSONObject) {
        if (!enabled || pending != null || !result.optBoolean("canAct") || result.optBoolean("inputPending")) return
        val first = result.optJSONArray("recommendations")?.optJSONObject(0) ?: return
        // No server prompt (for example after restoration): wait for the next synchronized action.
        if (result.optJSONArray("legalOperations")?.length() == 0) return
        val key = decisionKey(result)
        if (key == attempted || key in manual || result.optLong("sourceSequence") <= 0) return
        attempted = key
        val action = prepareAction(result, first) ?: run {
            suspend("AI 动作与合法操作未能匹配，等待新的操作窗口")
            return
        }
        wireConfirmed = false; submitted = false; accepted = false
        pendingAt = now()
        val delay = delayMs()
        notBefore = pendingAt + delay
        pending = JSONObject(result.toString()).apply {
            remove("analysis"); remove("recommendations"); remove("input")
            put("id", "$serial-${++count}")
            put("action", action)
            put("delayMs", delay)
            put("gameTile", action.optString("tile")); put("tsumogiri", action.optBoolean("moqie"))
        }
        emit(AutoDiscardEvent("QUEUED", "kind=${action.optString("kind")} delayMs=$delay",
            "queued", pending!!.optString("id")))
        AutoDiscardState.mutable.value = AutoDiscardStatus(true, "随机等待 ${"%.1f".format(delay / 1000.0)} 秒 · 可随时关闭")
    }

    /** Ack rows counted by the last [poll]: seen / folded into the previous record / written. */
    internal data class AckTally(val seen: Int, val folded: Int, val written: Int)

    @Synchronized fun lastAckTally() = AckTally(ackSeen, ackFolded, ackWritten)

    @Synchronized fun poll(acknowledgements: String?): String? {
        ackSeen = 0; ackFolded = 0; ackWritten = 0
        if (acknowledgements != null) runCatching {
            val rows = org.json.JSONArray(acknowledgements)
            for (index in 0 until rows.length()) {
                val ack = rows.getJSONObject(index)
                when (ack.optString("state")) {
                    // Coordinate/geometry telemetry. The adapter emits these on every
                    // capture tick with an empty id; AiOverlayService.pullAuto already
                    // parses hand_coords / hand_geometry / button_coords itself. They are
                    // never a verdict on the pending command, so they must not reach the
                    // rejection arm (GAME_REJECTED) or suspend(); they touch no state at
                    // all, with or without an id.
                    "hand_coords", "hand_geometry", "button_coords" -> continue
                    "rematch_step", "rematch_again", "rematch_confirmed", "rematch_paused", "rematch_failed" -> continue
                    "ack_oversize" -> {
                        // The native bridge refuses payloads beyond its transport limit.
                        // Report it instead of ignoring it: a dropped capture otherwise
                        // looks like "the hand hint silently stopped updating".
                        log("ACK_OVERSIZE hint_payload_dropped")
                        suspend("提示数据包过大被丢弃，等待下一手")
                        continue
                    }
                    "external" -> { if (enabled) suspend("游戏自动操作已完成，等待下一次同步"); continue }
                    "continued" -> { log("ROUND_CONTINUED"); continue }
                    "resync" -> { log("RESYNC_REQUESTED"); continue }
                }
                if (ack.optString("state") == "manual") {
                    // The ack carries no id: any armed mode hands the current window over,
                    // otherwise the user's input would be replayed on the next sync.
                    val id = ack.optString("id")
                    if (id.isNotEmpty() && id != pending?.optString("id")) continue
                    yieldManually("检测到手动操作 · 等待新的一回合")
                    continue
                }
                // Out-of-order acknowledgement from an older command never disturbs the
                // pending one; an empty id is a network-level manual signal instead.
                val ackId = ack.optString("id")
                if (ackId.isNotEmpty() && ackId != pending?.optString("id")) continue
                // An id-less row is never a verdict on a command. Everything that can
                // report "unmatched" without an id is telemetry and handled above; a
                // state that still arrives without an id here is dropped rather than
                // being turned into a rejection of a command it does not describe.
                if (!enabled || ackId.isEmpty()) continue
                when (ack.optString("state")) {
                    "probe" -> { log("CLAIM_PROBE ${ack.optString("reason")}") }
                    "waiting" -> { log("GAME_WAITING ${ack.optString("reason")}") }
                    "submitted" -> {
                        submitted = true
                        emit(AutoDiscardEvent("GAME_SUBMITTED", "", "submitted", ackId))
                    }
                    "accepted" -> {
                        accepted = true
                        emit(AutoDiscardEvent("SERVER_ACCEPTED", "", "accepted", ackId))
                    }
                    else -> {
                        val detail = AutoAckDetails.render(ack.optString("state"), ack.optString("reason"))
                        noteRejection("command", "GAME_REJECTED", detail, ackId)
                        val reason = when (ack.optString("state")) {
                            "stale" -> "推荐已过期"
                            "round_mismatch" -> "回合校验未通过"
                            "hand_mismatch", "invalid_hand", "unknown_hand" -> "手牌校验未通过"
                            "illegal_tile" -> "目标牌当前不可操作"
                            "illegal_combination" -> "鸣牌或立直组合已变化"
                            "target_mismatch" -> "目标牌或座位已变化"
                            "win_available" -> "可以和牌，请手动确认"
                            "special_selection" -> "正在选择特殊操作"
                            "failed" -> "服务器未确认操作"
                            else -> "游戏操作状态异常"
                        }
                        suspend("$reason，等待新的完整牌局状态")
                    }
                }
            }
        }.onFailure { suspend("操作反馈格式异常，等待重新同步") }
        if (enabled && wireConfirmed && accepted && latest?.optLong("revision") != pending?.optLong("revision")) {
            pending = null
            latest?.let(::offer)
        }
        if (pending != null && now() - pendingAt > 12_000) suspend("操作确认超时，等待游戏重新同步")
        return if (enabled && !submitted && !wireConfirmed && now() >= notBefore) pending?.toString() else null
    }

    companion object {
        /** Window in which an identical rejection is folded instead of re-written. */
        internal const val REJECT_DEDUPE_MS = 2_000L
        /** Distinct rejection signatures kept before the map is flushed and restarted. */
        internal const val FOLD_CAPACITY = 64

        /** Select an exact server combination, including red fives; no fallback action. */
        fun prepareAction(result: JSONObject, first: JSONObject): JSONObject? {
            val kind = first.optString("kind")
            val context = result.optJSONObject("autoContext") ?: return null
            val own = result.optInt("seat", -1)
            val target = first.optInt("target", -1)
            val response = context.optString("phase") == "wait_response"
            val type = when (kind) {
                "discard" -> 1; "chi" -> 2; "pon" -> 3; "ankan" -> 4; "kan" -> 5
                "kakan" -> 6; "riichi" -> 7; "hora" -> if (target == own) 8 else 9
                "abort" -> 10; "kita" -> 11; "pass" -> 0; else -> return null
            }
            val claim = type in listOf(2,3,5,9) || (kind == "pass" && response)
            if (kind != "discard" && kind != "pass" && claim != response) return null
            if (kind == "hora" || kind in listOf("chi","pon","kan")) {
                if (target !in 0 until result.optInt("players", 4) || (claim && target == own)) return null
            }
            val out = JSONObject(first.toString()).put("type", type)
                .put("method", if (claim) "inputChiPengGang" else "inputOperation")
            val operations = result.optJSONArray("legalOperations") ?: org.json.JSONArray()
            val op = (0 until operations.length()).map { operations.getJSONObject(it) }.firstOrNull { it.optInt("type") == type }
            val combo = op?.optJSONArray("combination") ?: org.json.JSONArray()
            fun gameTiles(values: org.json.JSONArray?): List<String>? {
                if (values == null) return null
                return (0 until values.length()).map { toGameTile(values.optString(it)) ?: return null }
            }
            fun split(value: String): List<String>? {
                val tiles = value.split('|')
                return tiles.takeIf { it.isNotEmpty() && it.all { t -> t.matches(Regex("[0-9][mps]|[1-7]z")) } }
            }
            val hand = gameTiles(result.optJSONArray("hand")) ?: return null
            if (kind == "pass") {
                // Own-turn optional actions must be resolved to a discard by the engine.
                if (!response || operations.length() == 0) return null
                return out.put("cancel_operation", true)
            }
            if (op == null) return null
            if (kind == "discard" || kind == "riichi") {
                val tile = toGameTile(first.optString("tile")) ?: return null
                if (tile !in hand || (kind == "discard" && !first.has("tsumogiri"))) return null
                if (kind == "riichi" && (context.optBoolean("riichi") || !(0 until combo.length()).any { combo.optString(it).replace('0','5') == tile.replace('0','5') })) return null
                val moqie = if (kind == "riichi") first.optString("tile") == context.optString("drawnTile") else first.getBoolean("tsumogiri")
                if (context.optBoolean("riichi") && !moqie) return null
                if (isOpeningDealerDiscard(result)) out.put("clientOpeningDraw", true)
                return out.put("tile", tile).put("moqie", moqie)
            }
            if (kind == "kita") {
                if (result.optInt("players") != 3 || "4z" !in hand) return null
                return out.put("moqie", context.optString("drawnTile") == "N")
            }
            if (kind in listOf("chi","pon","kan","ankan","kakan")) {
                val consumed = gameTiles(first.optJSONArray("consumed")) ?: return null
                val need = when(kind) { "chi","pon" -> 2; "kan" -> 3; "ankan" -> 4; else -> 3 }
                if (consumed.size != need) return null
                val remaining = hand.toMutableList()
                if (kind != "kakan" && !consumed.all { remaining.remove(it) }) return null
                var wanted = consumed
                if (kind == "kakan") {
                    val added = toGameTile(first.optString("tile")) ?: return null
                    if (added !in hand) return null
                    val melds = context.optJSONArray("melds") ?: return null
                    fun base(t: String) = t.replace('0','5')
                    val pon = (0 until melds.length()).map { melds.getJSONObject(it) }.firstOrNull {
                        it.optString("kind") == "pon" && gameTiles(it.optJSONArray("tiles"))?.all { t -> base(t) == base(added) } == true
                    } ?: return null
                    val tiles = gameTiles(pon.optJSONArray("tiles")) ?: return null
                    if (tiles.size != 3 || tiles.sorted() != consumed.sorted()) return null
                    wanted = tiles + added
                }
                val index = (0 until combo.length()).firstOrNull { split(combo.optString(it))?.sorted() == wanted.sorted() } ?: return null
                out.put("index", index).put("combination", combo.getString(index))
                if (claim) out.put("tile", toGameTile(first.optString("tile")) ?: return null)
                return out
            }
            return out.put("index", 0)
        }

        fun matchesInput(command: JSONObject, method: String, payload: JSONObject): Boolean {
            val action = command.optJSONObject("action") ?: return false
            if (method != ".lq.FastTest.${action.optString("method")}") return false
            if (action.optBoolean("cancel_operation")) return payload.optBoolean("cancel_operation") && payload.optInt("type") == 0
            if (payload.optBoolean("cancel_operation") || payload.optInt("type") != action.optInt("type")) return false
            for (key in listOf("tile","moqie","index")) {
                if (key == "tile" && action.optString("kind") !in listOf("discard","riichi")) continue // Other RPCs identify the operation by type/index.
                // Only the dealer's opening 14-tile hand uses the client's sorted
                // last-tile identity. Exact target/red-tile/type checks still apply.
                if (key == "moqie" && action.optBoolean("clientOpeningDraw") &&
                    action.optString("kind") in listOf("discard","riichi") && isOpeningDealerDiscard(command)) continue
                if (action.has(key)) {
                    if (key == "moqie" && payload.optBoolean(key) != action.optBoolean(key)) return false
                    if (key == "index" && payload.optInt(key) != action.optInt(key)) return false
                    if (key == "tile" && payload.optString(key) != action.optString(key)) return false
                }
            }
            return true
        }

        private fun isOpeningDealerDiscard(result: JSONObject): Boolean {
            val context = result.optJSONObject("autoContext") ?: return false
            val seat = result.optInt("seat", -1)
            return context.optBoolean("openingDealerDiscard") && context.optString("phase") == "wait_act" &&
                seat in 0 until result.optInt("players", 0) && seat == context.optInt("dealer", -2) &&
                result.optJSONArray("hand")?.length() == 14 && !context.optBoolean("riichi") &&
                context.optJSONArray("melds")?.length() == 0
        }

        fun toGameTile(tile: String): String? {
            val honors = mapOf("E" to "1z", "S" to "2z", "W" to "3z", "N" to "4z", "P" to "5z", "F" to "6z", "C" to "7z")
            honors[tile]?.let { return it }
            if (!tile.matches(Regex("[1-9][mps]|5[mps]r"))) return null
            return if (tile.endsWith("r")) "0${tile[1]}" else tile
        }
    }
}
