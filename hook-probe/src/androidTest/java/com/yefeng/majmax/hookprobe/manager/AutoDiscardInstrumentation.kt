package com.yefeng.majmax.hookprobe.manager

import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Runs against Android's real JSONObject, clock supplied explicitly; no game input. */
class AutoDiscardInstrumentation : Instrumentation() {
    private var controllerOnly = false
    private var importFile: String? = null
    private var verifySelectedModels = false
    override fun onCreate(arguments: Bundle?) {
        controllerOnly = arguments?.getString("controllerOnly") == "true"
        importFile = arguments?.getString("importFile")
        verifySelectedModels = arguments?.getString("verifySelectedModels") == "true"
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        val output = Bundle()
        runCatching {
            if (verifySelectedModels) {
                val before = targetContext.getSharedPreferences("onnx-policy-models", 0).all.toMap()
                val unattended = targetContext.getSharedPreferences("unattended", 0).all.toMap()
                OnnxModelStore.initialize(targetContext)
                val results = listOf(3,4).filter { OnnxModelStore.selectedCustom(targetContext,it) }
                    .map { OnnxModelStore.selfTest(targetContext,it) }
                check(results.isNotEmpty()) { "No selected custom models to verify" }
                check(before == targetContext.getSharedPreferences("onnx-policy-models", 0).all)
                check(unattended == targetContext.getSharedPreferences("unattended", 0).all)
                output.putString("stream", results.joinToString("\n", "PASS selected models; preferences unchanged\n", "\n"))
                finish(-1,output)
                return
            }
            importFile?.let { path ->
                val file = java.io.File(path).canonicalFile
                check(file.parentFile == targetContext.cacheDir.canonicalFile) { "Use a model in the target app cache" }
                check(AiNative.loadMortalPth(3, file.absolutePath, false) == 3) { "Expected a valid sanma checkpoint" }
                val prefs = targetContext.getSharedPreferences("onnx-policy-models", 0)
                val fourBefore = prefs.all.filterKeys { it.endsWith("_4") }
                val unattendedBefore = targetContext.getSharedPreferences("unattended", 0).all.toMap()
                val policy = OnnxModelStore.import(targetContext, android.net.Uri.fromFile(file))
                check(policy.players == 3 && policy.format == "mortal-pth")
                val result = OnnxModelStore.selfTest(targetContext, 3)
                check(OnnxModelStore.activate(3) == 0)
                check(OnnxModelStore.selectedCustom(targetContext, 3))
                check(prefs.all.filterKeys { it.endsWith("_4") } == fourBefore)
                check(targetContext.getSharedPreferences("unattended", 0).all == unattendedBefore)
                output.putString("stream", "PASS import ${policy.name}: ${policy.bytes} bytes, ${policy.sha256}\n$result\nPASS activation; four-player and unattended settings preserved\n")
                finish(-1, output)
                return
            }
            var tests = 0
            fun test(name: String, run: (AutoDiscardController, () -> Unit) -> Unit) {
                var time = 0L
                val gate = AutoDiscardController({}, { time }, { 0L })
                gate.invalidate("test reset")
                run(gate) { time += 12_001 }
                tests++
                sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") })
            }
            fun live(revision: Int = 1, kind: String = "discard") = JSONObject("""
                {"status":"live","canAct":true,"connection":"7","revision":$revision,
                 "sourceGeneration":3,"sourceSequence":8,"seat":0,"players":4,"hand":["5mr"],
                 "legalOperations":[{"type":1,"combination":[]}],
                 "autoContext":{"riichi":false},
                 "recommendations":[{"kind":"$kind","tile":"5mr","tsumogiri":false}]}
            """.trimIndent())
            fun ack(id: String, state: String) = JSONArray().put(JSONObject().put("id",id).put("state",state)).toString()
            test("default off and explicit enable") { gate, _ ->
                gate.observe(live()); check(gate.poll(null) == null)
                gate.toggle(); val command = JSONObject(checkNotNull(gate.poll(null)))
                check(command.getString("gameTile") == "0m")
                check(!command.getBoolean("tsumogiri"))
            }
            test("arming before sync never sends an input") { gate, _ ->
                gate.observe(live().put("status","demo")); gate.toggle(); check(gate.poll(null) == null)
                gate.observe(live()); gate.invalidate("disconnected"); gate.toggle(); check(gate.poll(null) == null)
            }
            test("new capture session invalidates queued input") { gate, _ ->
                gate.observe(live()); gate.toggle(); check(gate.poll(null) != null)
                gate.invalidate("new session"); check(gate.poll(null) == null && AutoDiscardState.mutable.value.enabled)
                gate.observe(live().put("sourceGeneration",4)); check(gate.poll(null) != null)
            }
            test("rematch is independent of unattended") { _, _ ->
                check(RematchState(enabled=true, unattended=false).active)
                check(!RematchState(enabled=false, unattended=true).active)
                check(!RematchState(enabled=true, unattended=true, demo=true).active)
            }
            test("manual operation yields without disabling") { gate, _ ->
                gate.observe(live()); gate.toggle()
                gate.poll(ack("", "manual")); check(gate.poll(null) == null && AutoDiscardState.mutable.value.enabled)
            }
            test("manual handover keeps the opt-in but never replays the same decision") { gate, _ ->
                gate.observe(live()); gate.toggle()
                gate.poll(ack("", "manual"))
                check(AutoDiscardState.mutable.value.enabled && gate.isEnabled())
                check(gate.poll(null) == null)
                gate.observe(live()); check(gate.poll(null) == null) { "same revision must not be resent" }
                gate.observe(live().put("sourceSequence",9)); check(gate.poll(null) == null)
                gate.observe(live(2)); check(gate.poll(null) != null) { "a new revision resumes" }
            }
            test("manual before the decision window is queued still claims that window") { gate, _ ->
                gate.toggle()
                gate.observe(live(2).put("canAct",false))
                gate.poll(ack("", "manual")) // armed, before a command can queue
                check(gate.poll(null) == null) { "the window the user played must not be replayed" }
                gate.observe(live(2)); check(gate.poll(null) == null)
                gate.observe(live(3)); check(gate.poll(null) != null)
            }
            test("manual observed as an unmatched uplink keeps the opt-in") { gate, _ ->
                gate.observe(live()); gate.toggle()
                gate.observe(live().put("input",JSONObject("""
                    {"method":".lq.FastTest.inputOperation","payload":{"type":1,"tile":"5m","moqie":false}}
                """)))
                check(AutoDiscardState.mutable.value.enabled && gate.poll(null) == null)
                gate.observe(live()); check(gate.poll(null) == null)
                gate.observe(live(2)); check(gate.poll(null) != null)
            }
            test("explicit switch-off is never undone by a new decision") { gate, _ ->
                gate.observe(live()); gate.toggle()
                gate.setEnabled(false); check(!AutoDiscardState.mutable.value.enabled)
                gate.observe(live(2)); check(gate.poll(null) == null && !gate.isEnabled())
                gate.poll(ack("", "manual")); check(!gate.isEnabled())
                gate.observe(live(3)); check(gate.poll(null) == null)
                gate.toggle(); check(gate.poll(null) != null) { "only the user may re-arm" }
            }
            test("out-of-order ack from an older command does not cancel the pending one") { gate, _ ->
                gate.observe(live()); gate.toggle(); val first=JSONObject(gate.poll(null)!!).getString("id")
                gate.poll(ack(first,"submitted")); gate.poll(ack(first,"accepted"))
                gate.observe(live().put("input", JSONObject("""{"method":".lq.FastTest.inputOperation","payload":{"type":1,"tile":"0m","moqie":false}}""")))
                gate.observe(live(2))
                val second=JSONObject(checkNotNull(gate.poll(null))).getString("id")
                check(second != first)
                gate.poll(ack(first,"accepted")); gate.poll(ack(first,"stale"))
                gate.poll(ack("$first-stale","round_mismatch"))
                check(gate.isEnabled())
                gate.poll(ack(second,"submitted")); gate.poll(ack(second,"accepted"))
                gate.observe(live(2).put("input", JSONObject("""{"method":".lq.FastTest.inputOperation","payload":{"type":1,"tile":"0m","moqie":false}}""")))
                gate.observe(live(3)); check(gate.poll(null) != null) { "the live command survived stale acks" }
            }
            test("manual input while disabled does not alter paused state") { gate, _ ->
                gate.observe(live())
                val previous = AutoDiscardState.mutable.value
                gate.poll(ack("", "manual"))
                check(previous == AutoDiscardState.mutable.value && gate.poll(null) == null)
            }
            test("model failure waits while armed") { gate, _ ->
                gate.observe(live()); gate.toggle(); gate.observe(live().put("modelFallback",true))
                check(gate.poll(null) == null && AutoDiscardState.mutable.value.enabled)
                gate.observe(live(2)); check(gate.poll(null) != null)
            }
            test("riichi requires a server declaration prompt") { gate, _ ->
                gate.observe(live(kind="riichi")); gate.toggle(); check(gate.poll(null) == null)
            }
            test("timeout keeps opt-in without replaying old decision") { gate, advance ->
                gate.observe(live()); gate.toggle(); advance()
                check(gate.poll(null) == null && AutoDiscardState.mutable.value.enabled)
                gate.observe(live()); check(gate.poll(null) == null)
                gate.observe(live(2)); check(gate.poll(null) != null)
            }
            test("stale rejection does not replay decision") { gate, _ ->
                gate.observe(live()); gate.toggle(); val id=JSONObject(gate.poll(null)!!).getString("id")
                gate.poll(ack(id,"stale")); check(gate.isEnabled() && gate.poll(null) == null)
                gate.observe(live()); check(gate.poll(null) == null)
                gate.observe(live(2)); check(gate.poll(null) != null)
            }
            test("sent input requires server acceptance before next decision") { gate, _ ->
                gate.observe(live()); gate.toggle(); val id=JSONObject(gate.poll(null)!!).getString("id")
                gate.poll(ack(id,"submitted"))
                gate.observe(live().put("canAct",false).put("input",JSONObject("""
                    {"method":".lq.FastTest.inputOperation","payload":{"type":1,"tile":"0m","moqie":false}}
                """)))
                gate.observe(live(2)); check(gate.poll(null) == null)
                gate.poll(ack(id,"accepted")); val next=JSONObject(checkNotNull(gate.poll(null)))
                check(next.getString("id") != id && next.getInt("revision") == 2)
            }
            test("unmatched uplink yields") { gate, _ ->
                gate.observe(live()); gate.toggle()
                gate.observe(live().put("input",JSONObject("""
                    {"method":".lq.FastTest.inputOperation","payload":{"type":1,"tile":"5m","moqie":false}}
                """)))
                check(gate.poll(null) == null && AutoDiscardState.mutable.value.enabled)
            }
            fun actionCase(kind: String, type: Int, tile: String = "", consumed: List<String> = emptyList(),
                           combo: List<String> = emptyList(), hand: List<String> = listOf("5mr"), target: Int = 3,
                           response: Boolean = true): JSONObject {
                val state=live(kind=kind).put("hand",JSONArray(hand))
                state.getJSONObject("autoContext").put("phase",if(response) "wait_response" else "wait_act").put("drawnTile","5mr")
                state.put("legalOperations",JSONArray().put(JSONObject().put("type",type).put("combination",JSONArray(combo))))
                state.put("recommendations",JSONArray().put(JSONObject().put("kind",kind).put("tile",tile)
                    .put("target",target).put("consumed",JSONArray(consumed))))
                return state
            }
            fun prepared(state: JSONObject) = checkNotNull(AutoDiscardController.prepareAction(state,state.getJSONArray("recommendations").getJSONObject(0)))
            test("opening dealer accepts client moqie only with complete protocol proof") { gate, _ ->
                val hand=listOf("1m","2m","3m","4m","5m","6m","7m","8m","9m","1p","2p","3p","1s","9s")
                val state=actionCase("discard",1,"9s",hand=hand,response=false)
                state.getJSONArray("recommendations").getJSONObject(0).put("tsumogiri",true)
                state.getJSONObject("autoContext").put("openingDealerDiscard",true).put("dealer",0).put("melds",JSONArray())
                gate.observe(state); gate.toggle()
                val cmd=JSONObject(checkNotNull(gate.poll(null)))
                check(cmd.getJSONObject("action").getBoolean("clientOpeningDraw"))
                val payload=JSONObject("""{"type":1,"tile":"9s","moqie":false}""")
                check(AutoDiscardController.matchesInput(cmd,".lq.FastTest.inputOperation",payload))
                check(!AutoDiscardController.matchesInput(cmd,".lq.FastTest.inputOperation",JSONObject(payload.toString()).put("tile","1s")))
                for ((key,value) in listOf("openingDealerDiscard" to false,"dealer" to 1,"phase" to "wait_response","riichi" to true,"melds" to JSONArray().put(JSONObject()))) {
                    val invalid=JSONObject(cmd.toString())
                    invalid.getJSONObject("autoContext").put(key,value)
                    check(!AutoDiscardController.matchesInput(invalid,".lq.FastTest.inputOperation",payload))
                }
                val shortHand=JSONObject(cmd.toString()).put("hand",JSONArray(hand.dropLast(1)))
                check(!AutoDiscardController.matchesInput(shortHand,".lq.FastTest.inputOperation",payload))
            }
            test("riichi retains red tile and declaration type") { gate, _ ->
                val state=actionCase("riichi",7,"5mr",combo=listOf("5m"),response=false)
                gate.observe(state); gate.toggle()
                val cmd=JSONObject(checkNotNull(gate.poll(null)))
                check(cmd.getJSONObject("action").getInt("type")==7 && cmd.getBoolean("tsumogiri"))
                check(!AutoDiscardController.matchesInput(cmd,".lq.FastTest.inputOperation",JSONObject("""{"type":1,"tile":"0m","moqie":true}""")))
                check(AutoDiscardController.matchesInput(cmd,".lq.FastTest.inputOperation",JSONObject("""{"type":7,"tile":"0m","moqie":true}""")))
            }
            test("chi selects exact red combination index") { _, _ ->
                val state=actionCase("chi",2,"3m",listOf("4m","5mr"),listOf("4m|5m","0m|4m"),listOf("4m","5mr"))
                check(prepared(state).getInt("index")==1)
                state.getJSONArray("recommendations").getJSONObject(0).put("consumed",JSONArray(listOf("4m","5m")))
                check(AutoDiscardController.prepareAction(state,state.getJSONArray("recommendations").getJSONObject(0))==null)
            }
            test("pon and open kan retain target and consumed tiles") { _, _ ->
                for ((kind,type,n) in listOf(Triple("pon",3,2),Triple("kan",5,3))) {
                    val tiles=List(n){"5m"}; val state=actionCase(kind,type,"5m",tiles,listOf(List(n){"5m"}.joinToString("|")),tiles)
                    check(prepared(state).getInt("index")==0 && prepared(state).getInt("target")==3)
                    state.getJSONArray("recommendations").getJSONObject(0).put("target",0)
                    check(AutoDiscardController.prepareAction(state,state.getJSONArray("recommendations").getJSONObject(0))==null)
                }
            }
            test("ankan requires all four exact tiles") { _, _ ->
                val tiles=listOf("5m","5m","5m","5mr")
                check(prepared(actionCase("ankan",4,"5m",tiles,listOf("5m|0m|5m|5m"),tiles,response=false)).getInt("index")==0)
            }
            test("kakan accounts for red tile in the existing pon") { _, _ ->
                val state=actionCase("kakan",6,"5m",listOf("5m","5mr","5m"),listOf("5m|5m|0m|5m"),listOf("5m"),response=false)
                state.getJSONObject("autoContext").put("melds",JSONArray().put(JSONObject().put("kind","pon").put("tiles",JSONArray(listOf("5m","5mr","5m")))))
                check(prepared(state).getInt("index")==0)
            }
            test("ron and tsumo use distinct request methods") { _, _ ->
                check(prepared(actionCase("hora",9,target=3)).getString("method")=="inputChiPengGang")
                check(prepared(actionCase("hora",8,target=0,response=false)).getString("method")=="inputOperation")
            }
            test("pass sends an explicit cancel and never confirms a win") { _, _ ->
                val state=actionCase("pass",2)
                val cmd=JSONObject().put("action",prepared(state))
                check(AutoDiscardController.matchesInput(cmd,".lq.FastTest.inputChiPengGang",JSONObject().put("cancel_operation",true)))
                check(!AutoDiscardController.matchesInput(cmd,".lq.FastTest.inputChiPengGang",JSONObject().put("type",9)))
            }
            test("kita is sanma only and preserves moqie") { _, _ ->
                val state=actionCase("kita",11,"N",hand=listOf("N"),response=false)
                state.put("players",3); state.getJSONObject("autoContext").put("drawnTile","N")
                val cmd=JSONObject().put("action",prepared(state))
                check(AutoDiscardController.matchesInput(cmd,".lq.FastTest.inputOperation",JSONObject().put("type",11).put("moqie",true)))
                state.put("players",4)
                check(AutoDiscardController.prepareAction(state,state.getJSONArray("recommendations").getJSONObject(0))==null)
            }
            test("abort requires server nine terminals prompt") { _, _ ->
                check(prepared(actionCase("abort",10,response=false)).getInt("type")==10)
            }
            test("changed combination index cannot confirm another call") { gate, _ ->
                gate.observe(actionCase("chi",2,"3m",listOf("4m","5mr"),listOf("4m|5m","4m|0m"),listOf("4m","5mr"))); gate.toggle()
                gate.observe(live().put("input",JSONObject("""{"method":".lq.FastTest.inputChiPengGang","payload":{"type":2,"index":0}}""")))
                check(AutoDiscardState.mutable.value.enabled && gate.poll(null) == null)
            }
            test("riichi locks ordinary discard to drawn tile") { _, _ ->
                val state=live(); state.getJSONObject("autoContext").put("riichi",true)
                check(AutoDiscardController.prepareAction(state,state.getJSONArray("recommendations").getJSONObject(0))==null)
            }
            test("queued actions wait for their full random delay") { _, _ ->
                for (delay in listOf(2_000L,5_000L)) {
                    var time=0L
                    val gate=AutoDiscardController({}, {time}, {delay})
                    gate.observe(live()); gate.toggle(); check(gate.poll(null)==null)
                    time=delay-1; check(gate.poll(null)==null)
                    time=delay; check(JSONObject(checkNotNull(gate.poll(null))).getLong("delayMs")==delay)
                }
            }
            test("heartbeat renews sequence without restarting delay") { _, _ ->
                var time=0L
                val gate=AutoDiscardController({}, {time}, {2_000L})
                gate.observe(live()); gate.toggle()
                time=1_500; gate.observe(live().put("sourceSequence",9)); check(gate.poll(null)==null)
                time=2_000; check(JSONObject(checkNotNull(gate.poll(null))).getLong("sourceSequence")==9L)
            }
            test("changed decision gets its own full delay") { _, _ ->
                var time=0L
                val gate=AutoDiscardController({}, {time}, {2_000L})
                gate.observe(live()); gate.toggle(); gate.observe(live(2))
                time=1_999; check(gate.poll(null)==null && AutoDiscardState.mutable.value.enabled)
                time=2_000; check(JSONObject(checkNotNull(gate.poll(null))).getInt("revision")==2)
            }
            test("pause during delay never publishes input") { _, _ ->
                var time=0L
                val gate=AutoDiscardController({}, {time}, {5_000L})
                gate.observe(live()); gate.toggle(); gate.pause("manual")
                time=5_000; check(gate.poll(null)==null)
            }
            test("production random delay always stays within two to five seconds") { _, _ ->
                repeat(30) {
                    var time=0L
                    val gate=AutoDiscardController({}, {time})
                    gate.observe(live()); gate.toggle(); check(gate.poll(null)==null)
                    time=5_000
                    check(JSONObject(checkNotNull(gate.poll(null))).getLong("delayMs") in 2_000L..5_000L)
                }
            }
            test("custom delay boundaries and fixed delay") { _, _ ->
                for (value in listOf(0, 1, 13, 50)) {
                    val range=AutoDelayRange(value,value)
                    repeat(10) { check(range.randomDelayMs()==value*100L) }
                    var time=0L
                    val gate=AutoDiscardController({}, {time}, range::randomDelayMs)
                    gate.observe(live()); gate.toggle()
                    if (value>0) { time=value*100L-1; check(gate.poll(null)==null) }
                    time=value*100L
                    check(JSONObject(checkNotNull(gate.poll(null))).getLong("delayMs")==time)
                }
                repeat(100) { check(AutoDelayRange(10,30).randomDelayMs() in 1_000L..3_000L) }
                check(AutoDelayRange(20,30).withMinimum(40)==AutoDelayRange(40,40))
                check(AutoDelayRange(20,30).withMaximum(10)==AutoDelayRange(10,10))
            }
            test("delay edits affect new decisions without restarting a queued wait") { _, _ ->
                var time=0L
                var range=AutoDelayRange(10,10)
                val gate=AutoDiscardController({}, {time}, {range.randomDelayMs()})
                gate.observe(live()); gate.toggle()
                range=AutoDelayRange(30,30)
                time=999; check(gate.poll(null)==null)
                time=1_000; check(JSONObject(checkNotNull(gate.poll(null))).getLong("delayMs")==1_000L)
                gate.observe(live(2))
                time=3_999; check(gate.poll(null)==null)
                time=4_000; check(JSONObject(checkNotNull(gate.poll(null))).getLong("delayMs")==3_000L)
            }
            test("delay settings persist and recover invalid saved values") { _, _ ->
                val isolated=object : android.content.ContextWrapper(targetContext) {
                    override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("delay-checks-$name",mode)
                }
                val prefs=isolated.getSharedPreferences("unattended",0)
                try {
                    prefs.edit().clear().commit()
                    check(AutoDelaySettings.read(isolated)==AutoDelayRange(20,50))
                    AutoDelaySettings.save(isolated,AutoDelayRange(13,27))
                    check(AutoDelaySettings.read(isolated)==AutoDelayRange(13,27))
                    prefs.edit().putInt("delay_min_tenths",99).putInt("delay_max_tenths",-1).commit()
                    check(AutoDelaySettings.read(isolated)==AutoDelayRange(0,50))
                    prefs.edit().putString("delay_min_tenths","bad").remove("delay_max_tenths").commit()
                    check(AutoDelaySettings.read(isolated)==AutoDelayRange(20,50))
                } finally { prefs.edit().clear().commit() }
            }
            test("round settlement resumes automatically with a new round") { gate, _ ->
                gate.observe(live()); gate.toggle()
                gate.observe(JSONObject().put("status","waiting"))
                check(gate.isEnabled() && gate.poll(null)==null)
                gate.observe(live(2)); check(gate.poll(null)!=null)
            }
            test("external timeout does not disable unattended mode") { gate, _ ->
                gate.observe(live()); gate.toggle(); gate.poll(ack("","external"))
                check(gate.isEnabled() && gate.poll(null)==null)
                gate.observe(live(2)); check(gate.poll(null)!=null)
            }
            test("restored opt-in waits for sync and persists explicit closure") { _, _ ->
                val saved=mutableListOf<Boolean>()
                val gate=AutoDiscardController({}, {0L}, {0L}, initialEnabled=true, modeChanged={saved.add(it)})
                check(gate.isEnabled() && gate.needsSync() && gate.poll(null)==null)
                gate.observe(live()); check(gate.poll(null)!=null)
                gate.pause("explicit stop"); gate.observe(live(2))
                check(!gate.isEnabled() && gate.poll(null)==null && saved.last()==false)
            }
            test("game relaunch waits ten seconds and backs off") { _, _ ->
                val recovery=UnattendedRecovery()
                check(!recovery.shouldLaunch(true,false,0))
                check(!recovery.shouldLaunch(true,false,9_999))
                check(recovery.shouldLaunch(true,false,10_000))
                check(!recovery.shouldLaunch(true,false,39_999))
                check(recovery.shouldLaunch(true,false,40_000))
                check(!recovery.shouldLaunch(true,false,99_999))
                check(recovery.shouldLaunch(true,false,100_000))
                check(!recovery.shouldLaunch(true,true,101_000))
                check(!recovery.shouldLaunch(true,false,102_000))
                check(!recovery.shouldLaunch(false,false,120_000))
            }
            test("temporary game proof wait keeps the same pending command") { gate, _ ->
                gate.observe(live()); gate.toggle()
                val cmd=JSONObject(checkNotNull(gate.poll(null)))
                gate.poll(ack(cmd.getString("id"),"waiting"))
                check(gate.isEnabled() && JSONObject(checkNotNull(gate.poll(null))).getString("id")==cmd.getString("id"))
            }
            test("validated custom model can arm and continue unattended") { gate, _ ->
                gate.observe(live().put("modelExperimental",true)); gate.toggle()
                val command=JSONObject(checkNotNull(gate.poll(null)))
                check(gate.isEnabled() && command.getJSONObject("action").getString("kind")=="discard")
                gate.poll(ack(command.getString("id"),"submitted"))
                gate.observe(live().put("modelExperimental",true).put("canAct",false).put("input",JSONObject("""
                    {"method":".lq.FastTest.inputOperation","payload":{"type":1,"tile":"0m","moqie":false}}
                """)))
                gate.observe(live(2).put("modelExperimental",true))
                check(gate.poll(null)==null)
                gate.poll(ack(command.getString("id"),"accepted"))
                check(gate.isEnabled() && JSONObject(checkNotNull(gate.poll(null))).getInt("revision")==2)
            }
            test("custom model fallback or missing proof still blocks unattended input") { gate, _ ->
                gate.observe(live().put("modelExperimental",true).put("modelFallback",true)); gate.toggle()
                check(gate.isEnabled() && gate.poll(null)==null)
                gate.observe(live(2).put("modelExperimental",true)); check(gate.poll(null)!=null)
                gate.observe(live(3).put("modelExperimental",true).put("modelFallback",true))
                check(gate.isEnabled() && gate.poll(null)==null)
                gate.observe(live(4).put("modelExperimental",true).put("sourceSequence",0))
                check(gate.isEnabled() && gate.poll(null)==null)
            }
            test("custom model preserves stale rejection and manual stop") { gate, _ ->
                gate.observe(live().put("modelExperimental",true)); gate.toggle()
                val command=JSONObject(checkNotNull(gate.poll(null)))
                gate.poll(ack(command.getString("id"),"stale"))
                gate.observe(live().put("modelExperimental",true)); check(gate.poll(null)==null)
                gate.observe(live(2).put("modelExperimental",true)); check(gate.poll(null)!=null)
                gate.poll(ack("","manual"))
                check(gate.isEnabled() && gate.poll(null)==null) { "manual input keeps the opt-in" }
                gate.observe(live(2).put("modelExperimental",true)); check(gate.poll(null)==null)
                gate.observe(live(3).put("modelExperimental",true)); check(gate.poll(null)!=null)
            }
            // TileBadgeOverlay coordinate filtering is pure; exercise it directly so a
            // camera/projection fault that sends every tile off-screen is observable.
            fun coord(tile: String, nx: Double, ny: Double, last: Boolean = false) =
                JSONObject().put("tile", tile).put("nx", nx).put("ny", ny).put("last", last)
            test("in-viewport coordinates are all kept") { _, _ ->
                val coords = JSONArray().put(coord("1m", 0.2, 0.8)).put(coord("2m", 0.3, 0.8))
                    .put(coord("3m", 0.4, 0.8))
                val f = TileBadgeOverlay.filterCoords(coords)
                check(f.kept.size == 3 && f.rejected == 0 && f.sample.isEmpty())
            }
            test("out-of-viewport coordinates are rejected with a bounded sample") { _, _ ->
                val coords = JSONArray().put(coord("1m", -1.5, 0.8)).put(coord("2m", 2.0, 0.8))
                    .put(coord("3m", 0.5, -0.2)).put(coord("4m", 0.5, 1.7))
                val f = TileBadgeOverlay.filterCoords(coords)
                check(f.kept.isEmpty() && f.rejected == 4)
                check(f.sample.isNotBlank() && f.sample.contains("1m@"))
            }
            test("edge-sitting coordinates within tolerance are kept") { _, _ ->
                val coords = JSONArray().put(coord("1m", 0.0, 0.5)).put(coord("9m", 0.999, 0.5))
                val f = TileBadgeOverlay.filterCoords(coords)
                check(f.kept.size == 2 && f.rejected == 0)
            }
            test("blank tile and non-finite coordinates are rejected") { _, _ ->
                // org.json forbids NaN/Infinity, so a missing field (optDouble -> NaN)
                // is the way a non-finite value reaches the filter in practice.
                val coords = JSONArray().put(coord("", 0.5, 0.5))
                    .put(JSONObject().put("tile", "1m").put("ny", 0.5))
                val f = TileBadgeOverlay.filterCoords(coords)
                check(f.kept.isEmpty() && f.rejected == 2)
            }
            test("mixed capture keeps valid and samples rejected") { _, _ ->
                val coords = JSONArray().put(coord("1m", 0.2, 0.8)).put(coord("9m", 5.0, 5.0))
                val f = TileBadgeOverlay.filterCoords(coords)
                check(f.kept.size == 1 && f.rejected == 1 && f.sample.contains("9m@5.000,5.000"))
            }
            // canonicalTile must fold protocol honor letters and zero-red-fives onto
            // the game-view encodings so a hint can actually pair with an on-screen
            // tile. Without this, a `W`/`P`/`F` recommendation never matched `3z/5z/6z`
            // and the honor badges stayed "missing".
            fun canon(t: String) = TileBadgeOverlay.canonicalTile(t)
            test("canonicalTile folds honor letters onto z-form") { _, _ ->
                check(canon("E") == "1z" && canon("S") == "2z" && canon("W") == "3z" && canon("N") == "4z")
                check(canon("P") == "5z" && canon("F") == "6z" && canon("C") == "7z")
                // z-form is already canonical and passes through unchanged.
                check(canon("3z") == "3z" && canon("5z") == "5z" && canon("6z") == "6z")
            }
            test("canonicalTile keeps red five distinct from normal five") { _, _ ->
                check(canon("0m") == "5mr" && canon("0p") == "5pr" && canon("0s") == "5sr")
                // r-suffix form must NOT be rebuilt into a corrupt "55r"/"55r" key.
                check(canon("5mr") == "5mr" && canon("5sr") == "5sr" && canon("5pr") == "5pr")
                check(canon("5m") == "5m" && canon("5s") == "5s")
                check(canon("1p") == "1p" && canon("9s") == "9s")
            }
            test("canonicalTile trims and guards sentinels") { _, _ ->
                check(canon(" W ") == "3z" && canon("") == "" && canon("null") == "null" && canon("?") == "?")
            }
            // HandCoordinateMapper must map Unity-normalized coords through the
            // *source* viewport, not the overlay's compressed content frame, and must
            // subtract the overlay's measured on-screen origin. The real device runs
            // Unity at 2376x1080 (landscape) while the overlay content frame is only
            // 2256 wide because of the 120px cutout inset; mapping nx*2256 put every
            // badge ~60-120px off. These pure cases pin the source-width transform.
            fun mapping(sourceW: Int, sourceH: Int, viewportLeft: Int = 0, viewportTop: Int = 0,
                        viewportW: Int = sourceW, viewportH: Int = sourceH,
                        frameOriginX: Int = 0, frameOriginY: Int = 0) =
                HandCoordinateMapper.Mapping(sourceW, sourceH, viewportLeft, viewportTop,
                    viewportW, viewportH, frameOriginX, frameOriginY)
            test("mapper scales by source width, not overlay width") { _, _ ->
                // 2376-wide source, overlay content frame 2256 wide, cutout on the left
                // (frame origin x=120). The global x for nx=0.568 is 0.568*2376 = 1349.568;
                // toLocalPx subtracts the measured frame origin (120), so the overlay-local
                // x is 1349.568 - 120 = 1229.568 -> 1230.
                val m = mapping(2376, 1080, frameOriginX = 120, viewportW = 2376, viewportH = 1080)
                val px = HandCoordinateMapper.toLocalPx(0.568, 0.5, m)!!
                check(px.first == 1230) { "expected x=1230, got ${px.first}" }
            }
            test("mapper subtracts frame origin so the whole row shifts correctly") { _, _ ->
                val m = mapping(2376, 1080, frameOriginX = 120, viewportW = 2376, viewportH = 1080)
                // nx=0 (leftmost tile) must land at -120 locally (off the left of the
                // clipped frame), NOT at 0 — proving the origin is accounted for.
                val left = HandCoordinateMapper.toLocalPx(0.0, 0.5, m)!!
                check(left.first == -120) { "expected x=-120, got ${left.first}" }
            }
            test("mapper letterbox scales source into a smaller viewport") { _, _ ->
                // A letterboxed 2376x1080 render inside a 1920x1080 window (scale ~0.808)
                // with no left inset: nx=0.5 -> 1188*scale -> 960.
                val m = mapping(2376, 1080, viewportW = 1920, viewportH = 1080)
                val px = HandCoordinateMapper.toLocalPx(0.5, 0.5, m)!!
                check(px.first == 960) { "expected x=960, got ${px.first}" }
            }
            test("mapper rotates source into a portrait viewport") { _, _ ->
                // Screen rotated: source 1080x2376 (portrait) into 1080x2376 viewport.
                val m = mapping(1080, 2376, viewportW = 1080, viewportH = 2376)
                val px = HandCoordinateMapper.toLocalPx(0.5, 0.5, m)!!
                check(px.first == 540 && px.second == 1188) { "expected 540,1188 got ${px.first},${px.second}" }
            }
            test("mapper returns null when dimensions are unusable") { _, _ ->
                check(HandCoordinateMapper.toLocalPx(0.5, 0.5, mapping(0, 1080)) == null)
                check(HandCoordinateMapper.toLocalPx(0.5, 0.5, mapping(2376, 0)) == null)
                check(HandCoordinateMapper.toLocalPx(0.5, 0.5, mapping(2376, 1080, viewportW = 0)) == null)
                check(!HandCoordinateMapper.isUsable(mapping(0, 1080)))
                check(HandCoordinateMapper.isUsable(mapping(2376, 1080)))
            }
            test("mapper does not clamp out-of-source coordinates") { _, _ ->
                // A coordinate beyond the source (e.g. a fault) must stay beyond the
                // frame, not be clamped into [0,1] — surfacing the fault instead.
                val m = mapping(2376, 1080, viewportW = 2376, viewportH = 1080)
                val px = HandCoordinateMapper.toLocalPx(1.5, 0.5, m)!!
                check(px.first == 3564) { "expected x=3564 (unclamped), got ${px.first}" }
            }
            // computeBadgePlacement is the pure placement math that the overlay's
            // asynchronous render path runs *after* the frame is laid out. The
            // regression being pinned here: the mapping was previously measured
            // against a zero-size (not-yet-attached) frame, producing `no_origin`
            // and a detach before any badge could render. With the mapping now
            // supplied externally (measured post-layout), a valid mapping must
            // yield placed anchors even though the frame itself was 0x0 moments
            // earlier — exactly the "initial frame=0 but post-layout mapper usable"
            // case.
            fun hint(kind: String, tile: String, idx: Int) =
                TileBadgeOverlay.DiscardHint(kind, tile, 50.0, idx)
            fun view(tile: String, nx: Double, ny: Double) =
                TileBadgeOverlay.HookedTile(tile, nx, ny, false)
            test("computeBadgePlacement places anchors from a post-layout mapping") { _, _ ->
                val m = mapping(2376, 1080, frameOriginX = 120, viewportW = 2376, viewportH = 1080)
                val views = listOf(view("1m", 0.20, 0.90), view("2m", 0.28, 0.90), view("3m", 0.36, 0.90))
                val hints = listOf(hint("discard", "2m", 1))
                val placement = TileBadgeOverlay.computeBadgePlacement(hints, views, m)
                check(placement.placed.size == 1 && placement.ambiguous == 0 && placement.missing == 0)
                check(placement.placed.containsKey(1)) { "expected handIndex 1 to be placed" }
                // nx=0.28 -> 0.28*2376=665.28 -> -120 origin -> 545.28 -> 545.
                check(placement.placed[1]!!.first == 545) {
                    "expected x=545, got ${placement.placed[1]!!.first}"
                }
                // The anchor carries the raw projected y (no lift baked in); the
                // vertical offset is applied later against the measured badge height.
                check(placement.placed[1]!!.second == 972) {
                    "expected raw anchor y=972, got ${placement.placed[1]!!.second}"
                }
            }
            test("computeBadgePlacement rejects an unusable mapping instead of guessing") { _, _ ->
                // An unusable mapping must return empty rather than place a badge at
                // a guess, and it must not invent a card-height calibration either.
                val views = listOf(view("1m", 0.20, 0.90))
                val hints = listOf(hint("discard", "1m", 0))
                val bad = mapping(0, 1080)
                val placement = TileBadgeOverlay.computeBadgePlacement(hints, views, bad)
                check(placement.placed.isEmpty() && placement.missing == 0)
                check(placement.cardHeightPx == null)
                // Empty views also yields empty (the no_coords case).
                val valid = mapping(2376, 1080, viewportW = 2376, viewportH = 1080)
                val emptyViews = TileBadgeOverlay.computeBadgePlacement(hints, emptyList(), valid)
                check(emptyViews.placed.isEmpty())
            }
            test("cat palettes preserve recommendation ranks") { _, _ ->
                check(CatHintView.paletteIndex(0, true) == 0)
                check(CatHintView.paletteIndex(1, true) == 1)
                check(CatHintView.paletteIndex(2, true) == 2)
                check(CatHintView.paletteIndex(0, false) == 2)
            }
            test("computeBadgePlacement keeps the top hint on duplicate tiles") { _, _ ->
                val m = mapping(2376, 1080, viewportW = 2376, viewportH = 1080)
                val views = listOf(view("5m", 0.20, 0.90), view("5m", 0.30, 0.90))
                val hints = listOf(hint("discard", "5m", 0))
                val placement = TileBadgeOverlay.computeBadgePlacement(hints, views, m)
                check(placement.ambiguous == 0 && placement.placed[0]?.first == 475)
                check(placement.pairingTrace.contains("5m=5m:2")) { "expected duplicate pairing trace" }
            }
            // buildMapping is the pure decision behind BADGE_HIDDEN. Each failure must
            // name its own cause, so an unset source viewport is never reported as a
            // frame/origin fault (the old `no_origin` label conflated all three).
            test("buildMapping names source_missing when the payload has no dims") { _, _ ->
                val r = TileBadgeOverlay.buildMapping(0, 0, 2256, 1080, 120, 0, 0, 0, 2376, 1080)
                check(r.mapping == null && r.failure == FrameFailure.SOURCE_MISSING) {
                    "expected source_missing, got ${r.failure}"
                }
            }
            test("buildMapping names frame_unmeasured for a not-yet-laid-out frame") { _, _ ->
                val r = TileBadgeOverlay.buildMapping(2376, 1080, 0, 0, 0, 0, 0, 0, 2376, 1080)
                check(r.mapping == null && r.failure == FrameFailure.FRAME_UNMEASURED) {
                    "expected frame_unmeasured, got ${r.failure}"
                }
            }
            test("buildMapping names metrics_invalid when the viewport is empty") { _, _ ->
                val r = TileBadgeOverlay.buildMapping(2376, 1080, 2256, 1080, 120, 0, 0, 0, 0, 0)
                check(r.mapping == null && r.failure == FrameFailure.METRICS_INVALID) {
                    "expected metrics_invalid, got ${r.failure}"
                }
            }
            // The regression #82 shape: the first pass sees a 0x0 frame AND no source
            // dims (stale bare-array Lua capture). Once the frame is laid out and the
            // wrapped payload arrives — source 2376x1080, overlay frame 2256x1080 at
            // x=120 — the same hints must place, proving the retry path recovers a
            // badge instead of dying on the first null mapping.
            test("initial 0x0 frame then measured 2376x1080 source places the badge") { _, _ ->
                val before = TileBadgeOverlay.buildMapping(2376, 1080, 0, 0, 0, 0, 0, 0, 2376, 1080)
                check(before.mapping == null && before.failure == FrameFailure.FRAME_UNMEASURED)
                val after = TileBadgeOverlay.buildMapping(2376, 1080, 2256, 1080, 120, 0, 0, 0, 2376, 1080)
                val m = after.mapping ?: error("expected a usable mapping after layout")
                check(after.failure == null && HandCoordinateMapper.isUsable(m))
                // Same hand as the device log: 14 tiles, the badge on hand index 12.
                val views = listOf(
                    view("1p", 0.2091, 0.9228), view("2p", 0.2489, 0.9019),
                    view("8p", 0.2887, 0.9042), view("1s", 0.3285, 0.9101),
                    view("2s", 0.7406, 0.9150),
                )
                val hints = listOf(hint("discard", "2s", 12))
                val placement = TileBadgeOverlay.computeBadgePlacement(hints, views, m)
                check(placement.placed.size == 1 && placement.ambiguous == 0 && placement.missing == 0)
                // 0.7406 * 2376 = 1759.6656 - 120 origin -> 1639.67.
                val anchor = placement.placed[12] ?: error("expected hand index 12 placed")
                check(anchor.first == 1640) { "expected x=1640, got ${anchor.first}" }
                // 0.9150 * 1080 = 988.2 -> 988: the raw projected anchor, *not* lifted.
                check(anchor.second == 988) { "expected raw anchor y=988, got ${anchor.second}" }
                // The sample's own card-height estimate is real but small (the row is
                // nearly flat: mean dy 0.015 * 1080 = 16.2 px, clamped up to the 0.045
                // floor = 48.6 -> 49 px), which is exactly why it does not drive the
                // offset: 0.7 * 49 = 34 px would leave the badge ~40 px too low.
                val cardH = placement.cardHeightPx ?: error("expected a card height estimate")
                check(cardH == 49) { "expected clamped card height 49, got $cardH" }
                // The offset is frame-normalized: 0.0694 * 1080 = 75 px, matching the
                // 997 -> 922 px anchor-to-tile-top distance in the screenshot.
                check(TileBadgeOverlay.tileTopOffsetPx(1080) == 75) { "expected offset 75" }
            }
            // Vertical placement, pinned to the reference screenshot: the badge's
            // bottom edge must land on the tile's top edge, not on the tile face.
            // Device evidence: anchorY = 0.9228 * 1080 = 997 px, tile top ~922 px
            // (75 px anchor->tile-top), badge height 70..80 px. Data payload gave
            // tileTop 922 / badgeH 70 / top 852 / bottom 922.
            // Compact labels retain the card-top anchor while reducing width and height.
            test("deriveBadgeSizePx sizes a compact label at 1080p") { _, _ ->
                val m = mapping(2376, 1080, frameOriginX = 120, viewportW = 2376, viewportH = 1080)
                // Live projection: adjacent standard tiles differ by nx ≈ 0.0399.
                val views = listOf(
                    view("1p", 0.2091, 0.9228), view("2p", 0.2489, 0.9228),
                    view("3p", 0.2887, 0.9228), view("4p", 0.3285, 0.9228),
                )
                val size = TileBadgeOverlay.deriveBadgeSizePx(views, m, 2256, 1080)
                    ?: error("expected a derived card size")
                // 0.0398 * 2376 = 94.6 px pitch (screenshot: 94-95 px).
                check(size.width in 65..68) { "expected a 65-68 px label width, got ${size.width}" }
                // Reference label height: 5% of a 1080 px frame.
                check(size.height in 52..56) {
                    "expected a ~54 px label height, got ${size.height}"
                }
                check(size.source == "pitch") { "expected pitch sizing, got ${size.source}" }
            }
            test("deriveBadgeSizePx ignores the widened drawn-tile step") { _, _ ->
                val m = mapping(2376, 1080, viewportW = 2376, viewportH = 1080)
                // Resting pitch 0.04; the rightmost (drawn) tile is 0.056 away — the
                // ~30 px gap seen in the screenshot. That step must not widen the badge.
                val views = listOf(
                    view("1m", 0.10, 0.92), view("2m", 0.14, 0.92),
                    view("3m", 0.18, 0.92), view("4m", 0.22, 0.92),
                    view("9m", 0.276, 0.92),
                )
                val size = TileBadgeOverlay.deriveBadgeSizePx(views, m, 2376, 1080)
                    ?: error("expected a derived card size")
                check(size.width in 65..68) {
                    "drawn-tile step leaked into the width: ${size.width}"
                }
            }
            test("deriveBadgeSizePx falls back to a card-width pitch without a sample") { _, _ ->
                // One anchored hint: no neighbour, so no pitch can be measured. The
                // label remains 70% of a hand tile pitch.
                val m = mapping(2376, 1080, viewportW = 2376, viewportH = 1080)
                val single = listOf(view("1m", 0.5, 0.92))
                val fallback = TileBadgeOverlay.deriveBadgeSizePx(single, m, 2376, 1080)
                    ?: error("expected the normalized fallback")
                check(fallback.source == "fallback") { "expected fallback, got ${fallback.source}" }
                check(fallback.width in 65..68) { "expected ~67 px, got ${fallback.width}" }
                check(fallback.height in 52..56) { "expected ~54 px, got ${fallback.height}" }
                // No mapping: use the same compact fallback.
                val noMapping = TileBadgeOverlay.deriveBadgeSizePx(single, null, 2376, 1080)
                    ?: error("expected a size even without a mapping")
                check(noMapping.width in 65..68 && noMapping.height in 52..56)
            }
            test("deriveBadgeSizePx clamps a distorted or single-tile hand") { _, _ ->
                val m = mapping(2000, 1000, viewportW = 2000, viewportH = 1000)
                // Absurd pitch (0.3 of the width) cannot become a banner.
                val wide = listOf(view("1m", 0.10, 0.9), view("2m", 0.40, 0.9))
                val clamped = TileBadgeOverlay.deriveBadgeSizePx(wide, m, 2000, 1000)
                    ?: error("expected a size")
                check(clamped.width == (0.055 * 2000 * 0.70).toInt()) {
                    "expected the scaled max pitch clamp, got ${clamped.width}"
                }
                // A near-zero step cannot become a sliver either.
                val tiny = listOf(view("1m", 0.10, 0.9), view("2m", 0.1005, 0.9))
                val sliver = TileBadgeOverlay.deriveBadgeSizePx(tiny, m, 2000, 1000)!!
                check(sliver.width == (0.03 * 2000 * 0.70).toInt()) {
                    "expected the scaled min pitch clamp, got ${sliver.width}"
                }
            }
            test("deriveBadgeSizePx scales with the frame height") { _, _ ->
                // 1440p: label height ~72 px; width ~89 px at 70% tile pitch.
                val m = mapping(3168, 1440, viewportW = 3168, viewportH = 1440)
                val views = listOf(
                    view("1p", 0.20, 0.92), view("2p", 0.2399, 0.92),
                    view("3p", 0.2798, 0.92),
                )
                val size = TileBadgeOverlay.deriveBadgeSizePx(views, m, 3168, 1440)
                    ?: error("expected a size")
                check(size.width in 87..90) { "expected ~89 px, got ${size.width}" }
                check(size.height in 70..74) { "expected ~72 px, got ${size.height}" }
                // Degenerate frame: no card size, the caller keeps the text measurement.
                check(TileBadgeOverlay.deriveBadgeSizePx(views, m, 0, 0) == null)
            }
            test("a compact badge still sits flush above its tile") { _, _ ->
                val m = mapping(2376, 1080, frameOriginX = 120, viewportW = 2376, viewportH = 1080)
                val views = listOf(
                    view("1p", 0.2091, 0.9228), view("2p", 0.2489, 0.9228),
                    view("8p", 0.2887, 0.9228),
                )
                val placement = TileBadgeOverlay.computeBadgePlacement(
                    listOf(hint("discard", "2p", 1)), views, m
                )
                val size = TileBadgeOverlay.deriveBadgeSizePx(views, m, 2256, 1080)!!
                val anchorY = placement.placed[1]?.second ?: error("expected a placed anchor")
                val offset = TileBadgeOverlay.tileTopOffsetPx(1080)
                val vertical = TileBadgeOverlay.computeBadgeTop(
                    anchorY, offset, size.height, 0, 1080
                ) ?: error("expected a vertical placement")
                // Bottom on the tile top, whole card above it: the badge never covers
                // the tile it points at (54 px fits above 922).
                check(vertical.top + vertical.height == anchorY - offset)
                check(vertical.height == size.height)
                check(vertical.top >= 0) { "compact badge left the frame: ${vertical.top}" }
                check(vertical.top >= 700) { "badge grew into unrelated UI: ${vertical.top}" }
            }
            test("computeBadgeTop puts the badge bottom on the tile top edge") { _, _ ->
                val vertical = TileBadgeOverlay.computeBadgeTop(
                    anchorY = 997, tileTopOffsetPx = 75, badgeHeightPx = 70,
                    gapPx = 0, frameHeight = 1080,
                ) ?: error("expected a vertical placement")
                check(vertical.tileTop == 922) { "expected tileTop=922, got ${vertical.tileTop}" }
                check(vertical.top == 852) { "expected top=852, got ${vertical.top}" }
                check(vertical.top + vertical.height == 922) {
                    "badge bottom must touch the tile top, got ${vertical.top + vertical.height}"
                }
                check(vertical.height == 70)
            }
            test("computeBadgeTop subtracts the measured badge height, not a frame fraction") { _, _ ->
                // The old bug used a fixed 0.0333 * 1080 = 36 px lift *as the top*,
                // ignoring the badge height: top=961 with the tile top at 922.
                val short = TileBadgeOverlay.computeBadgeTop(997, 75, 60, 4, 1080)!!
                val tall = TileBadgeOverlay.computeBadgeTop(997, 75, 80, 4, 1080)!!
                check(short.top == 858 && tall.top == 838) {
                    "expected 858/838, got ${short.top}/${tall.top}"
                }
                // A taller badge always starts higher; the bottom stays fixed.
                check(short.top + 60 == 922 - 4 && tall.top + 80 == 922 - 4)
            }
            test("computeBadgeTop scales with the render height") { _, _ ->
                // Same relative framing rendered taller: the offset is derived from the
                // frame height (a fraction), not from a hard-coded pixel value.
                val hd = TileBadgeOverlay.computeBadgeTop(1330, 100, 93, 0, 1440)!!
                check(hd.tileTop == 1230 && hd.top + hd.height == 1230) {
                    "expected bottom=1230, got ${hd.top + hd.height}"
                }
                // The same ratio that yields 75 px at 1080p yields 100 px here.
                check(TileBadgeOverlay.tileTopOffsetPx(1440) == 100)
                // A larger frame must place the badge proportionally higher.
                val small = TileBadgeOverlay.computeBadgeTop(997, 75, 70, 0, 1080)!!
                check(small.top.toDouble() / 1080.0 in 0.78..0.80) {
                    "unexpected relative top ${small.top.toDouble() / 1080.0}"
                }
            }
            test("computeBadgeTop clamps instead of leaving the frame or covering the tile") { _, _ ->
                // Row near the bottom: the whole badge fits above it, so nothing is lost.
                val low = TileBadgeOverlay.computeBadgeTop(1070, 75, 70, 0, 1080)!!
                check(low.top + low.height == 995)
                // Row near the top: the badge cannot leave the frame; it is shrunk to
                // the room above the tile rather than pushed down over the tile.
                val high = TileBadgeOverlay.computeBadgeTop(80, 75, 70, 4, 1080)!!
                check(high.top == 0) { "expected top=0, got ${high.top}" }
                check(high.height == 1) { "expected shrunk height=1, got ${high.height}" }
                // No room at all above the tile: skip rather than misplace.
                check(TileBadgeOverlay.computeBadgeTop(70, 75, 70, 4, 1080) == null)
                // Degenerate frame: skip.
                check(TileBadgeOverlay.computeBadgeTop(500, 75, 70, 0, 0) == null)
            }
            test("estimateCardHeightPx uses the pitch when tiles share one row") { _, _ ->
                // Synthetic flat row: five tiles on exactly one ny, pitch 0.0398 * 2376 = 94.6 px.
                val m = mapping(2376, 1080, frameOriginX = 120, viewportW = 2376, viewportH = 1080)
                val views = listOf(
                    view("1p", 0.2091, 0.92), view("2p", 0.2489, 0.92),
                    view("8p", 0.2887, 0.92), view("1s", 0.3285, 0.92),
                    view("2s", 0.7406, 0.92),
                )
                val h = TileBadgeOverlay.estimateCardHeightPx(views, m)
                check(h != null && h in 60..90) { "expected ~70 px via pitch, got $h" }
                // The frame-normalized offset is independent of that estimate and keeps
                // the reference distance (75 px at 1080p).
                check(TileBadgeOverlay.tileTopOffsetPx(1080) == 75)
                check(TileBadgeOverlay.tileTopOffsetPx(1440) == 100) { "expected 1440 -> 100" }
            }
            test("estimateCardHeightPx prefers the drawn-tile row offset and clamps it") { _, _ ->
                // A pickup tile drawn one full card height above the row: dy = 0.10.
                val m = mapping(2376, 1080, viewportW = 2376, viewportH = 1080)
                val drawn = listOf(view("1m", 0.10, 0.90), view("2m", 0.20, 0.80))
                check(TileBadgeOverlay.estimateCardHeightPx(drawn, m) == 108)
                // Absurd sample (dy = 0.5 of the screen) is clamped into a plausible band.
                val absurd = listOf(view("1m", 0.10, 0.90), view("2m", 0.20, 0.40))
                val clamped = TileBadgeOverlay.estimateCardHeightPx(absurd, m)!!
                check(clamped == 173) { "expected clamp to 0.16*1080=172.8 -> 173, got $clamped" }
                // A single tile with no pitch and no row offset cannot be calibrated.
                check(TileBadgeOverlay.estimateCardHeightPx(listOf(view("1m", 0.5, 0.9)), m) == null)
            }
            // Full pipeline: device coordinates through placement and the vertical
            // formula, asserting the badge never covers the tile it points at.
            test("device row places badges flush above each hinted tile") { _, _ ->
                val m = mapping(2376, 1080, frameOriginX = 120, viewportW = 2376, viewportH = 1080)
                val views = listOf(
                    view("1m", 0.15, 0.9228), view("2p", 0.25, 0.9228),
                    view("9m", 0.35, 0.9228), view("5s", 0.45, 0.9228),
                )
                val hints = listOf(
                    hint("discard", "2p", 1), hint("discard", "1m", 0),
                )
                val placement = TileBadgeOverlay.computeBadgePlacement(hints, views, m)
                check(placement.placed.size == 2)
                check(placement.cardHeightPx != null) { "expected a card height estimate" }
                val offset = TileBadgeOverlay.tileTopOffsetPx(1080)
                for (idx in listOf(0, 1)) {
                    val anchorY = placement.placed[idx]!!.second
                    val v = TileBadgeOverlay.computeBadgeTop(anchorY, offset, 70, 0, 1080)!!
                    check(v.top + v.height == anchorY - offset) {
                        "badge bottom must equal tileTop for hand index $idx"
                    }
                    check(v.top < anchorY - 70) { "badge must be fully above the tile" }
                }
            }
            // Button-recommendation mapping is pure: it folds each action kind onto a
            // button key and gates on the buttons the game actually reports visible.
            fun btnRec(kind: String, prob: Double) =
                JSONObject().put("kind", kind).put("policyProbability", prob)
            test("resolveButtonHints maps action kinds onto visible buttons") { _, _ ->
                val rows = JSONArray().put(btnRec("pon", 0.9)).put(btnRec("chi", 0.7))
                    .put(btnRec("pass", 0.3))
                val hints = TileBadgeOverlay.resolveButtonHints(rows, setOf("pon", "chi", "pass", "kan"))
                check(hints.size == 3)
                check(hints[0].key == "pon" && hints[0].displayName == "碰")
                check(hints[1].key == "chi" && hints[1].displayName == "吃")
                check(hints[2].key == "pass" && hints[2].displayName == "跳过")
                check(hints[0].weight == 90.0) { "expected 90%, got ${hints[0].weight}" }
            }
            test("resolveButtonHints drops kinds whose button is not visible") { _, _ ->
                val rows = JSONArray().put(btnRec("pon", 0.9)).put(btnRec("hora", 0.8))
                // Only the pon button is visible; hora has no anchor and must vanish.
                val hints = TileBadgeOverlay.resolveButtonHints(rows, setOf("pon"))
                check(hints.size == 1 && hints[0].key == "pon")
            }
            test("resolveButtonHints collapses multiple combinations of one kind") { _, _ ->
                // Two candidate chi combinations (different tiles) collapse to one
                // button label on the first (highest-ranked) entry.
                val rows = JSONArray().put(btnRec("chi", 0.8)).put(btnRec("chi", 0.2))
                val hints = TileBadgeOverlay.resolveButtonHints(rows, setOf("chi"))
                check(hints.size == 1 && hints[0].weight == 80.0)
            }
            test("resolveButtonHints ignores discard kinds and empty rows") { _, _ ->
                val rows = JSONArray().put(btnRec("discard", 0.9)).put(btnRec("riichi", 0.8))
                check(TileBadgeOverlay.resolveButtonHints(rows, setOf("pon")).isEmpty())
                check(TileBadgeOverlay.resolveButtonHints(JSONArray(), setOf("pon")).isEmpty())
            }
            test("resolveButtonHints caps the output at four button labels") { _, _ ->
                val rows = JSONArray().put(btnRec("pon", 0.9)).put(btnRec("chi", 0.8))
                    .put(btnRec("kan", 0.7)).put(btnRec("kita", 0.6)).put(btnRec("hora", 0.5))
                val hints = TileBadgeOverlay.resolveButtonHints(
                    rows, setOf("pon", "chi", "kan", "kita", "hora"))
                check(hints.size == 4)
            }
            // System-overlay mode-switch and configuration-change replay: the floating
            // window must recover the *full* result (with `analysis`) while the badge
            // overlay and in-game layer only ever get the trimmed hint.
            fun fullResult() = JSONObject("""
                {"status":"live","canAct":true,"inputPending":false,"message":"可操作",
                 "round":"E1","players":4,
                 "hand":["1m","2m","3m"],"recommendations":[{"kind":"discard","tile":"1m"}],
                 "legalOperations":[{"type":1}],
                 "analysis":{"hand13":{"tiles":["1m","2m","3m"]}}}
            """.trimIndent())
            test("trimHint keeps hint fields and drops the floating-window-only analysis") { _, _ ->
                val trimmed = AiOverlayService.trimHint(fullResult())
                check(trimmed.has("status") && trimmed.has("canAct") && trimmed.has("inputPending"))
                check(trimmed.has("hand") && trimmed.has("recommendations") && trimmed.has("legalOperations"))
                check(!trimmed.has("analysis")) { "badge hint must not carry analysis" }
                check(!trimmed.has("message") && !trimmed.has("round") && !trimmed.has("players")) {
                    "badge hint must not carry floating-window chrome"
                }
            }
            test("full result keeps analysis so the floating window can re-render it") { _, _ ->
                // The floating window's re-render reads `analysis`, `round` and
                // `players`; the replay cache stores the untouched result string, so
                // those keys must survive a string round-trip.
                val roundTrip = JSONObject(fullResult().toString())
                check(roundTrip.has("analysis") && roundTrip.has("round") && roundTrip.has("players"))
                check(roundTrip.getJSONObject("analysis").has("hand13"))
            }
            DiagnosticsClearChecks.run(targetContext)
            sendStatus(0, Bundle().apply { putString("stream", "PASS diagnostic cleanup scopes, rotations and legacy migration\n") })
            ApkUpdateChecks.run(targetContext)
            sendStatus(0, Bundle().apply { putString("stream", "PASS APK update identity, integrity, routing and cancellation checks\n") })
            CatalogChecks.run(targetContext, context)
            sendStatus(0, Bundle().apply { putString("stream", "PASS unlock catalog update, recovery and GitHub routes\n") })
            DisplayModeChecks.run(targetContext)
            sendStatus(0, Bundle().apply { putString("stream", "PASS assistant display-mode switch (overlay vs in-game Hook) and provider broadcast\n") })
            if (!controllerOnly) {
                ModelContractChecks.run(targetContext, context)
                sendStatus(0, Bundle().apply { putString("stream", "PASS ONNX/JNI contracts: four-player and sanma, bool/float masks and crossed-pair rejection\n") })
            }
            output.putString("stream", "$tests controller checks passed\n")
            finish(-1, output)
        }.onFailure {
            output.putString("stream", it.stackTraceToString()); finish(1, output)
        }
    }
}
