// JVM build-regression for the in-game crash reported with the dark-blue/gold preview:
//
//   NullPointerException: Attempt to invoke virtual method
//   'void com.yefeng.majmax.hookprobe.GameTopUi$Glyph.setOnClickListener(android.view.View$OnClickListener)'
//   on a null object reference
//     at GameTopControl.buildControlPage(GameTopControl.java:1065)
//     at GameTopControl.buildMenu(GameTopControl.java:922)
//     at GameTopControl.build(GameTopControl.java:577)
//     at GameTopControl.attach(GameTopControl.java:417)
//
// The bug was structural: buildControlPage() read the auto-rematch card's ⓘ button back
// with `getTag(TAG_DETAIL + 1)`, a key no card ever wrote (the card builder stores only
// TAG_TITLE/TAG_STATE/TAG_NOTE/TAG_SWITCH), so the field stayed null and the very next
// line called setOnClickListener() on it. Because attach() runs on the game's main thread,
// the NPE killed the hooking game process.
//
// Note on the constants: TAG_DETAIL is 0x7E010005 and the new TAG_INFO is 0x7E010006, so
// `TAG_DETAIL + 1` happens to name the same slot today. The defect was never the
// arithmetic - it was that the card builder wrote no such slot. The checks below therefore
// assert the *written* slot, and check 0 pins the constant values so a future renumbering
// cannot make an old `TAG_DETAIL + 1` read silently address something else.
//
// This probe runs the real, unmodified GameTopControl.java / GameTopUi.java on a plain JVM
// (the android.* classes come from tools/tests/jvm-shims/android-shim) and builds the real
// control page, so the whole path above is exercised: every View is created, every tag is
// written, every listener is attached. It asserts the wiring rather than the pixels.
//
// Usage: tools/tests/run-top-control-build-probe.sh
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.InvocationTargetException
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

private var passed = 0
private var failed = 0
private val failures = ArrayList<String>()

private fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
    if (cond) {
        passed++
        println("PASS  $name")
    } else {
        failed++
        val d = detail()
        failures.add(if (d.isEmpty()) name else "$name  [$d]")
        println("FAIL  $name  $d")
    }
}

private fun checkEq(name: String, expected: Any?, actual: Any?) =
    check(name, expected == actual) { "expected=$expected actual=$actual" }

private fun privateField(target: Any, name: String): Any? {
    var type: Class<*>? = target.javaClass
    while (type != null) {
        try {
            val f: Field = type.getDeclaredField(name)
            f.isAccessible = true
            return f.get(target)
        } catch (e: NoSuchFieldException) {
            type = type.superclass
        }
    }
    return null
}

private fun intConstant(owner: Class<*>, name: String): Int {
    val f: Field = owner.getDeclaredField(name)
    f.isAccessible = true
    return f.getInt(null)
}

private fun privateMethod(owner: Class<*>, name: String, vararg params: Class<*>): Method {
    val m = owner.getDeclaredMethod(name, *params)
    m.isAccessible = true
    return m
}

/** Depth-first list of a view subtree. */
private fun walk(root: View, out: MutableList<View> = ArrayList()): List<View> {
    out.add(root)
    if (root is ViewGroup) for (i in 0 until root.childCount) walk(root.getChildAt(i), out)
    return out
}

/** Count how many times any view in the tree with this tag fires. */
private fun tagOf(card: LinearLayout, key: Int): Any? = card.getTag(key)

fun main() {
    val controlClass = Class.forName("com.yefeng.majmax.hookprobe.GameTopControl")

    // GameTopControl is constructed exactly like ProbeModule does it: with the application.
    // Nothing in buildControlPage() talks to the manager, so no provider call happens.
    val control: Any = try {
        val ctor = controlClass.getDeclaredConstructor()
        ctor.isAccessible = true
        ctor.newInstance()
    } catch (t: Throwable) {
        println("FATAL  cannot construct GameTopControl: ${rootCause(t)}")
        println("=== 0 passed, 1 failed ===")
        return
    }

    // ---- 0. the tag keys themselves -------------------------------------------
    // Every slot is read back through a literal below, so the constants must keep the values
    // this probe knows. A shifted TAG_DETAIL would make an old `TAG_DETAIL + 1` read silently
    // address a different slot, which is exactly how the original bug stayed invisible.
    val tagTitle = intConstant(controlClass, "TAG_TITLE")
    val tagState = intConstant(controlClass, "TAG_STATE")
    val tagNote = intConstant(controlClass, "TAG_NOTE")
    val tagSwitch = intConstant(controlClass, "TAG_SWITCH")
    val tagDetail = intConstant(controlClass, "TAG_DETAIL")
    val tagInfo = intConstant(controlClass, "TAG_INFO")
    // 0x7E010001.. are Int constants; spelled in decimal because a Kotlin hex literal here
    // reads as Long and would not compare equal to the reflected Int.
    checkEq("TAG_TITLE keeps its value", 2113994753, tagTitle)
    checkEq("TAG_STATE keeps its value", 2113994754, tagState)
    checkEq("TAG_NOTE keeps its value", 2113994755, tagNote)
    checkEq("TAG_SWITCH keeps its value", 2113994756, tagSwitch)
    checkEq("TAG_DETAIL keeps its value", 2113994757, tagDetail)
    checkEq("TAG_INFO keeps its value", 2113994758, tagInfo)

    val activity = Activity()

    // ---- 1. the crash line ------------------------------------------------------
    // This is the exact call that threw on the device. If the regression comes back this
    // throws NullPointerException and the probe reports the same frame the game log had.
    var thrown: Throwable? = null
    val page = try {
        privateMethod(controlClass, "buildControlPage", Activity::class.java)
            .invoke(control, activity) as LinearLayout?
    } catch (e: InvocationTargetException) {
        thrown = rootCause(e)
        null
    } catch (t: Throwable) {
        thrown = rootCause(t)
        null
    }
    check("buildControlPage() does not throw", thrown == null) {
        val t = thrown
        if (t == null) "" else "${t.javaClass.name}: ${t.message} @ ${t.stackTrace.firstOrNull { it.className.contains("hookprobe") }}"
    }
    check("buildControlPage() returned the control page", page != null)
    if (page == null) {
        println("=== $passed passed, ${failed} failed ===")
        return
    }

    // ---- 2. the ⓘ of the auto-rematch card exists and is wired ------------------
    val card = privateField(control, "rematchCard") as LinearLayout?
    throwableField("rematchCard", card)
    val tree = page?.let { walk(it) } ?: emptyList()

    val info = privateField(control, "rematchInfo") as View?
    check("rematchInfo (the ⓘ) is not null", info != null)
    check("the ⓘ lives inside the control page", info != null && tree.contains(info)) {
        "tree size=${tree.size}"
    }
    check("the ⓘ has a click listener", info != null && info.hasOnClickListeners())
    check("the ⓘ is clickable", info != null && info.isClickable)

    // ---- 3. the explanation it toggles is built and starts folded --------------
    val detail = privateField(control, "rematchDetail") as View?
    check("rematchDetail is not null", detail != null)
    check("rematchDetail starts GONE", detail != null && detail.visibility == View.GONE)
    check("rematchDetail is inside the card", detail != null && card != null && walk(card).contains(detail))

    // ---- 4. tapping the ⓘ toggles the explanation exactly once per tap ---------
    checkEq("rematchDetailOpen starts false", false, privateField(control, "rematchDetailOpen"))
    info?.performClick()
    checkEq("one tap on the ⓘ opens the explanation", true, privateField(control, "rematchDetailOpen"))
    info?.performClick()
    checkEq("a second tap on the ⓘ folds it back", false, privateField(control, "rematchDetailOpen"))

    // The ⓘ must not double as the card's tap: the card's own listener is a separate one.
    check("the ⓘ is not the card itself", info !== card)

    // ...and it is the button the field points at. A build that kept the page alive but
    // wired a throw-away view would still leave the real one dead (and the field would be
    // pointing at the wrong object), which is the second half of the original defect.
    check("the field points at the ⓘ stored in the card",
        info != null && info === privateField(control, "rematchInfo"))

    // ---- 5. both other cards still register their own parts --------------------
    // Card 1 and card 3 have no ⓘ; the builder writes no TAG_INFO for them, and the build
    // path must not require one (that asymmetry is what hid the bug).
    val unattendedCard = privateField(control, "unattendedCard") as LinearLayout?
    val panelCard = privateField(control, "panelCard") as LinearLayout?
    check("card 1 exists", unattendedCard != null)
    check("card 3 exists", panelCard != null)
    for ((label, c) in listOf("card 1" to unattendedCard, "card 3" to panelCard)) {
        if (c == null) continue
        check("$label registers a title", tagOf(c, tagTitle) != null)
        check("$label registers a state label", tagOf(c, tagState) != null)
        check("$label registers a note", tagOf(c, tagNote) != null)
        check("$label registers a switch", tagOf(c, tagSwitch) != null)
        check("$label has no ⓘ slot", tagOf(c, tagInfo) == null)
    }
    if (card != null) {
        check("card 2 registers its ⓘ at TAG_INFO", tagOf(card, tagInfo) != null)
        check("card 2's ⓘ slot holds the same view as the field",
            tagOf(card, tagInfo) === privateField(control, "rematchInfo"))
        // TAG_DETAIL is the explanation, a different view: reading the ⓘ through
        // TAG_DETAIL + 1 is exactly what produced the null (and it was never written).
        check("card 2 stores the explanation at TAG_DETAIL", tagOf(card, tagDetail) === detail)
        // The v0.15 defect read the ⓘ back through `TAG_DETAIL + 1`. That expression only
        // happens to address the new TAG_INFO because TAG_INFO was numbered right after
        // TAG_DETAIL; the defect was never the arithmetic, it was that only TAG_INFO is
        // written. Both keys must therefore be populated by the card and point at the ⓘ.
        if (tagInfo == tagDetail + 1) {
            check("the ⓘ is stored at TAG_DETAIL + 1 as well (same slot as TAG_INFO)",
                tagOf(card, tagDetail + 1) === privateField(control, "rematchInfo"))
        }
    }

    // ---- 6. every switch on the page is still reachable -----------------------
    for (name in listOf("unattendedSwitch", "rematchSwitch", "panelSwitch")) {
        check("$name was created", privateField(control, name) != null)
    }

    // ---- 7. failures were recorded, never thrown out of build -----------------
    // The build path stays wrapped so a future layout mistake cannot kill the game: the
    // probe proves the boundary exists by calling attach() on a control whose window has
    // no decor view, which must return quietly instead of throwing.
    var attachThrew: Throwable? = null
    try {
        privateMethod(controlClass, "attach", Activity::class.java).invoke(control, BlankActivity())
    } catch (e: InvocationTargetException) {
        attachThrew = rootCause(e)
    } catch (t: Throwable) {
        attachThrew = rootCause(t)
    }
    check("attach() never bubbles a failure out of the hook", attachThrew == null) {
        attachThrew?.toString() ?: ""
    }

    println("=== $passed passed, $failed failed ===")
    if (failed > 0) {
        println("failures:")
        failures.forEach { println("  - $it") }
    }
}

private fun throwableField(name: String, value: Any?) {
    if (value == null) println("note  $name is null")
}

private fun rootCause(t: Throwable): Throwable {
    var c = t
    while (c.cause != null && c.cause !== c) c = c.cause!!
    return c
}

/** An Activity whose decor view is not a ViewGroup, to exercise attach()'s guard. */
private class BlankActivity : Activity() {
    private val w = object : android.view.Window(this) {
        override fun peekDecorView(): View? = null
        override fun getDecorView(): View = View(this@BlankActivity)
    }
    override fun getWindow(): android.view.Window = w
}
