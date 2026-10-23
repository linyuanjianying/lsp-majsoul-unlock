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

/** One pending command, one attempt per decision window; never retries an input. */
internal class AutoDiscardController(private val log: (String) -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val delayMs: () -> Long = { AutoDelayRange().randomDelayMs() },
    initialEnabled: Boolean = false, private val modeChanged: (Boolean) -> Unit = {}) {
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
    private var sameTurnRetries = 0
    /** Armed right after our input is sent: poll the game every second and repeat the
     *  discard while the refreshed state still says it is our turn. Survives reconnect. */
    private var pollArmed = false
    private var pollDueAt = 0L
    private var pollWait = ""

    @Synchronized fun pause(reason: String) {
        if (enabled || pending != null) log("PAUSED: $reason")
        enabled = false; pending = null
        stopPolling()
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

    @Synchronized private fun stopPolling() {
        pollArmed = false; pollDueAt = 0L; sameTurnRetries = 0; pollWait = ""
    }

    /** Keep polling, but do not repeat an unchanged wait reason in the diagnostics log. */
    @Synchronized private fun pollWait(reason: String) {
        pollDueAt = now() + POLL_INTERVAL_MS
        if (pollWait != reason) { pollWait = reason; log("TURN_POLL_WAIT $reason") }
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
            } else if (command != null && payload != null
                && matchesInput(command, input.optString("method"), payload)) {
                // Our own request was captured on the wire. Confirm it even when the
                // Lua ack arrived first, otherwise the command would never be released.
                wireConfirmed = true
                log("UPLINK_CONFIRMED id=${command.optString("id")}")
                AutoDiscardState.mutable.value = AutoDiscardStatus(true, "已确认游戏发送操作 · 等待下一回合")
            } else if (wireConfirmed || accepted) {
                // The server has already accepted our command. A following claim or
                // response frame belongs to the same transition and must not revoke
                // the mode before the next synchronized decision arrives.
                log("IGNORED_TRANSITION_UPLINK id=${command?.optString("id") ?: ""}")
            } else if (command == null) {
                // A claim window can produce several inputOperation/inputChiPengGang
                // frames while the server advances the turn. With nothing pending
                // those are ordinary game traffic; keep the live snapshot instead of
                // stranding unattended mode until the next full authGame sync.
                log("IGNORED_UNMATCHED_UPLINK no_pending")
            } else {
                // Only an unmatched frame while a command is genuinely awaiting
                // submission is suspicious. Revoke that command, but keep the mode
                // armed for recovery.
                suspend("收到不匹配的游戏操作，撤销当前指令并等待重新同步")
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
        val key = "${result.optLong("sourceGeneration")}:${result.optString("connection")}:${result.optLong("revision")}"
        if (key == attempted || result.optLong("sourceSequence") <= 0) return
        attempted = key
        // A brand-new decision window gets its own fresh polling budget.
        sameTurnRetries = 0; pollWait = ""
        val action = prepareAction(result, first) ?: run {
            suspend("AI 动作与合法操作未能匹配，等待新的操作窗口")
            return
        }
        enqueue(result, action, delayMs())
    }

    /** Queue one command; a repeat inside the polling window uses no random delay. */
    private fun enqueue(result: JSONObject, action: JSONObject, delay: Long) {
        pending = null
        wireConfirmed = false; submitted = false; accepted = false
        pendingAt = now()
        notBefore = pendingAt + delay
        pending = JSONObject(result.toString()).apply {
            remove("analysis"); remove("recommendations"); remove("input")
            put("id", "$serial-${++count}")
            put("action", action)
            put("delayMs", delay)
            put("gameTile", action.optString("tile")); put("tsumogiri", action.optBoolean("moqie"))
        }
        // Expect the input to hit the wire at notBefore; poll one second later even if the
        // Lua acknowledgement is lost, and refresh the deadline when it does arrive.
        pollArmed = true; pollDueAt = notBefore + POLL_INTERVAL_MS
        log("QUEUED kind=${action.optString("kind")} delayMs=$delay id=${pending!!.optString("id")}")
        AutoDiscardState.mutable.value = AutoDiscardStatus(true, "等待 ${"%.1f".format(delay / 1000.0)} 秒后执行 · 可随时关闭")
    }

    /** Repeat the last decision with no delay once the poll finds it is still our turn. */
    private fun pollRetry() {
        // A command that has not reached its own delay window is still pending; never
        // replace it. Once it was published (or confirmed) only the confirmation is
        // missing, so a lost Lua acknowledgement must not disable the poll.
        if (pending != null && !(submitted || wireConfirmed || accepted || now() >= notBefore)) return
        // Always poll the freshest frame: after a reconnect an older copy would carry a
        // stale capture generation and the game side would refuse it.
        val state = latest
        if (state == null || state.optString("status") != "live" || state.optBoolean("modelFallback")) {
            pollWait("等待完整牌局同步")
            return
        }
        if (!state.optBoolean("canAct") || state.optBoolean("inputPending")) {
            stopPolling()
            log("TURN_POLL_STOPPED not_our_turn")
            return
        }
        if (sameTurnRetries >= MAX_SAME_TURN_RETRIES) {
            stopPolling()
            log("TURN_POLL_STOPPED retries_exhausted")
            return
        }
        val first = state.optJSONArray("recommendations")?.optJSONObject(0) ?: run {
            stopPolling(); log("TURN_POLL_STOPPED no_recommendation"); return
        }
        if (state.optJSONArray("legalOperations")?.length() == 0) {
            stopPolling(); log("TURN_POLL_STOPPED no_legal_operation"); return
        }
        val action = prepareAction(state, first) ?: run {
            stopPolling(); log("TURN_POLL_STOPPED action_unavailable"); return
        }
        sameTurnRetries++
        pollWait = ""
        log("TURN_POLL_RETRY attempt=$sameTurnRetries")
        enqueue(state, action, 0L)
        AutoDiscardState.mutable.value =
            AutoDiscardStatus(true, "轮询确认仍是我方回合 · 立即重新出牌（第 $sameTurnRetries 次）")
    }

    @Synchronized fun poll(acknowledgements: String?): String? {
        if (acknowledgements != null) runCatching {
            val rows = org.json.JSONArray(acknowledgements)
            for (index in 0 until rows.length()) {
                val ack = rows.getJSONObject(index)
                when (ack.optString("state")) {
                    "external" -> { stopPolling(); if (enabled) suspend("游戏自动操作已完成，等待下一次同步"); continue }
                    "continued" -> { log("ROUND_CONTINUED"); continue }
                    "resync" -> { log("RESYNC_REQUESTED"); continue }
                }
                if (ack.optString("state") == "manual") {
                    stopPolling()
                    if (enabled) suspend("游戏反馈为手动操作，撤销当前指令并等待重新同步")
                    continue
                }
                if (ack.optString("id") != pending?.optString("id")) continue
                when (ack.optString("state")) {
                    "probe" -> { log("CLAIM_PROBE ${ack.optString("reason")}") }
                    "waiting" -> { log("GAME_WAITING ${ack.optString("reason")}") }
                    "probe" -> { log("CLAIM_PROBE ${ack.optString("reason")}") }
                    "submitted" -> {
                        submitted = true
                        pollDueAt = now() + POLL_INTERVAL_MS
                        log("GAME_SUBMITTED id=${ack.optString("id")}")
                    }
                    "accepted" -> {
                        accepted = true
                        pollDueAt = now() + POLL_INTERVAL_MS
                        log("SERVER_ACCEPTED id=${ack.optString("id")}")
                        AutoDiscardState.mutable.value =
                            AutoDiscardStatus(true, "已出牌 · 1 秒后轮询确认是否仍是自己回合")
                    }
                    else -> {
                        log("GAME_REJECTED ${ack.optString("state")} ${ack.optString("reason")}")
                        val reason = when (ack.optString("state")) {
                            "stale" -> "推荐已过期"
                            "round_mismatch" -> "回合校验未通过"
                            "hand_mismatch", "invalid_hand", "unknown_hand" -> "手牌校验未通过"
                            "illegal_tile" -> "目标牌当前不可操作"
                            "illegal_combination" -> "鸣牌或立直组合已变化"
                            "target_mismatch", "target_tile_mismatch" -> "目标牌已变化"
                            "win_available" -> "可以和牌，请手动确认"
                            "special_selection" -> "正在选择特殊操作"
                            "failed" -> "服务器未确认操作"
                            "claim_run_exception" -> "吃碰界面调用发生异常"
                            else -> "游戏操作状态异常"
                        }
                        suspend("$reason，等待新的完整牌局状态")
                    }
                }
            }
        }.onFailure { suspend("操作反馈格式异常，等待重新同步") }
        // Once the server accepted our input and the snapshot moved on, the decision
        // window is finished; a missing uplink frame (reconnect, capture gap) must not
        // keep the stale command alive.
        if (enabled && accepted && pending != null && latest?.optLong("revision") != pending?.optLong("revision")) {
            pending = null
            latest?.let(::offer)
        }
        if (pending != null && now() - pendingAt > 12_000) suspend("操作确认超时，等待游戏重新同步")
        // One second after our input was sent, verify the turn is really over. A claim or
        // kan window can reconnect the game and strand the decision, so a still-live
        // "our turn" state repeats the discard immediately instead of waiting for a frame
        // that never arrives.
        if (enabled && pollArmed && now() >= pollDueAt) pollRetry()
        return if (enabled && !submitted && !wireConfirmed && now() >= notBefore) pending?.toString() else null
    }

    companion object {
        /** Default unattended random delay window; user-adjustable 0..5000 ms, persisted by AiOverlayService. */
        const val DEFAULT_MIN_DELAY_MS = 1_000L
        const val DEFAULT_MAX_DELAY_MS = 3_000L
        const val POLL_INTERVAL_MS = 1_000L
        const val MAX_SAME_TURN_RETRIES = 3

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
