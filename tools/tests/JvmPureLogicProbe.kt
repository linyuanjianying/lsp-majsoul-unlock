// JVM-only regression probe for TileBadgeOverlay / CatHintView pure logic.
// Uses -Xfriend-paths so Kotlin `internal` members are reachable without patching sources.
// No Android stubs: only the pure companion functions are touched (no Context/View instances).
import org.json.JSONArray
import org.json.JSONObject
import com.yefeng.majmax.hookprobe.manager.AutoDiscardController
import com.yefeng.majmax.hookprobe.manager.RematchState
import com.yefeng.majmax.hookprobe.manager.TileBadgeOverlay as TBO
import com.yefeng.majmax.hookprobe.manager.TileBadgeOverlay.DiscardHint
import com.yefeng.majmax.hookprobe.manager.TileBadgeOverlay.HookedTile
import com.yefeng.majmax.hookprobe.manager.HandCoordinateMapper

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
    if (cond) { passed++; println("PASS  $name") }
    else { failed++; println("FAIL  $name  ${detail()}") }
}

// 2376x1080 source, overlay frame same size at origin 0,0 -> local px = round(n * dim).
private fun mapping(sw: Int = 2376, sh: Int = 1080, originX: Int = 0): HandCoordinateMapper.Mapping =
    HandCoordinateMapper.Mapping(sw, sh, 0, 0, sw, sh, originX, 0)

private fun discardHint(tile: String, idx: Int, rank: Int = 0): DiscardHint = DiscardHint("discard", tile, 50.0, idx, rank)
private fun tileView(tile: String, nx: Double, ny: Double = 0.9): HookedTile = HookedTile(tile, nx, ny, false)

fun main() {
    val m = mapping()

    // 1. Duplicate ordinary tiles: each copy consumed once, leftmost first.
    run {
        val views = listOf<HookedTile>(tileView("5m", 0.20), tileView("5m", 0.30))
        val one = TBO.computeBadgePlacement(listOf(discardHint("5m", 0)), views, m)
        check("dup tile: single hint picks left (x=475)", one.placed[0]?.first == 475) {
            "got ${one.placed[0]}"
        }
        val two = TBO.computeBadgePlacement(
            listOf(discardHint("5m", 0), discardHint("5m", 1)), views, m)
        check("dup tile: 2 hints -> distinct anchors 475 / 713", 
            two.placed[0]?.first == 475 && two.placed[1]?.first == 713) { "got ${two.placed}" }
        check("dup tile: 3 hints -> third is missing", 
            TBO.computeBadgePlacement(
                listOf(discardHint("5m", 0), discardHint("5m", 1), discardHint("5m", 2)), views, m).missing == 1)
    }

    // 2. Two identical recommendations must not reuse one anchor.
    run {
        val p = TBO.computeBadgePlacement(
            listOf(discardHint("3p", 4), discardHint("3p", 5)),
            listOf<HookedTile>(tileView("3p", 0.10), tileView("3p", 0.15)), m)
        val a = p.placed[4]; val b = p.placed[5]
        check("identical recs: 2 placed, distinct anchors",
            a != null && b != null && a != b && p.missing == 0) { "got $p" }
    }

    // 3. Red five must not match a normal five (and vice versa).
    run {
        val normal = listOf<HookedTile>(tileView("5s", 0.40))
        val red = TBO.computeBadgePlacement(listOf(discardHint("5sr", 0)), normal, m)
        check("red5 hint vs normal5 view -> missing", red.placed.isEmpty() && red.missing == 1) { "got $red" }
        val normalVsRed = TBO.computeBadgePlacement(
            listOf(discardHint("5s", 0)), listOf<HookedTile>(tileView("5sr", 0.40)), m)
        check("normal5 hint vs red5 view -> missing",
            normalVsRed.placed.isEmpty() && normalVsRed.missing == 1) { "got $normalVsRed" }
        // zero-encoding and r-suffix are the same aka five.
        val aka = TBO.computeBadgePlacement(listOf(discardHint("0s", 0)), listOf<HookedTile>(tileView("5sr", 0.40)), m)
        check("0s hint matches 5sr view (same aka)",
            aka.placed[0]?.first == 950 && aka.missing == 0) { "got $aka" }
        check("canonicalTile(0s)=5sr, (5s)=5s",
            TBO.Companion.canonicalTile("0s") == "5sr" &&
            TBO.Companion.canonicalTile("5s") == "5s")
    }

    // 4. Different recommendations keep their original hand rank (index), not a new sequence.
    run {
        val views = listOf<HookedTile>(tileView("1m", 0.20), tileView("2m", 0.25), tileView("9p", 0.70))
        val p = TBO.computeBadgePlacement(
            listOf(discardHint("9p", 11), discardHint("1m", 3), discardHint("2m", 7)), views, m)
        check("ranks preserved: keys 11,3,7", p.placed.keys == setOf(11, 3, 7)) { "got ${p.placed.keys}" }
        // 0.20*2376=475.2->475 ; 0.25*2376=594->594 ; 0.70*2376=1663.2->1663
        check("ranks map to own tiles",
            p.placed[3]!!.first == 475 && p.placed[7]!!.first == 594 && p.placed[11]!!.first == 1663) {
            "got ${p.placed}"
        }
    }

    // 5. Empty/unusable coordinates first, then valid -> placement works (no sticky failure).
    run {
        val views = listOf<HookedTile>(tileView("2s", 0.7406))
        val emptyViews = TBO.computeBadgePlacement(listOf(discardHint("2s", 12)), emptyList(), m)
        check("empty views -> nothing placed", emptyViews.placed.isEmpty()) { "got $emptyViews" }
        val badMapping = mapping(0, 1080)
        val bad = TBO.computeBadgePlacement(listOf(discardHint("2s", 12)), views, badMapping)
        check("unusable mapping -> nothing placed, no cardHeight",
            bad.placed.isEmpty() && bad.missing == 0 && bad.cardHeightPx == null) { "got $bad" }
        val good = TBO.computeBadgePlacement(listOf(discardHint("2s", 12)), views, m)
        check("valid coords after empty -> placed", good.placed[12]?.first == 1760) { "got $good" }
        check("same inputs are idempotent",
            good == TBO.computeBadgePlacement(listOf(discardHint("2s", 12)), views, m))
    }

    // 7. resolveButtonHints: kind mapping, visibility gating, collapsing, rank order.
    run {
        val rows = JSONArray("""
            [{"kind":"pon","weight":80},
             {"kind":"pon","weight":70},
             {"kind":"chi","weight":60},
             {"kind":"discard","weight":99},
             {"kind":"kan","weight":50}]
        """.trimIndent())
        val hints = TBO.Companion.resolveButtonHints(rows, setOf("pon", "chi", "pass"))
        check("button hints: pon+chi only (kan hidden, discard ignored)",
            hints.map { it.kind } == listOf("pon", "chi")) { "got ${hints.map { it.kind }}" }
        check("button hints: pon collapsed to first rank 0",
            hints.first().rank == 0 && hints.first().weight == 80.0) { "got ${hints.first()}" }
        check("button hints: chi keeps original row rank 2", hints[1].rank == 2) { "got ${hints[1]}" }
        check("button hints: keys + display names",
            hints[0].key == "pon" && hints[0].displayName == "碰" && hints[1].displayName == "吃")
        check("button hints: empty rows -> empty", 
            TBO.Companion.resolveButtonHints(JSONArray(), setOf("pon")).isEmpty())
        check("button hints: nothing visible -> empty",
            TBO.Companion.resolveButtonHints(rows, emptySet()).isEmpty())
    }

    run {
        fun state(rev: Int = 1) = JSONObject("""{"status":"live","canAct":true,"connection":"7","revision":$rev,"sourceGeneration":3,"sourceSequence":8,"seat":0,"players":4,"hand":["5mr"],"legalOperations":[{"type":1,"combination":[]}],"autoContext":{"riichi":false},"recommendations":[{"kind":"discard","tile":"5mr","tsumogiri":false}]}""")
        fun ack(id: String = "") = JSONArray().put(JSONObject().put("id",id).put("state","manual")).toString()
        val gate = AutoDiscardController({}, {0L}, {0L})
        gate.observe(state()); gate.toggle()
        check("auto queued before handover", gate.poll(null) != null)
        gate.poll(ack()); check("manual preserves enabled",gate.isEnabled())
        gate.observe(state()); check("manual decision never replays",gate.poll(null)==null)
        gate.observe(state(2)); check("new decision resumes",gate.poll(null)!=null)
        val next=gate.poll(null)
        gate.poll(ack("old-command")); check("old manual ack ignored",gate.poll(null)==next)
        gate.setEnabled(false); gate.observe(state(3))
        check("explicit off remains off",!gate.isEnabled() && gate.poll(null)==null)
        val early=AutoDiscardController({}, {0L}, {0L}, initialEnabled=true)
        early.observe(state().put("canAct",false)); early.poll(ack()); early.observe(state())
        check("manual before offer blocks same decision",early.isEnabled() && early.poll(null)==null)
        early.observe(state(2)); check("early handover resumes new decision",early.poll(null)!=null)
        check("rematch without unattended", RematchState(enabled=true,unattended=false).active)
        check("rematch off stays off", !RematchState(enabled=false,unattended=true).active)
        check("demo never rematches", !RematchState(enabled=true,demo=true).active)
    }

    run {
        fun state(rev: Int = 1) = JSONObject("""{"status":"live","canAct":true,"connection":"7","revision":$rev,"sourceGeneration":3,"sourceSequence":8,"seat":0,"players":4,"hand":["5mr"],"legalOperations":[{"type":1,"combination":[]}],"autoContext":{"riichi":false},"recommendations":[{"kind":"discard","tile":"5mr","tsumogiri":false}]}""")
        fun ack(id: String, status: String) = JSONArray().put(JSONObject().put("id",id).put("state",status)).toString()
        fun wire(rev: Int) = state(rev).put("input", JSONObject("""{"method":".lq.FastTest.inputOperation","payload":{"type":1,"tile":"0m","moqie":false}}"""))
        val gate = AutoDiscardController({}, {0L}, {0L})
        gate.observe(state()); gate.toggle()
        val first=JSONObject(checkNotNull(gate.poll(null))).getString("id")
        gate.poll(ack(first,"submitted")); gate.poll(ack(first,"accepted"))
        gate.observe(state(2))
        check("accepted ack alone cannot advance without wire confirmation",gate.poll(null)==null)
        gate.observe(wire(1)); gate.observe(state(2))
        val second=JSONObject(checkNotNull(gate.poll(null))).getString("id")
        check("wire confirmation advances to a new command", first!=second)
        gate.poll(ack(first,"accepted")); gate.poll(ack(first,"stale")); gate.poll(ack(first,"manual"))
        check("stale acknowledgements preserve current command",JSONObject(checkNotNull(gate.poll(null))).getString("id")==second)
        gate.poll(ack(second,"submitted")); gate.poll(ack(second,"accepted"))
        gate.observe(wire(2)); gate.observe(state(3))
        check("current command advances after all confirmations",gate.poll(null)!=null)
    }

    run {
        fun panel(json: String) = com.yefeng.majmax.hookprobe.manager.RecommendationPanelOverlay.dataFor(JSONObject(json))
        val missing = panel("""{"analysis":{"hand14":{}},"recommendations":[{"kind":"discard","tile":"E"}]}""")
        check("missing shanten preserves recommendations", missing.getJSONArray("rows").length() == 1 && missing.getInt("shanten") == -99)
        val fallback = panel("""{"analysis":{"hand13":{},"hand14":{"shanten":2}}}""")
        check("missing hand13 shanten falls back to hand14", fallback.getInt("shanten") == 2)
        val ready = panel("""{"analysis":{"hand13":{"shanten":0,"win_scores":[{"tile":"5s"}]}},"recommendations":[{"kind":"discard","tile":"E"}]}""")
        check("explicit zero shanten shows wait scores", ready.getJSONArray("rows").length() == 0 && ready.getJSONArray("scores").length() == 1)
        val unknown = panel("""{"recommendations":[{"kind":"discard","tile":"E"}]}""")
        check("absent analysis preserves recommendations", unknown.getInt("shanten") == -99 && unknown.getJSONArray("rows").length() == 1)
    }

    println("\n=== ${passed} passed, ${failed} failed ===")
    if (failed != 0) kotlin.system.exitProcess(1)
}
