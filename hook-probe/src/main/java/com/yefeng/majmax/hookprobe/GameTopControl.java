package com.yefeng.majmax.hookprobe;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.yefeng.majmax.hookprobe.helper.InGameLocalizer;
import com.yefeng.majmax.hookprobe.manager.AssistLaunch;
import com.yefeng.majmax.hookprobe.manager.ManagerLanguage;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Game-window control for unattended mode. It is a child of the game's decor view,
 * never a system overlay, so it needs no overlay permission and survives every
 * activity the game opens (lifecycle callbacks are registered on the Application).
 *
 * The game and the manager app live in different UIDs, so every interaction is a
 * UID-checked ContentProvider call into {@code AiEndpointProvider}:
 *
 * <ul>
 *   <li>{@code assistLaunch} starts the manager's foreground analysis service and
 *       switches unattended mode on. The manager answers {@code restricted} when
 *       Android 12+ refuses a background foreground-service start; the capsule then
 *       opens {@link AssistLaunchActivity} in the manager app with the single-use
 *       token that call returned. A visible activity of the manager app is an
 *       allowed exemption, so the start from there is legal.</li>
 *   <li>{@code autoSet} switches the mode off without stopping the service, so the
 *       foreground notification keeps explaining what is still running.</li>
 *   <li>{@code autoStatus} is polled for progress, for the live mode state and for the
 *       auto-rematch opt-in (`rematch` / `rematchActive`).</li>
 *   <li>{@code rematchSet} writes the single auto-rematch preference. It never changes
 *       unattended mode, the service lifetime or the display mode; revoking it only
 *       removes the lease the next heartbeat carries.</li>
 *   <li>{@code panelSet} writes the single "recommendation / honour-points panel"
 *       preference of the click-through fixed panel. It never changes unattended
 *       mode, auto-rematch, the service lifetime, the display mode or the cat-head
 *       hints; the reply carries the stored value plus {@code recommendationPanel}.</li>
 * </ul>
 *
 * Surface behaviour (three local stages, no provider involved):
 *
 * <ol>
 *   <li>{@link #STAGE_HANDLE}: the resting entry at the top edge — an 84x30dp translucent
 *       deep-blue U with a gold outline, its live state dot and its down chevron in one
 *       row, inside a 108x44dp touch box so it is easy to hit without covering the table
 *       (the padding is touch area only and is never painted).</li>
 *   <li>{@link #STAGE_CAPSULE}: the assistant capsule — state dot, one short phrase
 *       ("unattended running" / "assistant running" / "assistant off"), a hairline, and
 *       the four-square menu grid. The whole capsule is one tap target.</li>
 *   <li>{@link #STAGE_MENU}: the full panel — deep-blue surface with a gold outline,
 *       a header (state dot, name, run-state pill, collapse arrow), a two-part Tab
 *       (control / status), the switch cards and the status page.</li>
 * </ol>
 *
 * The capsule folds back to the handle after {@link #IDLE_CAPSULE_MS}; the menu keeps
 * its own, longer window. A start sequence, a write in flight or a finger still down
 * always postpones the fold (busy / starting guard), so a reply never lands on a
 * collapsed view.
 *
 * <p>Touch: the stage root is only as large as what it draws, and it is clickable only
 * while the menu is open, where a touch outside the panel is an explicit "close" (the
 * same action as the header arrow). No full-screen transparent view is ever added, so
 * the game keeps every touch on its hand tiles and buttons.
 *
 * <p>Nothing here blocks the game's main thread: provider calls run on a worker pool
 * with a bounded timeout, results are compared against a generation counter so a
 * late reply can never paint over a newer activity, and only one poll and one user
 * action are ever in flight.
 */
final class GameTopControl implements Application.ActivityLifecycleCallbacks {
    /** Target game, used for the in-game surface and for the trampoline guard. */
    static final String GAME = "com.soulgamechst.majsoul";
    private static final String MANAGER = "com.yefeng.majmax.hookprobe";
    private static final String TRAMPOLINE = MANAGER + ".GameTopControl$AssistLaunchActivity";
    private static final String TOKEN_EXTRA = "majmax.assist.token";
    private static final Uri ENDPOINT = Uri.parse("content://com.yefeng.majmax.hookprobe.ai/capture");
    private static final int STAGE_HANDLE = 0;
    private static final int STAGE_CAPSULE = 1;
    private static final int STAGE_MENU = 2;

    /** Tab sections of the expanded menu, drawn as a two-part tab above the cards. */
    private static final int TAB_CONTROL = 0;
    private static final int TAB_STATUS = 1;

    /* Plain tag keys for the card parts (no resource ids exist in this module). */
    private static final int TAG_TITLE = 0x7E010001;
    private static final int TAG_STATE = 0x7E010002;
    private static final int TAG_NOTE = 0x7E010003;
    private static final int TAG_SWITCH = 0x7E010004;
    /** Card slot of the expandable long explanation ({@link #rematchDetail}). */
    private static final int TAG_DETAIL = 0x7E010005;
    /**
     * Card slot of the ⓘ button that toggles {@link #TAG_DETAIL}. It used to be read back
     * through {@code TAG_DETAIL + 1}, a key nothing ever writes, so {@code rematchInfo}
     * stayed null and {@link #buildControlPage} threw a NullPointerException on the main
     * thread of the hooked game process the moment the control was attached.
     */
    private static final int TAG_INFO = 0x7E010006;

    /**
     * Touch target of a *primary action* (the start/stop cell and the hand-off row), in
     * pre-scale dp. The menu is drawn at {@link ScaledMenuHost#SCALE} (.75), so 60dp inside
     * is the ~45dp the finger gets. Deliberately NOT applied to the chrome: the reporter's
     * device is 2376x1080 at 2.75, where 60dp becomes 135px of a ~648px menu, and the old
     * build spent the header and the tab on exactly that budget, leaving one switch card
     * visible. Content space wins there, so the header/tab/cards use their own compact
     * constants below.
     */
    private static final int TARGET_DP = 60;
    /**
     * Header band, pre-scale dp. 28dp is 77px visible on the reporter's 2.75 density (the
     * visible target was 70..85px) and holds the 16sp title, the 11sp state pill and the
     * collapse chevron in one centred row of 28dp. The action below it measures its own
     * 77x72dp touch box instead of inheriting {@link #TARGET_DP}.
     */
    private static final int HEADER_DP = 28;
    /**
     * Two-part tab band, pre-scale dp. 22dp is ~60px visible (target 55..65px): a 14sp
     * label with 1dp of padding and 6dp of capsule margin still hits ~106px of width, and
     * the whole row is one tap wide per half.
     */
    private static final int TAB_DP = 28;
    /**
     * Height of a switch card, pre-scale dp. 34dp is ~94px visible: title row (16sp + 13sp
     * state) plus one 12sp note line, which is what the card actually needs. Cards stay the
     * widest tap target on the page, so this is about density of information, not reach.
     */
    private static final int ROW_DP = 34;
    /** Gutter between header, tab and pages, pre-scale dp (~8px visible). */
    private static final int CHROME_GAP_DP = 4;
    /** Inner padding of the menu surface, pre-scale dp (~10px visible). */
    private static final int PANEL_PAD_DP = 5;
    /** Top inset for the capsule and the menu; the half-hidden handle ignores it. */
    private static final int TOP_GAP_DP = 8;
    /** Idle window before the capsule folds back into the handle. */
    private static final long IDLE_CAPSULE_MS = 3_000L;
    /** Idle window before the open menu folds back into the handle. */
    private static final long IDLE_MENU_MS = 6_000L;
    /**
     * Short fall-back delay while something must not be interrupted: a touch still down,
     * a start sequence running, or a provider write in flight.
     */
    private static final long ACTION_HOLD_MS = 2_000L;

    private static final long POLL_MENU_MS = 1_000L;
    private static final long POLL_IDLE_MS = 3_000L;
    private static final long REQUEST_TIMEOUT_MS = 8_000L;
    private static final long START_TIMEOUT_MS = 20_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "majmax-top-control");
        thread.setDaemon(true);
        return thread;
    });
    private final Runnable pollTick = new Runnable() {
        @Override public void run() { poll(); }
    };
    private final AtomicBoolean actionBusy = new AtomicBoolean();
    private final AtomicBoolean pollBusy = new AtomicBoolean();

    private WeakReference<Activity> active = new WeakReference<>(null);
    /** Root of the attached control (a wrap-content {@link StageRoot}). */
    private StageRoot panel;
    private FrameLayout.LayoutParams panelParams;

    /** Expanded menu: deep-blue surface, header, two-part tab, two pages. */
    private LinearLayout menu;
    private View titleDot;
    private TextView title;
    private TextView statePill;
    private View collapseButton;

    private LinearLayout tabControl;
    private LinearLayout tabStatus;
    private TextView tabControlLabel;
    private TextView tabStatusLabel;

    private LinearLayout pageControl;
    private LinearLayout pageStatus;

    /* Switch card 1: unattended mode (assistLaunch / autoSet). */
    private LinearLayout unattendedCard;
    private TextView unattendedTitle;
    private TextView unattendedState;
    private TextView unattendedNote;
    private GameTopUi.Switch unattendedSwitch;
    /** The ⓘ button of card 1; hidden while the note line already explains everything. */
    private GameTopUi.Glyph unattendedInfo;

    /* Switch card 2: auto-rematch (rematchSet), independent of card 1. */
    private LinearLayout rematchCard;
    private TextView rematchTitle;
    private TextView rematchState;
    private TextView rematchNote;
    private GameTopUi.Switch rematchSwitch;
    /** ⓘ toggle of card 2: it opens the long explanation, it is not a link. */
    private GameTopUi.Glyph rematchInfo;
    /** Long explanation of card 2; built with the card, only shown while expanded. */
    private TextView rematchDetail;
    private boolean rematchDetailOpen;

    /* 开关卡 3：推荐与番点面板（panelSet），与卡 1、卡 2 完全独立。 */
    private LinearLayout panelCard;
    private TextView panelTitle;
    private TextView panelState;
    private TextView panelNote;
    private GameTopUi.Switch panelSwitch;

    /* Status page: the name row with its state dot, the detail rows and the actions. */
    private LinearLayout statusCard;
    private View statusDot;
    private TextView statusMode;
    private TextView statusDetail;
    private TextView statusService;
    private TextView statusRematch;
    private TextView startStop;
    private LinearLayout manualRow;

    /**
     * Survives activity switches so the menu does not collapse on every transition.
     * STAGE_HANDLE is the resting state, so a re-attach always starts half-hidden.
     */
    private int stage = STAGE_HANDLE;
    private int tab = TAB_CONTROL;
    private int generation;
    /**
     * The managed in-game hint layer, or null while hook mode is not confirmed. It is built
     * lazily by {@link #attachHintLayer()} and only ever after {@link #display} said hook, so
     * overlay mode never allocates a single in-game view.
     */
    private GameHintLayer hints;

    /**
     * The assistant display mode of this game process. Nothing is drawn until its first answer
     * arrives; overlay mode builds and polls nothing here. See {@link GameDisplayGate}.
     */
    private final GameDisplayGate display = GameDisplayGate.forHook(this::onHookModeConfirmed);

    /** True once the gate handed the in-game surface over (hook mode confirmed). */
    private boolean inGameEnabled;

    /* Top-centre docking geometry: the control never drags, it always sits top-centre. */
    private View decor;
    private GameTopUi.HandleView handleTab;
    private GameTopUi.CapsuleView capsule;
    /** Only keeps the wrap-content root clamped top-centre; it never drives a drag. */
    private SizeWatcher hooks;

    /**
     * Idle auto-collapse: while the capsule or the menu is showing, this brings the
     * control back to the handle after {@link #IDLE_CAPSULE_MS} (capsule) or
     * {@link #IDLE_MENU_MS} (menu) without interaction. Only a user interaction (or an
     * action still running) restarts it — polling and {@link #applyState()} never do, so
     * a one-second status refresh cannot keep the menu open forever.
     */
    private final Runnable collapseTick = new Runnable() {
        @Override public void run() {
            collapseIfIdle();
        }
    };
    private long lastTouchAt;
    /** True between ACTION_DOWN and ACTION_UP/CANCEL inside the control. */
    private boolean touching;

    /* Last reply from the manager app. */
    private boolean available;
    private boolean enabled;
    private boolean demo;
    private String remoteMessage = "";
    private boolean unreachable;
    private boolean managerMissing;

    /**
     * Why the selected overlay is not on screen, as reported by the manager (`overlayError`).
     * Shown on the status page of a still-running control; empty in hook mode.
     */
    private String displayError = "";

    /**
     * Auto-rematch as reported by the manager's status reply. [rematch] is the persisted
     * opt-in (what the row shows and toggles); [rematchActive] is the effective gate
     * reported alongside it and is only used to explain why an enabled opt-in is inert.
     * Both are read-only here: the row's tap is the only writer, and it never touches
     * unattended mode, the service lifetime or the display mode.
     */
    private boolean rematch;
    private boolean rematchActive;

    /**
     * 推荐与番点面板开关，取自状态回复的 {@code recommendationPanel}（缺省为开）。
     * 只读字段：卡片的点按是唯一写入方，走 {@code panelSet}，且从不改无人值守、
     * 自动下一局、显示模式或猫头提示。
     */
    private boolean recommendationPanel = true;

    /**
     * UI language of every label on this surface. Seeded from the manager's own
     * preference (the first reply corrects it), then moved only by the `uiLanguage`
     * tag the provider stamps on each status reply.
     *
     * A change repaints *all* static copy — tab names, card titles, the header, the
     * capsule and the status page — not just the values that were already dynamic;
     * that is what [paintStatic] exists for. Nothing else is rebuilt: no listener is
     * added here and no view is created, so repeated replies cannot stack anything.
     */
    private final InGameLocalizer.State uiLanguage = new InGameLocalizer.State();

    /**
     * The copy behind the current notice, kept in Chinese. `notice` is what a card
     * shows (already translated and possibly clipped); the key is never rewritten, so
     * the failure tests below keep reading the source phrase whatever the language.
     */
    private String noticeKey = "";

    /* Local, transient start sequence. */
    private boolean starting;
    private long startingAt;
    private String notice = "";
    private boolean noticeFailed;

    /** Application the receiver is registered against; null while no activity is attached. */
    private Application application;

    /**
     * One tab / page pair. The two parts of the top tab and the two pages are toggled
     * together from here. Buttons carry their listener exactly once (set at build time);
     * a language switch rebuilds the tree instead of re-binding them, so nothing stacks.
     */
    private final TabView controlTab = new TabView(TAB_CONTROL);
    private final TabView statusTab = new TabView(TAB_STATUS);

    private final class TabView {
        final int id;
        /** Selectable container: background + ripple. */
        LinearLayout shell;
        TextView label;

        TabView(int id) { this.id = id; }

        void unbind() {
            shell = null;
            label = null;
        }

        boolean bound() { return shell != null && label != null; }
    }

    /* Language / rebuild helpers. */
    private boolean languageStale;

    void register(Application app) {
        application = app;
        app.registerActivityLifecycleCallbacks(this);
        // The very first thing this process does: ask which mode the assistant runs in.
        // This is asynchronous — the call returns immediately and the answer arrives on the
        // main thread (or through a bounded retry). Nothing is built, attached or polled
        // before that answer is known, and in overlay mode nothing ever will be.
        display.requestMode(app.getApplicationContext());
    }

    /**
     * Called once, on the main thread, when the assistant confirmed hook mode. The gate
     * already applied the decision before calling back, so building here is ordered exactly
     * like a normal {@link #attach(Activity)}.
     */
    private void onHookModeConfirmed() {
        if (inGameEnabled) return;
        inGameEnabled = true;
        Activity activity = active.get();
        if (activity != null) attach(activity);
    }

    /** True only once hook mode is confirmed; the single gate for every in-game view. */
    private boolean inGameEnabled() {
        return inGameEnabled && display.getMode() == GameDisplayGate.Mode.HOOK;
    }

    /* ---------------------------------------------------------------- lifecycle */

    @Override public void onActivityResumed(Activity activity) {
        if (!inGameEnabled()) {
            // Overlay mode (or the answer is still pending): remember the visible activity
            // first so a later hook confirmation has something to attach to, but create no
            // view at all. While the gate is still undecided, a resume is also the cheapest
            // moment to re-ask (a no-op if a request is already in flight or the mode is
            // settled) — this is what recovers a game that started before the assistant.
            active = new WeakReference<>(activity);
            if (!display.isDecided()) display.requestMode(activity.getApplicationContext());
            return;
        }
        attach(activity);
    }

    @Override public void onActivityPaused(Activity activity) {
        if (active.get() == activity) detach();
    }

    @Override public void onActivityDestroyed(Activity activity) {
        if (active.get() == activity) detach();
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}

    @Override public void onActivityStarted(Activity activity) {}

    @Override public void onActivityStopped(Activity activity) {}

    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

    private void attach(Activity activity) {
        // Overlay mode never reaches this point: the surface was never built and the gate
        // answered overlay. Keeping the check here means a late lifecycle callback cannot
        // create the in-game control behind the overlay's back.
        if (!inGameEnabled()) {
            active = new WeakReference<>(activity);
            return;
        }
        if (panel != null && active.get() == activity) return;
        detach();
        if (activity.getWindow() == null) return;
        View decorView = activity.getWindow().peekDecorView();
        if (!(decorView instanceof ViewGroup)) return;
        active = new WeakReference<>(activity);
        decor = decorView;
        seedLanguage(activity);
        // The control is a guest in the game's own process: a layout failure of the
        // in-game surface must never become a crash of the game itself. Every build step
        // stays inside this boundary, and a failure leaves the game running without the
        // control (the overlay path is untouched).
        try {
            panel = build(activity);
            // Wrap-content child of the decor view: it is only as large as the visible
            // stage, never a full-screen surface, so the game keeps every other touch.
            panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            ((ViewGroup) decorView).addView(panel, panelParams);
            installOutsideObserver(activity);
            // Drag is gone by design: a fixed top-centre tab needs no travel, and the
            // helper only keeps the (now trivial) position legal after a size change.
            // The stage survives activity switches; a restored capsule / menu gets a fresh
            // idle window instead of an already-expired one.
            if (stage != STAGE_HANDLE) restartIdleTimer();
            // The stage survived the activity switch, but not the presentation mode: a new
            // activity must never inherit "in-game control visible" from before the user
            // moved the surface to the overlay. The next reply re-evaluates it.
            applyState();
            hooks = new SizeWatcher(activity);
            ((ViewGroup) decorView).addOnLayoutChangeListener(hooks);
            attachHintLayer();
            schedulePoll(0L);
        } catch (RuntimeException | LinkageError failure) {
            recordSurfaceFailure(activity, "ATTACH", failure);
            detach();
        }
    }

    /**
     * Records a failed build of the in-game surface and gives up on it for this attach.
     * Best effort: any failure inside this helper is swallowed, because it runs on the
     * path that is already recovering from a failure.
     */
    private void recordSurfaceFailure(Activity activity, String code, Throwable failure) {
        try {
            HookDiagnostics.event("ERROR", "hook.topcontrol", code,
                HookDiagnostics.fields(
                    "error", failure.getClass().getName(),
                    "message", String.valueOf(failure.getMessage()),
                    "frame", topFrame(failure),
                    "activities", activity == null ? "none"
                        : activity.getClass().getName()));
        } catch (Throwable ignored) {}
    }

    /** First stack frame inside this module, for a one-line report; empty when unknown. */
    private static String topFrame(Throwable failure) {
        String own = GameTopControl.class.getName();
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frame.getClassName().startsWith(own.substring(0, own.lastIndexOf('.')))) {
                return frame.getClassName() + "." + frame.getMethodName()
                    + ":" + frame.getLineNumber();
            }
        }
        return "";
    }

    /**
     * Builds and attaches the hint layer if — and only if — it does not exist yet. Called from
     * {@link #attach(Activity)}, so a re-attach on the next activity reuses the same instance
     * instead of stacking a second one; [GameHintLayer.attach] already detaches itself first.
     */
    private void attachHintLayer() {
        if (!inGameEnabled()) return;
        Activity activity = active.get();
        if (activity == null) return;
        if (hints == null) hints = new GameHintLayer();
        hints.setPresentationAllowed(this::inGameVisible);
        hints.attach(activity);
    }

    private void detach() {
        removeOutsideObserver();
        stopIdleTimer();
        touching = false;
        if (hints != null) hints.detach();
        generation++;
        main.removeCallbacks(pollTick);
        if (panel != null && panel.getParent() instanceof ViewGroup) {
            ((ViewGroup) panel.getParent()).removeView(panel);
        }
        if (hooks != null) {
            // The listener is on the decor view, which may already be gone; removing it
            // from a detached view is a no-op, so the reference is dropped either way.
            if (decor != null) decor.removeOnLayoutChangeListener(hooks);
            hooks = null;
        }
        panel = null;
        panelParams = null;
        decor = null;
        handleTab = null;
        capsule = null;
        menu = null;
        title = null;
        titleDot = null;
        statePill = null;
        collapseButton = null;
        controlTab.unbind();
        statusTab.unbind();
        pageControl = pageStatus = null;
        unattendedCard = null;
        unattendedTitle = unattendedState = unattendedNote = null;
        unattendedSwitch = null;
        unattendedInfo = null;
        rematchCard = null;
        rematchTitle = rematchState = rematchNote = null;
        rematchSwitch = null;
        rematchInfo = null;
        rematchDetail = null;
        panelCard = null;
        panelTitle = panelState = panelNote = null;
        panelSwitch = null;
        statusCard = null;
        statusDot = null;
        statusMode = statusDetail = statusService = statusRematch = null;
        startStop = null;
        manualRow = null;
        active.clear();
    }

    private android.view.Window observedWindow;
    private android.view.Window.Callback savedCallback, tapCallback;
    private void removeOutsideObserver() {
        if (observedWindow != null && observedWindow.getCallback() == tapCallback)
            observedWindow.setCallback(savedCallback);
        observedWindow = null; savedCallback = null; tapCallback = null;
    }
    private void installOutsideObserver(Activity activity) {
        removeOutsideObserver();
        final android.view.Window window = activity.getWindow();
        final android.view.Window.Callback original = window.getCallback();
        if (original == null) return;
        final float[] down = new float[2];
        final boolean[] candidate = new boolean[1];
        final int slop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
        android.view.Window.Callback wrapper = (android.view.Window.Callback)
            java.lang.reflect.Proxy.newProxyInstance(android.view.Window.Callback.class.getClassLoader(),
                new Class<?>[]{android.view.Window.Callback.class}, (proxy, method, args) -> {
                    if (method.getName().equals("dispatchTouchEvent") && args != null && args[0] instanceof MotionEvent) {
                        MotionEvent e = (MotionEvent) args[0];
                        android.graphics.Rect bounds = new android.graphics.Rect();
                        boolean inside = panel != null && panel.getGlobalVisibleRect(bounds)
                            && bounds.contains((int)e.getRawX(), (int)e.getRawY());
                        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            down[0] = e.getRawX(); down[1] = e.getRawY();
                            candidate[0] = stage == STAGE_MENU && !inside;
                        } else if (e.getPointerCount() > 1 || e.getActionMasked() == MotionEvent.ACTION_CANCEL
                            || Math.abs(e.getRawX()-down[0]) > slop || Math.abs(e.getRawY()-down[1]) > slop) {
                            candidate[0] = false;
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            if (candidate[0] && !inside && stage == STAGE_MENU) main.post(() -> { if (stage == STAGE_MENU) collapse(); });
                            candidate[0] = false;
                        }
                    }
                    try { return method.invoke(original, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        observedWindow = window; savedCallback = original; tapCallback = wrapper;
        window.setCallback(wrapper);
    }

    /* ---------------------------------------------------------------------- UI */

    private static int dp(Context context, int value) {
        return GameTopUi.dp(context, value);
    }

    /** Rounds a pressable background so taps get a ripple inside the rounded shape. */
    private static void ripple(View view, GradientDrawable shape) {
        GameTopUi.pressable(view, shape);
    }

    private TextView cell(Context context, float size, int color, boolean bold) {
        TextView view = GameTopUi.text(context, size, color, bold);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
        view.setMinHeight(dp(context, 34));
        return view;
    }

    /**
     * Opens the menu on the control page (from a capsule tap or an explicit call) and
     * asks for a fresh status immediately. This is the only place the menu stage is
     * entered, so every entry point arms the idle window exactly once.
     */
    private void openMenu() {
        if (stage == STAGE_MENU) return;
        stage = STAGE_MENU;
        tab = TAB_CONTROL;
        resetTransientMenuState();
        restartIdleTimer();
        applyState();
        schedulePoll(0L);
    }

    /**
     * A freshly opened menu starts with card 2's long explanation folded, so the panel
     * keeps its compact height and every card stays reachable without scrolling.
     */
    private void resetTransientMenuState() {
        rematchDetailOpen = false;
    }

    private StageRoot build(Activity activity) {
        StageRoot root = new StageRoot(activity);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        // Resting entry: 30dp visible pill inside a 44dp touch box at the window edge.
        handleTab = new GameTopUi.HandleView(activity);
        handleTab.setContentDescription(t("展开雀魂助手"));
        handleTab.setStateColor(stateColor());
        handleTab.setOnClickListener(view -> {
            stage = STAGE_CAPSULE;
            restartIdleTimer();
            applyState();
        });
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        handleParams.topMargin = 0;
        root.addView(handleTab, handleParams);

        // Assistant capsule: state dot, short phrase, hairline, four-icon grid.
        capsule = new GameTopUi.CapsuleView(activity);
        capsule.setContentDescription(t("打开雀魂助手菜单"));
        capsule.setStateColor(stateColor());
        // openMenu() arms the idle window, so the capsule needs no touch listener of its
        // own; a plain click keeps the ripple and the accessibility tap working.
        capsule.setOnClickListener(view -> openMenu());
        FrameLayout.LayoutParams capsuleParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(activity, GameTopUi.CapsuleView.HEIGHT_DP),
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        capsuleParams.topMargin = dp(activity, TOP_GAP_DP);
        root.addView(capsule, capsuleParams);

        menu = buildMenu(activity);
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        menuParams.topMargin = dp(activity, TOP_GAP_DP);
        // The host occupies the visible scaled size, not the original large rectangle.
        ScaledMenuHost menuHost = new ScaledMenuHost(activity);
        menuHost.addView(menu, new FrameLayout.LayoutParams(dp(activity, 500),
            ViewGroup.LayoutParams.WRAP_CONTENT));
        // Outside the panel the menu stays open: the root below turns such a tap into the
        // same explicit "collapse" the arrow performs (see StageRoot). The host itself stays
        // click-through, so the idle window is not re-armed by touches outside the panel.
        root.addView(menuHost, menuParams);

        // Outer touch watcher. It only observes: every event is handed straight back to
        // the normal child / click path (return false), so switches, rows and the scroll
        // behaviour of the panel keep working exactly as before.
        return root;
    }

    /**
     * Records menu interactions for the idle timer and never consumes the event.
     * ACTION_DOWN arms a hold so a finger resting on the panel (reading the status)
     * does not trigger the fall-back mid-read.
     */
    private void noteMenuTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                lastTouchAt = SystemClock.elapsedRealtime();
                stopIdleTimer();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                lastTouchAt = SystemClock.elapsedRealtime();
                break;
            case MotionEvent.ACTION_MOVE:
                if (stage != STAGE_HANDLE) restartIdleTimer();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                restartIdleTimer();
                break;
            default:
                break;
        }
    }

    /* ------------------------------------------------------------ idle collapse */

    /** Idle window of the current stage. */
    private long idleWindow() {
        return stage == STAGE_CAPSULE ? IDLE_CAPSULE_MS : IDLE_MENU_MS;
    }

    /** Restarts the idle window; the handle stage has no timer to run. */
    private void restartIdleTimer() {
        main.removeCallbacks(collapseTick);
        lastTouchAt = SystemClock.elapsedRealtime();
        if (stage == STAGE_HANDLE) return;
        // A start sequence or a settings write must not land on a collapsed view: the
        // reply is kept and repainted, but the fall-back waits until it is done.
        long delay = actionBusy.get() || starting ? ACTION_HOLD_MS : idleWindow();
        main.postDelayed(collapseTick, delay);
    }

    private void stopIdleTimer() {
        main.removeCallbacks(collapseTick);
    }

    /**
     * Pushes the pending fall-back out by {@link #ACTION_HOLD_MS} without counting as
     * user activity. Used while an action is in flight; the handle stage has no timer.
     */
    private void deferCollapse() {
        if (stage == STAGE_HANDLE) return;
        main.removeCallbacks(collapseTick);
        main.postDelayed(collapseTick, ACTION_HOLD_MS);
    }

    /**
     * Auto-collapse entry point. Any interaction (or any action still in flight)
     * postpones it instead of hiding the panel; only a genuinely idle control folds
     * back to the half-hidden handle. Nothing in polling reaches this method.
     */
    private void collapseIfIdle() {
        if (panel == null || !inGameVisible() || stage == STAGE_HANDLE) return;
        if (touching || actionBusy.get() || starting) {
            main.postDelayed(collapseTick, ACTION_HOLD_MS);
            return;
        }
        long window = idleWindow();
        long idle = SystemClock.elapsedRealtime() - lastTouchAt;
        if (idle < window - 250L) {
            main.postDelayed(collapseTick, Math.max(250L, window - idle));
            return;
        }
        collapse();
    }

    /** Back to the half-hidden handle. The timer is not armed again from here. */
    private void collapse() {
        stopIdleTimer();
        if (stage == STAGE_HANDLE) return;
        stage = STAGE_HANDLE;
        resolveOutsideClick();
        applyState();
    }

    /**
     * Window size / density changed. The control stays top-centre, which the layout
     * gravity already guarantees, so the only work left is re-clamping and letting the
     * panel re-measure against the new window height.
     */
    private void onWindowResized() {
        if (panel == null) return;
        panel.post(() -> {
            if (panel == null || panel.getParent() == null) return;
            if (menu != null) ((CappedColumn) menu).refreshCap();
            applyState();
        });
    }

    /**
     * Listens for window size changes (rotation, split screen, density change). Only a
     * real size change re-centres; ordinary layout passes are ignored so the control is
     * never re-laid-out for nothing while the user is reading it.
     */
    private final class SizeWatcher implements View.OnLayoutChangeListener {
        private final Activity activity;
        private int lastWidth;
        private int lastHeight;
        private boolean armed;

        SizeWatcher(Activity activity) { this.activity = activity; }

        @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                             int oldLeft, int oldTop, int oldRight, int oldBottom) {
            if (active.get() != activity) return;
            int width = right - left;
            int height = bottom - top;
            if (!armed) {
                armed = true;
                lastWidth = width;
                lastHeight = height;
                return;
            }
            if (width == lastWidth && height == lastHeight) return;
            lastWidth = width;
            lastHeight = height;
            onWindowResized();
        }
    }

    /**
     * Stage host. It is a wrap-content FrameLayout, but it also strips the full-screen
     * decor constraint the window hands down: the root must only ever be as large as
     * what it draws, otherwise the game's touches would be eaten by an invisible box
     * around the menu. Children are measured exactly as FrameLayout would, against the
     * real window size, so the capped panel and CENTER_HORIZONTAL gravity still work.
     *
     * <p>Outside touches: while the menu is open the root becomes clickable and any tap
     * that is not consumed by the panel reaches it, which closes the menu — the exact
     * action of the header arrow. The root's own bounds are now the visible panel (the
     * handle and the capsule are GONE), so the game's hand tiles and buttons outside it
     * are untouched. While the handle or the capsule is showing the root is wrap-content
     * around that small view and never clickable at all.
     */
    private final class StageRoot extends FrameLayout {
        private float downX, downY;
        private boolean swiping, moved;
        StageRoot(Context context) { super(context); }
        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            if (stage != STAGE_MENU) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = e.getX(); downY = e.getY(); swiping = false; moved = false;
            } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                int slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true;
                if (Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    swiping = true; return true;
                }
            }
            return false;
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (stage != STAGE_MENU) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                if (swiping) {
                    int next = e.getX() < downX ? TAB_STATUS : TAB_CONTROL;
                    selectTab(next);
                    View page = next == TAB_CONTROL ? pageControl : pageStatus;
                    if (page != null) {
                        page.setTranslationX(dp(getContext(), next == TAB_STATUS ? 18 : -18));
                        page.setAlpha(.5f);
                        page.animate().translationX(0).alpha(1f).setDuration(160).start();
                    }
                } // Internal blank taps never collapse the menu.
            }
            return true;
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            noteMenuTouch(event);
            return super.dispatchTouchEvent(event);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int maxW = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? 0 : MeasureSpec.getSize(widthSpec);
            int maxH = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                ? 0 : MeasureSpec.getSize(heightSpec);
            int count = getChildCount();
            int width = 0;
            int height = 0;
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) child.getLayoutParams();
                int childWidthSpec;
                if (params.width == LayoutParams.MATCH_PARENT && maxW > 0) {
                    childWidthSpec = MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.EXACTLY);
                } else if (params.width >= 0) {
                    childWidthSpec = MeasureSpec.makeMeasureSpec(params.width, MeasureSpec.EXACTLY);
                } else {
                    childWidthSpec = MeasureSpec.makeMeasureSpec(
                        Math.max(0, maxW - params.leftMargin - params.rightMargin),
                        maxW > 0 ? MeasureSpec.AT_MOST : MeasureSpec.UNSPECIFIED);
                }
                int childHeightSpec;
                if (params.height == LayoutParams.MATCH_PARENT && maxH > 0) {
                    childHeightSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.EXACTLY);
                } else if (params.height >= 0) {
                    childHeightSpec = MeasureSpec.makeMeasureSpec(params.height, MeasureSpec.EXACTLY);
                } else {
                    childHeightSpec = MeasureSpec.makeMeasureSpec(
                        Math.max(0, maxH - params.topMargin - params.bottomMargin),
                        maxH > 0 ? MeasureSpec.AT_MOST : MeasureSpec.UNSPECIFIED);
                }
                child.measure(childWidthSpec, childHeightSpec);
                if (child.getVisibility() == GONE) continue;
                int childWidth = child.getMeasuredWidth();
                int childHeight = child.getMeasuredHeight();
                if (child instanceof ScaledMenuHost) {
                    // The host measures its scale itself: its *visible* width is measured,
                    // and the child is laid out wider. Using the child's box here would
                    // inflate the root and swallow more of the table than the panel covers.
                    View scaled = ((ViewGroup) child).getChildAt(0);
                    if (scaled != null) {
                        childWidth = Math.min(childWidth, scaled.getMeasuredWidth());
                        childHeight = Math.min(childHeight, scaled.getMeasuredHeight());
                    }
                }
                width = Math.max(width, childWidth + params.leftMargin + params.rightMargin);
                height = Math.max(height, childHeight + params.topMargin + params.bottomMargin);
            }
            if (maxW > 0) width = Math.min(width, maxW);
            if (maxH > 0) height = Math.min(height, maxH);
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
        }
    }

    /** A menu host with matching visual, layout and touch bounds. */
    private static final class ScaledMenuHost extends FrameLayout {
        float SCALE = .75f;
        ScaledMenuHost(Context context) { super(context); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            View child = getChildAt(0);
            int availableWidth = MeasureSpec.getSize(widthSpec);
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int visualWidth = Math.round(screenWidth * .42f);
            if (availableWidth > 0) visualWidth = Math.min(visualWidth, availableWidth);
            int width = Math.round(visualWidth / SCALE);
            int availableHeight = MeasureSpec.getSize(heightSpec);
            int visualHeight = Math.round(screenHeight * .60f);
            if (availableHeight > 0) visualHeight = Math.min(visualHeight, availableHeight);
            // The child gets the pre-scale size of the *visible* box (648 / .75 = 864), not
            // the rounded pixels scaled back: the panel is capped to exactly what the finger
            // sees, so no chip of it can slide under the bottom edge.
            int height = Math.round(visualHeight / SCALE);
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            // Report the visible box itself. Rounding the already-rounded child size
            // (864 * .75 -> 648) only agrees when the two roundings cancel, and a 1px
            // disagreement inside the stage root shows up as a clipped bottom edge.
            setMeasuredDimension(visualWidth, visualHeight);
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            View child = getChildAt(0);
            child.setPivotX(0f);
            child.setPivotY(0f);
            child.setScaleX(SCALE);
            child.setScaleY(SCALE);
            child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
        }
    }

    /**
     * Vertical LinearLayout capped to a maximum height. Used for the expanded panel so
     * it can never grow past the window in landscape; the inner pages are measured
     * against the cap, and the top of the panel (header, tab) stays reachable.
     */
    private static final class CappedColumn extends LinearLayout {
        private final int marginReserve;
        private int maxHeight;

        CappedColumn(Context context, int maxHeight) {
            super(context);
            this.marginReserve = maxHeight;
            this.maxHeight = Math.max(0, maxHeight);
            setOrientation(VERTICAL);
        }

        /**
         * Re-reads the window height after a rotation / size change so a menu opened in
         * portrait can never overflow a short landscape window.
         */
        void refreshCap() {
            int available = marginReserve;
            View parent = (View) getParent();
            if (parent != null && parent.getHeight() > 0 && !(parent instanceof ScaledMenuHost)) {
                available = Math.min(available, parent.getHeight());
            }
            maxHeight = Math.max(0, available);
            requestLayout();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            boolean bounded = MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED;
            if (maxHeight > 0 && bounded) {
                // The host already capped the height, so the panel takes exactly that much:
                // a definite height is what lets the scrolling body (weight 1) fill the
                // space the compact chrome leaves instead of wrapping to content. The old
                // wrap-measure-then-clip left the body at its content height and pushed the
                // pinned divider/collapse row up over the cards.
                int exact = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.EXACTLY);
                super.onMeasure(widthSpec, exact);
                setMeasuredDimension(getMeasuredWidth(), maxHeight);
                return;
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /**
     * The expanded panel: deep-blue surface, compact header, two-part tab, two pages.
     *
     * <p>Vertical budget, in the pre-scale coordinates the panel is laid out in, for the
     * reporter's 2376x1080 @ 2.75 device (density 2.75, the 648px the finger sees = 864
     * pre-scale px at {@link ScaledMenuHost#SCALE} .75):
     *
     * <pre>
     *   panel padding         5dp          14px        11px visible
     *   header               28dp          77px        58px visible
     *   gutter                4dp          11px         8px visible
     *   tab                  22dp          61px        46px visible
     *   gutter                4dp          11px         8px visible
     *   body (weight 1)                 848px       507px visible = 78% of the menu
     *   panel padding         5dp          14px        11px visible
     *   ---- total                     1036px       648px visible
     * </pre>
     *
     * <p>Nothing is pinned below the scroll band any more: the 1dp divider and the 135px
     * "收起面板" row are gone (the header chevron and the outside tap both collapse), so the
     * band keeps every pixel the chrome does not need.
     */
    private LinearLayout buildMenu(Activity activity) {
        int maxHeight = Math.round(activity.getResources().getDisplayMetrics().heightPixels
            - GameTopUi.density(activity) * (TOP_GAP_DP + 8));
        LinearLayout panelView = new CappedColumn(activity, maxHeight);
        panelView.setBackground(GameTopUi.panel(activity));
        int pad = dp(activity, PANEL_PAD_DP);
        panelView.setPadding(pad, pad, pad, pad);
        panelView.setElevation(dp(activity, 12));
        // Width is capped so a narrow portrait screen never pushes the panel off-screen;
        // the status page scrolls instead of growing past the window height.
        int width = Math.min(dp(activity, 272),
            Math.max(dp(activity, 232), Math.round(screenWidthDp(activity) * 0.84f)));
        panelView.setMinimumWidth(width);

        panelView.addView(buildHeader(activity),
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, HEADER_DP)));
        panelView.addView(spacer(activity, CHROME_GAP_DP));
        panelView.addView(buildTabs(activity),
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, TAB_DP)));
        panelView.addView(spacer(activity, CHROME_GAP_DP));

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        panelView.addView(body, bodyParams);

        android.widget.ScrollView pageScroll = new android.widget.ScrollView(activity);
        pageScroll.setFillViewport(true);
        pageScroll.setVerticalScrollBarEnabled(false);
        body.addView(pageScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout pages = new FrameLayout(activity);
        pageScroll.addView(pages, new android.widget.ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pageControl = buildControlPage(activity);
        pages.addView(pageControl, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pageStatus = buildStatusPage(activity);
        pages.addView(pageStatus, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return panelView;
    }

    /**
     * Header: live state dot, the name, the run-state pill and the collapse arrow, all in
     * one 28dp row (77px visible at 2.75).
     *
     * <p>The chevron keeps a 24x26dp box of its own instead of the 60dp
     * {@link #TARGET_DP}: 60dp is taller than the whole header and would drag it back to
     * the old 165px, whose only purpose was reach. Reach costs content here, and the header
     * is the user's explicit way out of the menu — the outside tap and the idle window both
     * still collapse it.
     */
    private LinearLayout buildHeader(Activity activity) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(activity, 2), 0, 0, 0);

        titleDot = GameTopUi.dot(activity, GameTopUi.INK_FAINT);
        header.addView(titleDot);

        title = GameTopUi.text(activity, 15, GameTopUi.INK, true);
        title.setText(t("雀魂助手"));
        header.addView(title, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statePill = GameTopUi.text(activity, 11, GameTopUi.INK_MUTED, true);
        statePill.setGravity(Gravity.CENTER);
        // 1dp vertical padding: the row is only 28dp tall, so the pill must not add air.
        statePill.setPadding(dp(activity, 8), dp(activity, 1), dp(activity, 8), dp(activity, 1));
        statePill.setBackground(GameTopUi.statePill(activity));
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillParams.leftMargin = dp(activity, 6);
        header.addView(statePill, pillParams);

        // Collapse arrow: a chevron-up path in a 24x26dp target, right of the pill. The
        // header is 28dp tall, so the box is the tallest thing that still fits it.
        FrameLayout arrowBox = new FrameLayout(activity);
        arrowBox.setContentDescription(t("收起面板"));
        GameTopUi.Glyph arrow = new GameTopUi.Glyph(activity, GameTopUi.ICON_CHEVRON_UP,
            GameTopUi.INK_MUTED, 16, 1.8f);
        FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(
            dp(activity, 16), dp(activity, 16), Gravity.CENTER);
        arrowBox.addView(arrow, arrowParams);
        ripple(arrowBox, GameTopUi.round(activity, 10, 0x00000000, 0, 0));
        arrowBox.setOnClickListener(view -> collapse());
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
            dp(activity, 24), dp(activity, 26));
        header.addView(arrowBox, boxParams);
        collapseButton = arrowBox;
        return header;
    }

    /**
     * The two-part tab above the cards: one gold capsule for the selected section, a
     * quiet card for the other. Both sides share the row's half width, so the hit target
     * is the full half rather than only the drawn text.
     */
    private LinearLayout buildTabs(Activity activity) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(GameTopUi.round(activity, 11, 0x14000000, 0, 0));
        // 1dp inset: the tab capsule is the whole 22dp band, the row only a hairline frame.
        row.setPadding(dp(activity, 1), dp(activity, 1), dp(activity, 1), dp(activity, 1));
        bindTab(activity, controlTab, TAB_CONTROL);
        bindTab(activity, statusTab, TAB_STATUS);
        row.addView(controlTab.shell, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        right.leftMargin = dp(activity, 2);
        row.addView(statusTab.shell, right);
        return row;
    }

    /**
     * Builds one tab part and binds its click listener exactly once. Rebuilding the menu
     * on a language switch drops the old shell together with its listener; the new shell
     * gets a fresh one, so nothing can accumulate on a listener list.
     */
    private void bindTab(Activity activity, TabView view, int id) {
        LinearLayout shell = new LinearLayout(activity);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setGravity(Gravity.CENTER);
        // 6dp side padding is still ~106px of half-row to hit; the 60dp minimum is gone
        // so the two-part tab fits the 22dp band instead of owning a 135px row.
        shell.setPadding(dp(activity, 6), 0, dp(activity, 6), 0);
        TextView label = GameTopUi.text(activity, 15, GameTopUi.INK_MUTED, true);
        label.setText(id == TAB_CONTROL ? t("控制") : t("状态"));
        label.setGravity(Gravity.CENTER);
        InGameLocalizer.layout(label, 1, InGameLocalizer.Align.CENTER);
        shell.addView(label, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.setOnClickListener(clicked -> selectTab(id));
        view.shell = shell;
        view.label = label;
    }

    private static View spacer(Context context, int heightDp) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)));
        return view;
    }

    private LinearLayout vertical(Activity activity) {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    /** Page 1: the independent switches the user asked for. */
    private LinearLayout buildControlPage(Activity activity) {
        LinearLayout page = vertical(activity);

        unattendedCard = switchCard(activity, page, t("无人值守"),
            t("助手服务自动分析并出牌"),
            () -> onToggle());
        unattendedTitle = (TextView) unattendedCard.getTag(TAG_TITLE);
        unattendedState = (TextView) unattendedCard.getTag(TAG_STATE);
        unattendedNote = (TextView) unattendedCard.getTag(TAG_NOTE);
        unattendedSwitch = (GameTopUi.Switch) unattendedCard.getTag(TAG_SWITCH);

        page.addView(spacer(activity, 4));

        // Card 2 is the only card with a ⓘ: it is handed to the card builder so the
        // button really exists in the tree before anyone wires it up or reads it back.
        GameTopUi.Glyph info = new GameTopUi.Glyph(activity, GameTopUi.ICON_INFO,
            GameTopUi.INK_MUTED, 18, 1.6f);
        rematchCard = switchCard(activity, page, t("自动续打"),
            t("整场牌局结束后自动继续"),
            () -> onRematchToggle(), info);
        rematchTitle = (TextView) rematchCard.getTag(TAG_TITLE);
        rematchState = (TextView) rematchCard.getTag(TAG_STATE);
        rematchNote = (TextView) rematchCard.getTag(TAG_NOTE);
        rematchSwitch = (GameTopUi.Switch) rematchCard.getTag(TAG_SWITCH);
        rematchInfo = rematchCard.getTag(TAG_INFO) instanceof GameTopUi.Glyph
            ? (GameTopUi.Glyph) rematchCard.getTag(TAG_INFO) : info;
        addRematchDetail(activity);
        // Unconditional on the freshly built button: it exists by construction, and the
        // listener is what makes the explanation reachable at all.
        rematchInfo.setOnClickListener(view -> {
            rematchDetailOpen = !rematchDetailOpen;
            applyState();
            restartIdleTimer();
        });

        page.addView(spacer(activity, 4));

        // Click-through fixed panel content: recommendation / honour points. Its own
        // switch, live via `panelSet`; it never closes any other feature.
        panelCard = switchCard(activity, page, t("推荐与番点"),
            t("穿透固定面板上的推荐与番点"),
            () -> onPanelToggle());
        panelTitle = (TextView) panelCard.getTag(TAG_TITLE);
        panelState = (TextView) panelCard.getTag(TAG_STATE);
        panelNote = (TextView) panelCard.getTag(TAG_NOTE);
        panelSwitch = (GameTopUi.Switch) panelCard.getTag(TAG_SWITCH);
        return page;
    }

    /**
     * Long explanation of the auto-next-game card, only visible while its ⓘ is open.
     * It is built together with the card (so a language switch rebuilds it too) and is
     * hidden by {@link #paintSwitches()}, which owns the expanded flag.
     */
    private void addRematchDetail(Activity activity) {
        if (rematchCard == null) return;
        rematchDetail = GameTopUi.text(activity, 11, GameTopUi.INK_MUTED, false);
        rematchDetail.setText(t("只调用游戏的“下一局”原按钮，不伪造请求；未开启时不排队"));
        rematchDetail.setPadding(dp(activity, 2), dp(activity, 4), dp(activity, 2), 0);
        InGameLocalizer.layout(rematchDetail, 3);
        rematchDetail.setBackground(GameTopUi.round(activity, 8, 0x14121A2A, 0, 0));
        rematchDetail.setVisibility(View.GONE);
        rematchCard.setTag(TAG_DETAIL, rematchDetail);
        rematchCard.addView(rematchDetail);
    }

    /**
     * One switch card: title, live state, switch on the right, and a note line that
     * explains why an enabled switch is not acting yet. The state is only ever painted
     * from the latest provider reply ({@link #applyReply}); taps go through the same
     * callbacks the old rows used. Padding, type and the switch are a step larger than
     * the old sidebar layout so the card is comfortable to hit.
     */
    private LinearLayout switchCard(Activity activity, LinearLayout page, String titleText,
                                    String subtitle, Runnable action) {
        return switchCard(activity, page, titleText, subtitle, action, null);
    }

    /**
     * Same card with an optional ⓘ button placed left of the switch. The button is built
     * here and handed back through {@link #TAG_INFO}, so the caller can never read a slot
     * that was never written (the crash this overload fixes).
     */
    private LinearLayout switchCard(Activity activity, LinearLayout page, String titleText,
                                    String subtitle, Runnable action, GameTopUi.Glyph info) {
        LinearLayout card = vertical(activity);
        card.setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 8), dp(activity, 3));
        // 34dp pre-scale = ~94px visible on the reporter's device: title + state + one note
        // line, with the whole card width as the tap target. The old 60dp minimum made
        // every card 135px tall and pushed two of the three switches out of the first
        // screenful; the 8dp side padding still gives a wide, comfortable row.
        card.setMinimumHeight(dp(activity, ROW_DP));
        ripple(card, GameTopUi.card(activity));
        card.setClickable(true);
        card.setContentDescription(titleText);
        // Card labels never toggle a setting; only the right-hand switch does.

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels = vertical(activity);
        // 15sp title / 12sp state: one notch down from 16/13 so the state line fits the
        // 34dp card without touching the note, and still comfortably readable at 2.75.
        TextView titleView = GameTopUi.text(activity, 15, GameTopUi.INK, true);
        titleView.setText(titleText);
        labels.addView(titleView);
        TextView stateView = GameTopUi.text(activity, 12, GameTopUi.INK_MUTED, false);
        stateView.setText(subtitle);
        stateView.setPadding(0, dp(activity, 1), 0, 0);
        labels.addView(stateView);
        top.addView(labels, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (info != null) {
            // The ⓘ sits inside the card but is not part of the card's tap target: it
            // only opens the explanation, it never flips the switch. 16dp keeps it inside
            // the 34dp card row.
            info.setContentDescription(titleText + "说明");
            LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                dp(activity, 22), dp(activity, 26));
            infoParams.gravity = Gravity.CENTER_VERTICAL;
            infoParams.leftMargin = dp(activity, 4);
            top.addView(info, infoParams);
        }

        GameTopUi.Switch toggle = new GameTopUi.Switch(activity);
        toggle.setContentDescription(titleText + "开关");
        toggle.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        switchParams.leftMargin = dp(activity, 8);
        top.addView(toggle, switchParams);
        card.addView(top);

        // One short note line, capped at a single line with an ellipsis: the idle copy
        // answers "what does a tap do", and a failure notice is what the card has to say
        // right now. Two lines with 13sp+12sp in a 34dp card would overflow it.
        TextView note = GameTopUi.text(activity, 11, GameTopUi.INK_MUTED, false);
        note.setPadding(0, dp(activity, 2), 0, 0);
        InGameLocalizer.layout(titleView, 1);
        InGameLocalizer.layout(stateView, 1);
        InGameLocalizer.layout(note, 1);
        card.addView(note);

        // Filled by buildControlPage right after this call; no resource ids involved.
        // `info` is null for the cards that have no ⓘ; the tag is only written when the
        // button exists, and buildControlPage never touches a slot it did not get.
        card.setTag(TAG_TITLE, titleView);
        card.setTag(TAG_STATE, stateView);
        card.setTag(TAG_NOTE, note);
        card.setTag(TAG_SWITCH, toggle);
        if (info != null) card.setTag(TAG_INFO, info);
        page.addView(card);
        return card;
    }

    /**
     * Page 2: the name row with its state dot over the detail rows, then the manual and
     * start / stop actions. The details are one short line each; anything longer is the
     * card's note or the notice, which the control page already shows.
     */
    private LinearLayout buildStatusPage(Activity activity) {
        LinearLayout page = vertical(activity);

        LinearLayout card = vertical(activity);
        card.setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 4));
        card.setBackground(GameTopUi.card(activity));
        statusCard = card;

        LinearLayout nameRow = new LinearLayout(activity);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = GameTopUi.dot(activity, GameTopUi.INK_FAINT);
        nameRow.addView(statusDot);
        statusMode = GameTopUi.text(activity, 14, GameTopUi.INK, true);
        nameRow.addView(statusMode, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(nameRow);

        statusDetail = statusLine(activity, card);
        statusService = statusLine(activity, card);
        statusRematch = statusLine(activity, card);
        InGameLocalizer.layout(statusMode, 3);
        InGameLocalizer.layout(statusDetail, 3);
        InGameLocalizer.layout(statusService, 2);
        InGameLocalizer.layout(statusRematch, 2);
        page.addView(card);

        page.addView(spacer(activity, 8));

        startStop = cell(activity, 14, GameTopUi.INK_ON_GOLD, true);
        startStop.setMinHeight(dp(activity, TARGET_DP));
        ripple(startStop, GameTopUi.round(activity, 12, GameTopUi.GOLD, 0, 0));
        startStop.setOnClickListener(view -> onToggle());
        InGameLocalizer.layout(startStop, 2, InGameLocalizer.Align.CENTER);
        page.addView(startStop, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(spacer(activity, 8));

        manualRow = actionRow(activity, t("打开管理应用"), GameTopUi.GOLD, null);
        manualRow.setOnClickListener(view -> openManager());
        page.addView(manualRow);
        return page;
    }

    /** One secondary row of the status card (dot-indented copy, one or two lines). */
    private TextView statusLine(Activity activity, LinearLayout card) {
        TextView line = GameTopUi.text(activity, 12, GameTopUi.INK_MUTED, false);
        line.setPadding(0, dp(activity, 3), 0, 0);
        card.addView(line);
        return line;
    }

    private LinearLayout actionRow(Activity activity, String label, int color, String description) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        // Status-page actions are the primary targets of that page, so they keep the full
        // 60dp (= 45dp under the finger) instead of the compact card height.
        row.setMinimumHeight(dp(activity, TARGET_DP));
        row.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
        TextView text = GameTopUi.text(activity, 14, color, true);
        text.setText(label);
        text.setGravity(Gravity.CENTER);
        InGameLocalizer.layout(text, 2, InGameLocalizer.Align.CENTER);
        row.addView(text, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (description != null) row.setContentDescription(description);
        ripple(row, GameTopUi.round(activity, 12, GameTopUi.CARD, GameTopUi.CARD_BORDER, 1));
        return row;
    }

    private static float screenWidthDp(Context context) {
        return context.getResources().getDisplayMetrics().widthPixels / GameTopUi.density(context);
    }

    private static void setText(TextView view, String text) {
        if (view == null) return;
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private void applyState() {
        if (panel == null) return;
        if (languageStale) {
            // A switched (or freshly seeded) language: rebuild the views of the current
            // stage so every label — including the ones only written at build time — is
            // painted from scratch in the new language. The stage root is reused, so
            // this neither stacks views nor adds listeners; the geometry is identical.
            languageStale = false;
            rebuildStage();
            if (panel == null) return;
        }
        // Repaint every static label first (a language switch has to reach them too);
        // the dynamic painters below then overwrite the values they own, so one pass is
        // always consistent. Nothing is added here, so repeated replies cannot stack.
        paintStatic();
        // The surface only exists in hook mode with a decided gate, so `visible` is false
        // only for the short window before the first answer in this activity.
        boolean visible = inGameVisible();
        if (hints != null) {
            hints.setPresentationEnabled(visible);
            if (!visible) hints.hideNow();
        }
        if (!visible) {
            // Nothing may be drawn before the mode is known: hide the whole in-game control
            // (handle, capsule and menu) and the hint layer, and keep the poll quiet.
            stopIdleTimer();
            stage = STAGE_HANDLE;
            show(handleTab, false);
            show(capsule, false);
            if (menu != null && menu.getParent() instanceof View) show((View) menu.getParent(), false);
            resolveOutsideClick();
            return;
        }
        boolean menuShown = stage == STAGE_MENU;
        if (menu != null && menu.getParent() instanceof View) {
            show((View) menu.getParent(), menuShown);
        } else {
            show(menu, menuShown);
        }
        show(capsule, stage == STAGE_CAPSULE);
        show(handleTab, stage == STAGE_HANDLE);
        // The root only accepts an outside tap while the menu is open (see StageRoot).
        resolveOutsideClick();
        // Handle and capsule carry the same live dot; paint them even when hidden so the
        // next reveal is already correct instead of flashing the previous colour.
        int state = stateColor();
        if (handleTab != null) handleTab.setStateColor(state);
        if (capsule != null) {
            capsule.setStateColor(state);
            capsule.setPhrase(capsuleText());
        }
        if (menuShown) {
            paintHeader();
            paintTab();
            paintSwitches();
            paintStatusPage();
            paintActions();
        }
        // Polling repaints arrive here every few seconds and deliberately never touch
        // the idle window: only a real interaction (restartIdleTimer) re-arms it, and the
        // capsule / menu are only entered through those interaction paths.
    }

    /** The root is clickable exactly while the open menu is on screen. */
    private void resolveOutsideClick() {
        if (panel == null) return;
        boolean wanted = stage == STAGE_MENU && inGameVisible();
        if (panel.isClickable() == wanted) return;
        panel.setClickable(wanted);
        panel.setFocusable(false);
        // One listener, installed once per root (a new root is built on attach and on a
        // language switch): assigning it again would stack duplicates on the same view.
        if (wanted && !panel.hasOnClickListeners()) panel.setOnClickListener(view -> collapse());
    }

    private static void show(View view, boolean visible) {
        if (view == null) return;
        int wanted = visible ? View.VISIBLE : View.GONE;
        if (view.getParent() instanceof ScaledMenuHost) {
            View host = (View) view.getParent();
            if (host.getVisibility() != wanted) host.setVisibility(wanted);
        }
        if (view.getVisibility() != wanted) view.setVisibility(wanted);
    }

    /** Paints a card line, reusing the card's pre-built text view when possible. */
    private static void setCardText(View card, int key, TextView fallback, String text) {
        TextView view = fallback;
        if (card != null && card.getTag(key) instanceof TextView) view = (TextView) card.getTag(key);
        setText(view, text);
    }

    private void selectTab(int wanted) {
        if (tab == wanted) return;
        tab = wanted;
        restartIdleTimer();
        applyState();
    }

    /** Gold capsule for the active tab, quiet card for the other one. */
    private void paintTab() {
        boolean control = tab == TAB_CONTROL;
        styleTab(controlTab, control);
        styleTab(statusTab, !control);
        show(pageControl, control);
        show(pageStatus, !control);
    }

    private static void styleTab(TabView view, boolean selected) {
        if (!view.bound()) return;
        view.shell.setBackground(selected ? GameTopUi.accentPill(view.shell.getContext())
            : GameTopUi.quietPill(view.shell.getContext()));
        int color = selected ? GameTopUi.INK_ON_GOLD : GameTopUi.INK_MUTED;
        if (view.label.getCurrentTextColor() != color) view.label.setTextColor(color);
    }

    /** Live state colour shared by the handle, the capsule and the header. */
    private int stateColor() {
        if (starting) return GameTopUi.STATE_AMBER;
        if (enabled) return GameTopUi.STATE_GREEN;
        if (available) return GameTopUi.STATE_AMBER;
        if (demo) return GameTopUi.STATE_AMBER;
        if (unreachable) return GameTopUi.DANGER;
        return GameTopUi.STATE_AMBER;
    }

    /**
     * True when the assistant is really running: the manager says unattended mode is on,
     * or its service answered but the mode is off. A start sequence is not "running" yet —
     * the capsule says "starting" for that.
     */
    private boolean runningNow() {
        // A running service that still reports a self-test is not "running" for the user:
        // the same caveat that keeps the unattended card from switching on.
        return enabled || (available && !demo && !starting);
    }

    /** The short phrase of the capsule; never truncated, the capsule grows instead. */
    private String capsuleText() {
        if (starting) return t("启动中");
        if (enabled && !available) return t("启动中");
        if (runningNow()) return t("助手运行中");
        return t("助手未开启");
    }

    /** Header: the state dot and the run-state pill (the name is static copy). */
    private void paintHeader() {
        GameTopUi.setDotColor(titleDot, stateColor());
        String pill;
        if (starting) pill = t("启动中");
        else if (enabled) pill = t("无人值守中");
        else if (available && !demo) pill = t("待机");
        else if (demo) pill = t("自检中");
        else if (unreachable) pill = t("服务未连接");
        else pill = t("未开启");
        setText(statePill, pill);
        if (statePill != null) {
            boolean gold = !starting && enabled;
            statePill.setTextColor(gold ? GameTopUi.GOLD : GameTopUi.INK_MUTED);
            statePill.setBackground(GameTopUi.statePill(statePill.getContext()));
        }
        InGameLocalizer.layout(statePill, 1, InGameLocalizer.Align.CENTER);
    }

    /** Live card values: state words, switches, notes and the ⓘ of card 2. */
    private void paintSwitches() {
        // Card 1 is a pure view of the mode: enabled only when the manager says so,
        // and never editable while a start sequence or another action is in flight.
        if (unattendedSwitch != null) {
            unattendedSwitch.setOn(enabled, true);
            unattendedSwitch.setBusy(starting || actionBusy.get());
        }
        setCardText(unattendedCard, TAG_STATE, unattendedState, unattendedStateText());
        setText(unattendedNote, unattendedNoteText());
        int unattendedTone = noticeFailed && noticeRefersToUnattended()
            ? GameTopUi.DANGER : GameTopUi.INK_MUTED;
        if (unattendedNote != null) unattendedNote.setTextColor(unattendedTone);
        if (unattendedInfo != null) show(unattendedInfo, false);

        // Card 2 mirrors the stored rematch opt-in; it stays tappable while unattended
        // mode is off, exactly like the old single row did.
        if (rematchSwitch != null) {
            rematchSwitch.setOn(rematch, true);
            rematchSwitch.setBusy(actionBusy.get() && !starting);
        }
        setCardText(rematchCard, TAG_STATE, rematchState, rematch ? t("已开启") : t("已关闭"));
        if (rematchState != null) {
            rematchState.setTextColor(rematchActive ? GameTopUi.GOLD
                : rematch ? GameTopUi.INK : GameTopUi.INK_FAINT);
        }
        setText(rematchNote, rematchNoteText());
        if (rematchNote != null) {
            rematchNote.setTextColor(panelNoticeFailed()
                ? GameTopUi.DANGER : GameTopUi.INK_MUTED);
        }
        if (rematchInfo != null) {
            rematchInfo.setColor(rematchDetailOpen ? GameTopUi.GOLD : GameTopUi.INK_MUTED);
        }
        if (rematchDetail != null) show(rematchDetail, rematchDetailOpen);

        // Card 3 mirrors the persisted "recommendation / honour-points panel" opt-in.
        // It stays tappable whatever the other two cards say: it only controls the
        // click-through fixed panel content and never closes any other feature.
        if (panelSwitch != null) {
            panelSwitch.setOn(recommendationPanel, true);
            panelSwitch.setBusy(actionBusy.get() && !starting);
        }
        setCardText(panelCard, TAG_STATE, panelState,
            recommendationPanel ? t("已开启") : t("已关闭"));
        if (panelState != null) {
            panelState.setTextColor(recommendationPanel ? GameTopUi.GOLD : GameTopUi.INK_FAINT);
        }
        setText(panelNote, panelNoteText());
        if (panelNote != null) {
            panelNote.setTextColor(panelNoticeFailed() ? GameTopUi.DANGER : GameTopUi.INK_MUTED);
        }
    }

    /** True when the last failed notice came from the unattended path. */
    private boolean noticeRefersToUnattended() {
        return noticeKey.contains("无人值守") || noticeKey.contains("启动") || noticeKey.contains("管理应用");
    }

    /** True when the last failed notice came from the panel (recommendation) path. */
    private boolean panelNoticeFailed() {
        return noticeFailed && noticeKey.contains("面板");
    }

    private void paintStatusPage() {
        if (statusMode == null) return;
        GameTopUi.setDotColor(statusDot, stateColor());
        setText(statusMode, modeText());
        if (statusMode != null) {
            statusMode.setTextColor(noticeFailed ? GameTopUi.DANGER : GameTopUi.INK);
        }
        setText(statusDetail, detailText());
        setText(statusService, serviceText());
        setText(statusRematch, rematchText());
        if (statusRematch != null) {
            statusRematch.setTextColor(rematchActive ? GameTopUi.GOLD : GameTopUi.INK_MUTED);
        }
    }

    private void paintActions() {
        if (startStop != null) {
            setText(startStop, toggleText());
            startStop.setAlpha(starting ? .55f : 1f);
            startStop.setBackground(GameTopUi.round(startStop.getContext(), 12,
                starting ? 0xFF8A6A32 : GameTopUi.GOLD, 0, 0));
        }
        String manualText = manualText();
        if (manualRow != null) {
            show(manualRow, manualText != null);
            manualRow.setContentDescription(manualText == null ? t("打开管理应用") : manualText);
            if (manualRow.getChildCount() > 0 && manualRow.getChildAt(0) instanceof TextView) {
                TextView label = (TextView) manualRow.getChildAt(0);
                setText(label, manualText == null ? t("打开管理应用") : manualText);
                label.setTextColor(noticeFailed ? GameTopUi.DANGER : GameTopUi.GOLD);
            }
        }
        if (collapseButton != null) setDescription(collapseButton, t("收起面板"));
    }

    private String unattendedStateText() {
        if (starting) return t("启动中…");
        if (enabled) return t("运行中");
        if (demo) return t("自检中 · 暂不可开");
        if (!available) return t("未启动");
        return t("已关闭");
    }

    /**
     * Why an "on" switch is not acting yet, or what a tap will do. Kept short: the card
     * is narrow, so the line may wrap to a second line inside it but never grows the
     * card's width.
     */
    private String unattendedNoteText() {
        if (!notice.isEmpty() && noticeRefersToUnattended()) return notice;
        if (starting) return t("正在启动分析与无人值守…");
        if (enabled) return t("点按关闭 · 助手服务继续运行");
        if (demo) return t("模型自检中，结束后可开启");
        if (!available) return t("点按开启，自动拉起管理应用服务");
        return t("点按开启 AI 自动出牌");
    }

    private String rematchNoteText() {
        if (demo && rematch) return t("自检中不生效");
        if (rematch && !rematchActive) return t("待条件满足");
        if (rematch) return t("仅按下一局原按钮");
        return t("牌局结束后自动继续");
    }

    /** Note line of card 3: why it does or does not draw, in one short phrase. */
    private String panelNoteText() {
        if (panelNoticeFailed()) return notice;
        if (recommendationPanel) return t("固定面板显示建议牌与番点");
        return t("已隐藏建议牌与番点");
    }

    private String serviceText() {
        if (unreachable) return managerMissing ? t("管理应用 · 未安装") : t("管理应用 · 无响应");
        if (!available && demo) return t("前台服务 · 自检中");
        if (!available) return t("前台服务 · 未运行");
        return t("前台服务 · 运行中") + (remoteMessage.isEmpty() ? "" : " · " + clip(remoteMessageText(), 14));
    }

    private String modeText() {
        if (starting && !available) return t("正在启动助手服务…");
        if (!available && demo) return t("模型自检中 · 暂不可开启");
        if (!available) return t("助手未启动");
        String suffix = remoteMessage.isEmpty() ? "" : " · " + clip(remoteMessageText(), 16);
        return (enabled ? t("无人值守已开启") : t("助手已就绪 · 无人值守关闭")) + suffix;
    }

    /**
     * The overlay error the manager reported, or null. In-game surfaces only exist in hook
     * mode, so this is normally empty here; it is shown when a *different* running assistant
     * instance reported a broken overlay, which is what the user needs to see in that case.
     *
     * The reason token itself (`overlayError`) is a diagnostic identifier, not copy, and is
     * kept verbatim.
     */
    private String overlayErrorText() {
        if (display.getMode() == GameDisplayGate.Mode.OVERLAY) {
            return displayError.isEmpty() ? t("系统悬浮窗模式") : t("系统悬浮窗不可用 · ") + displayError;
        }
        return null;
    }

    private String toggleText() {
        if (starting) return t("启动中…");
        return enabled ? t("关闭无人值守") : t("开启无人值守");
    }

    /**
     * Full auto-rematch line for the status page; the switch card shows the same
     * facts split over its state and note lines.
     */
    private String rematchText() {
        String state = rematch ? t("开") : t("关");
        String note;
        if (demo && rematch) note = t("自检中不生效");
        else if (rematch && !rematchActive) note = t("待条件满足");
        else if (rematch) note = t("仅按下一局原按钮");
        else note = t("点按开启");
        return t("自动下一局 · ") + state + "（" + note + "）";
    }

    private String detailText() {
        if (!notice.isEmpty()) return notice;
        if (!displayError.isEmpty() && overlayErrorText() != null) return overlayErrorText();
        if (!available) return t("点击开启即自动启动管理应用的前台分析服务");
        return enabled ? t("点击关闭只关闭无人值守，助手服务继续运行") : t("开启后按 AI 首选操作，可随时在此关闭");
    }

    /** Label of the hand-off action; null hides the row. */
    private String manualText() {
        if (starting) return null;
        if (unreachable) return managerMissing ? t("未安装管理应用 · 点击打开") : t("管理应用无响应 · 点击打开");
        if (noticeFailed) return t("点击打开管理应用");
        return null;
    }

    /**
     * Replaces the stage root with a freshly built one for the same stage.
     *
     * Only the view tree is rebuilt: the stage, tab, language, mode and every value
     * polled from the manager are carried over, and the new tree is inserted with the
     * same layout params the old one had. Used when the copy language changes, so no
     * label can keep a string from the previous language. Every view is created here,
     * so each listener is created once with its view — nothing is re-bound on an
     * existing view and no listener list can grow.
     */
    private void rebuildStage() {
        Activity activity = active.get();
        View decorView = decor;
        if (panel == null || activity == null || !(decorView instanceof ViewGroup) || panelParams == null) return;
        ViewGroup parent = (ViewGroup) decorView;
        if (parent.indexOfChild(panel) < 0) return;
        int stage = this.stage;
        int tab = this.tab;
        // Detach only the old tree; the hint layer is owned by this control and keeps
        // running against the same decor view.
        parent.removeView(panel);
        panel = null;
        handleTab = null;
        capsule = null;
        menu = null;
        title = null;
        titleDot = null;
        statePill = null;
        collapseButton = null;
        controlTab.unbind();
        statusTab.unbind();
        pageControl = pageStatus = null;
        unattendedCard = null;
        unattendedTitle = unattendedState = unattendedNote = null;
        unattendedSwitch = null;
        unattendedInfo = null;
        rematchCard = null;
        rematchTitle = rematchState = rematchNote = null;
        rematchSwitch = null;
        rematchInfo = null;
        rematchDetail = null;
        panelCard = null;
        panelTitle = panelState = panelNote = null;
        panelSwitch = null;
        statusCard = null;
        statusDot = null;
        statusMode = statusDetail = statusService = statusRematch = null;
        startStop = null;
        manualRow = null;

        // A rebuild (language switch) is the second place the whole tree is created, so it
        // carries the same boundary as attach(): a failure leaves nothing half-built
        // instead of killing the game process, and the next attach rebuilds cleanly.
        try {
            panel = build(activity);
            parent.addView(panel, panelParams);
            this.stage = stage;
            this.tab = tab;
            applyState();
            if (hooks == null) {
                hooks = new SizeWatcher(activity);
                parent.addOnLayoutChangeListener(hooks);
            }
        } catch (RuntimeException | LinkageError failure) {
            recordSurfaceFailure(activity, "REBUILD", failure);
            panel = null;
        }
    }

    /* ------------------------------------------------------------------- copy */

    /** The language the surface draws in; never null. */
    private ManagerLanguage lang() {
        return uiLanguage.language;
    }

    /** Translation of a Chinese source string for the current language. */
    private String t(String zh) {
        return InGameLocalizer.t(lang(), zh);
    }

    /**
     * Seeds the language before the first reply can arrive.
     *
     * The menu lives in the game process, so the manager's stored preference is the only
     * local source; it is a read-only projection used for the very first frame and is
     * replaced by whatever tag the first status reply carries. A failed read keeps the
     * current value (Chinese), so a broken preference never shows a half-translated menu.
     */
    private void seedLanguage(Context context) {
        if (!uiLanguage.tag.isEmpty()) return;
        // The stored *choice*, with "follow the system" resolved against this device, so
        // the very first frame already matches what the manager page will show.
        ManagerLanguage stored =
            InGameLocalizer.currentLanguage(context);
        // Tag stays empty on purpose: the first reply is authoritative and must be able
        // to move the copy even if the stored value was stale.
        uiLanguage.force(stored);
        notice = InGameLocalizer.t(lang(), noticeKey);
        languageStale = true;
    }

    /**
     * Records a notice in Chinese and paints it in the current language.
     *
     * [noticeKey] keeps the untouched source phrase: the failure predicates below and the
     * card checks read it, so they keep working in every language.
     */
    private void setNotice(String zh) {
        noticeKey = zh == null ? "" : zh;
        notice = InGameLocalizer.t(lang(), noticeKey);
    }

    /**
     * A manager status line: translated only when it is one of the known phrases; an
     * unknown diagnostic is returned exactly as the manager sent it.
     */
    private String remoteMessageText() {
        return InGameLocalizer.t(lang(), remoteMessage);
    }

    /**
     * Repaints every label that is not part of the polled value set: tab names, the
     * three card titles, the header name, the hand-off row and the long explanation of
     * card 2. (The pinned collapse row these labels used to include is gone: the header
     * chevron, the outside tap and the idle window all collapse the menu.)
     *
     * Called from [applyState] on every pass, so a language switch reaches the *static*
     * labels too and not only the values that were already dynamic. Nothing here adds a
     * listener or a view, which is what keeps repeated replies from stacking anything.
     */
    private void paintStatic() {
        if (controlTab.bound()) {
            setText(controlTab.label, t("控制"));
            InGameLocalizer.layout(controlTab.label, 1, InGameLocalizer.Align.CENTER);
            setDescription(controlTab.shell, t("控制"));
        }
        if (statusTab.bound()) {
            setText(statusTab.label, t("状态"));
            InGameLocalizer.layout(statusTab.label, 1, InGameLocalizer.Align.CENTER);
            setDescription(statusTab.shell, t("状态"));
        }

        setStaticCard(unattendedCard, "无人值守", "助手服务自动分析并出牌", true);
        setStaticCard(rematchCard, "自动续打", "整场牌局结束后自动继续", false);
        setStaticCard(panelCard, "推荐与番点", "穿透固定面板上的推荐与番点", false);

        setText(title, t("雀魂助手"));
        InGameLocalizer.layout(title, 1);
        if (collapseButton != null) setDescription(collapseButton, t("收起面板"));

        // The hand-off row only names its static label while it has no live label of its
        // own; [paintActions] owns it otherwise.
        if (manualRow == null || manualText() == null) paintActionRow(manualRow, "打开管理应用");

        setText(rematchDetail, t("只调用游戏的“下一局”原按钮，不伪造请求；未开启时不排队"));
        InGameLocalizer.layout(rematchDetail, 4);

        if (handleTab != null) setDescription(handleTab, t("展开雀魂助手"));
        if (capsule != null) setDescription(capsule, t("打开雀魂助手菜单"));

        InGameLocalizer.layout(unattendedNote, 1);
        InGameLocalizer.layout(rematchNote, 1);
        InGameLocalizer.layout(panelNote, 1);
        InGameLocalizer.layout(statusMode, 3);
        InGameLocalizer.layout(statusDetail, 3);
        InGameLocalizer.layout(statusService, 2);
        InGameLocalizer.layout(statusRematch, 2);
        InGameLocalizer.layout(startStop, 2, InGameLocalizer.Align.CENTER);
        InGameLocalizer.layout(statePill, 1, InGameLocalizer.Align.CENTER);
    }

    /**
     * Card title (plus its accessibility label) and, while the card still shows its
     * static subtitle, that subtitle.
     *
     * [unattended] is the only card that painted a static subtitle at build time; the
     * other two were built with their state line already live, so repainting it here
     * would race their painters.
     */
    private void setStaticCard(View card, String titleZh, String subtitleZh, boolean staticSubtitle) {
        if (card == null) return;
        TextView titleView = card.getTag(TAG_TITLE) instanceof TextView
            ? (TextView) card.getTag(TAG_TITLE) : null;
        TextView stateView = card.getTag(TAG_STATE) instanceof TextView
            ? (TextView) card.getTag(TAG_STATE) : null;
        TextView noteView = card.getTag(TAG_NOTE) instanceof TextView
            ? (TextView) card.getTag(TAG_NOTE) : null;
        setText(titleView, t(titleZh));
        if (staticSubtitle && stateView != null && isStaticSubtitle(stateView, subtitleZh)) {
            setText(stateView, t(subtitleZh));
        }
        setDescription(card, t(titleZh));
        View toggle = card.getTag(TAG_SWITCH) instanceof View ? (View) card.getTag(TAG_SWITCH) : null;
        if (toggle != null) setDescription(toggle, t(titleZh) + t("开关"));
        // The compact card keeps every line single-row (see switchCard): a 34dp card
        // cannot hold two lines of title/state plus a note.
        InGameLocalizer.layout(titleView, 1);
        InGameLocalizer.layout(stateView, 1);
        InGameLocalizer.layout(noteView, 1);
    }

    /** True while the state line still shows its card's static subtitle. */
    private boolean isStaticSubtitle(TextView stateView, String subtitleZh) {
        CharSequence current = stateView.getText();
        return TextUtils.equals(current, subtitleZh) || TextUtils.equals(current, t(subtitleZh));
    }

    private void paintActionRow(LinearLayout row, String labelZh) {
        if (row == null) return;
        String label = t(labelZh);
        setDescription(row, label);
        if (row.getChildCount() > 0 && row.getChildAt(0) instanceof TextView) {
            TextView text = (TextView) row.getChildAt(0);
            setText(text, label);
            InGameLocalizer.layout(text, 2, InGameLocalizer.Align.CENTER);
        }
    }

    private static void setDescription(View view, String text) {
        if (view == null) return;
        if (!TextUtils.equals(view.getContentDescription(), text)) view.setContentDescription(text);
    }

    private static String clip(String text, int limit) {
        if (text.length() <= limit) return text;
        return text.substring(0, limit) + "…";
    }

    /* ------------------------------------------------------------------ actions */

    private void onToggle() {
        if (starting) return;
        if (enabled) disable();
        else enable();
    }

    private void enable() {
        if (!actionBusy.compareAndSet(false, true)) return;
        starting = true;
        startingAt = SystemClock.elapsedRealtime();
        setNotice("正在启动分析与无人值守…");
        noticeFailed = false;
        applyState();
        call("assistLaunch", null, REQUEST_TIMEOUT_MS, reply -> {
            actionBusy.set(false);
            if (!starting) return;
            String state = reply == null ? "unreachable" : reply.getString("state", "");
            if (state == null) state = "";
            if ("unreachable".equals(state)) {
                starting = false;
                noticeFailed = true;
                unreachable = true;
                managerMissing = reply != null && !reply.getBoolean("installed", true);
                setNotice(managerMissing ? "未找到管理应用 · 请先安装" : "管理应用无响应 · 点击重试");
            } else {
                applyReply(reply);
                if (AssistLaunch.RUNNING.equals(state) || AssistLaunch.STARTED.equals(state)) {
                    setNotice("已请求启动 · 等待服务就绪");
                } else if (AssistLaunch.RESTRICTED.equals(state) || AssistLaunch.FORBIDDEN.equals(state)) {
                    String token = reply.getString("token");
                    if (launchTrampoline(token)) {
                        setNotice("正在通过管理应用完成启动…");
                    } else {
                        starting = false;
                        noticeFailed = true;
                        setNotice("无法打开管理应用 · 请手动开启助手");
                    }
                } else {
                    starting = false;
                    noticeFailed = true;
                    setNotice(AssistLaunch.UNAVAILABLE.equals(state)
                        ? "无法启动前台服务 · 点击重试" : "启动中断 · 点击重试");
                }
            }
            applyState();
            schedulePoll(POLL_MENU_MS);
        });
    }

    /**
     * Persists the auto-rematch opt-in through the UID-checked provider. Sends the
     * *requested* value (a toggle of what the last reply reported), and the reply is
     * applied like any poll, so the row can never show a value the manager did not
     * store. Revoking while a settlement is being walked cancels the adapter's pending
     * action on the next heartbeat; it deliberately does not change unattended mode.
     */
    private void onRematchToggle() {
        if (!actionBusy.compareAndSet(false, true)) return;
        boolean wanted = !rematch;
        setNotice(wanted ? "正在开启整场后续局…" : "正在关闭整场后续局…");
        noticeFailed = false;
        applyState();
        Bundle args = new Bundle();
        args.putBoolean("enabled", wanted);
        call("rematchSet", args, REQUEST_TIMEOUT_MS, reply -> {
            actionBusy.set(false);
            if (reply == null) {
                noticeFailed = true;
                setNotice("管理应用无响应 · 设置可能未保存");
            } else {
                applyReply(reply);
                // The reply carries the stored value, so a write that did not stick is
                // reported instead of leaving the row claiming the new state.
                noticeFailed = rematch != wanted;
                setNotice(rematch != wanted ? "设置未保存 · 当前" + (rematch ? "开" : "关")
                    : wanted ? "整场后续局已开启" : "整场后续局已关闭 · 无人值守未变");
            }
            applyState();
            schedulePoll(POLL_MENU_MS);
        });
    }

    /**
     * 通过 UID 校验过的 provider 实时切换「推荐与番点面板」。发送的是*目标值*
     * （对上一次回复值的取反），回复像普通轮询一样被应用，所以卡片不可能显示一个
     * 管理器没有真正保存的值。它只写这一个偏好：不启停服务、不改无人值守 /
     * 自动下一局 / 显示模式，也不影响猫头提示。
     */
    private void onPanelToggle() {
        if (!actionBusy.compareAndSet(false, true)) return;
        boolean wanted = !recommendationPanel;
        setNotice(wanted ? "正在显示推荐与番点面板…" : "正在隐藏推荐与番点面板…");
        noticeFailed = false;
        applyState();
        Bundle args = new Bundle();
        args.putBoolean("enabled", wanted);
        call("panelSet", args, REQUEST_TIMEOUT_MS, reply -> {
            actionBusy.set(false);
            if (reply == null) {
                noticeFailed = true;
                setNotice("管理应用无响应 · 面板设置可能未保存");
            } else {
                applyReply(reply);
                noticeFailed = recommendationPanel != wanted;
                setNotice(recommendationPanel != wanted
                    ? "面板设置未保存 · 当前" + (recommendationPanel ? "开" : "关")
                    : wanted ? "推荐与番点面板已开启" : "推荐与番点面板已关闭 · 其他功能未变");
            }
            applyState();
            schedulePoll(POLL_MENU_MS);
        });
    }

    private void disable() {
        if (!actionBusy.compareAndSet(false, true)) return;
        setNotice("正在关闭无人值守…");
        noticeFailed = false;
        starting = false;
        applyState();
        Bundle args = new Bundle();
        args.putBoolean("enabled", false);
        call("autoSet", args, REQUEST_TIMEOUT_MS, reply -> {
            actionBusy.set(false);
            if (reply == null) {
                noticeFailed = true;
                setNotice("管理应用无响应 · 模式可能仍在开启");
            } else {
                applyReply(reply);
                noticeFailed = enabled;
                setNotice(enabled ? "未能关闭无人值守 · 点击重试" : "已关闭无人值守 · 助手服务保持运行");
            }
            applyState();
            schedulePoll(POLL_MENU_MS);
        });
    }

    /**
     * Explicit, user-initiated hand-off to the manager app. Only a single-use token
     * issued by the UID-checked provider unlocks the trampoline activity, and it is
     * opened from the visible game activity so no background-activity rule is bent.
     */
    private boolean launchTrampoline(String token) {
        Activity activity = active.get();
        if (activity == null || token == null || token.isEmpty()) return false;
        Intent intent = new Intent();
        intent.setClassName(MANAGER, TRAMPOLINE);
        intent.putExtra(TOKEN_EXTRA, token);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException missing) {
            return false;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private void openManager() {
        Activity activity = active.get();
        if (activity == null) return;
        Intent intent = activity.getPackageManager().getLaunchIntentForPackage(MANAGER);
        if (intent == null) {
            setNotice("未安装管理应用");
            noticeFailed = true;
            managerMissing = true;
            applyState();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (RuntimeException ignored) {
            // The launcher activity may be disabled; the text stays as a hint.
        }
    }

    /* ---------------------------------------------------------------- transport */

    private void schedulePoll(long delay) {
        main.removeCallbacks(pollTick);
        if (panel != null) main.postDelayed(pollTick, delay);
    }

    private void poll() {
        if (panel == null || active.get() == null) return;
        if (starting && SystemClock.elapsedRealtime() - startingAt > START_TIMEOUT_MS) {
            starting = false;
            noticeFailed = true;
            setNotice("启动超时 · 点击重试");
            applyState();
        }
        if (!actionBusy.get() && pollBusy.compareAndSet(false, true)) {
            call("autoStatus", new Bundle(), REQUEST_TIMEOUT_MS, reply -> {
                pollBusy.set(false);
                if (reply == null || "unreachable".equals(reply.getString("state", ""))) {
                    unreachable = true;
                    managerMissing = reply != null && !reply.getBoolean("installed", true);
                    available = false;
                    enabled = false;
                    demo = false;
                    remoteMessage = "";
                    // No reply says nothing about the display mode: this control already
                    // decided from the answer it got before it was built, and a provider
                    // hiccup is not a reason to create or hide a surface. The capsule keeps
                    // offering "open manager" instead.
                } else {
                    applyReply(reply);
                    if (starting && available && enabled) {
                        starting = false;
                        setNotice("");
                        noticeFailed = false;
                    }
                }
                applyState();
            });
        }
        schedulePoll(stage == STAGE_MENU || starting ? POLL_MENU_MS : POLL_IDLE_MS);
        if (starting || actionBusy.get()) {
            // A reply or a start completion is still on its way; hold the control on
            // whatever stage it is so that reply lands on a visible view. The idle
            // window itself is untouched, so a poll never extends it.
            deferCollapse();
        }
    }

    /**
     * Applies one status reply: live state plus the manager's display snapshot.
     *
     * The display fields are informational here. This control only exists because the mode
     * was hook when it was built, and that does not change for the rest of the process; the
     * snapshot is passed to {@link #display} so a *different* running service (new nonce) is
     * still recognised, and `overlayError` is kept for the status page.
     */
    private void applyReply(Bundle reply) {
        // The language travels with the reply. Adopt it before the values are painted,
        // so this same reply is already drawn in the new language; a reply without the
        // key (older manager, partial reply) leaves the current one untouched.
        boolean languageChanged = uiLanguage.apply(reply.getString("uiLanguage"));
        if (languageChanged) {
            notice = t(noticeKey);
            languageStale = true;
        }
        available = reply.getBoolean("available");
        enabled = reply.getBoolean("enabled");
        demo = reply.getBoolean("demo");
        String message = reply.getString("message", "");
        remoteMessage = message == null ? "" : message.trim();
        display.apply(reply);
        // Only a reply that is *about* the overlay carries a reason. In hook mode a stale
        // `overlayError` from an earlier selection must not leak onto this control's status.
        String error = reply.getString("overlayError", "");
        displayError = display.getMode() == GameDisplayGate.Mode.OVERLAY && error != null
            ? error : "";
        rematch = reply.getBoolean("rematch", rematch);
        rematchActive = reply.getBoolean("rematchActive", false);
        // Persisted click-through panel opt-in; defaults to on like the manager side.
        recommendationPanel = reply.getBoolean("recommendationPanel", recommendationPanel);
        unreachable = false;
        managerMissing = false;
    }

    /**
     * Whether this control may show itself at all. Hook mode is confirmed in [register], so
     * this only gates the moment before the gate's first answer (a resumed activity can be
     * attached a few hundred milliseconds earlier).
     */
    private boolean inGameVisible() {
        return inGameEnabled() && display.isDecided();
    }

    /**
     * One provider round trip with a hard timeout. The reply is delivered on the main
     * thread and dropped when the control moved to another activity in the meantime,
     * so a slow manager can neither block the game nor repaint a detached panel.
     */
    private void call(String method, Bundle args, long timeoutMs, Consumer<Bundle> done) {
        Activity activity = active.get();
        if (activity == null) {
            done.accept(null);
            return;
        }
        Context context = activity.getApplicationContext();
        int token = generation;
        AtomicBoolean finished = new AtomicBoolean();
        main.postDelayed(() -> {
            if (finished.compareAndSet(false, true)) done.accept(null);
        }, timeoutMs);
        worker.execute(() -> {
            Bundle reply = null;
            try {
                reply = context.getContentResolver().call(ENDPOINT, method, null, args);
            } catch (RuntimeException | Error ignored) {
                // The manager may be missing, disabled, or have revoked its URI grant.
            }
            if (reply == null) {
                Bundle failure = new Bundle();
                failure.putString("state", "unreachable");
                failure.putBoolean("installed", managerInstalled(context));
                reply = failure;
            }
            Bundle result = reply;
            main.post(() -> {
                if (!finished.compareAndSet(false, true)) return;
                if (token != generation) return;
                done.accept(result);
            });
        });
    }

    private static boolean managerInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(MANAGER, 0);
            return true;
        } catch (Exception missing) {
            return false;
        }
    }

    /**
     * Translucent, token-guarded entry point used only when Android refuses the
     * background foreground-service start. It exists because the game is a different
     * UID: a visible activity of the manager app is a documented background-start
     * exemption, so the service start that happens here is legal. The activity shows
     * no UI and closes itself immediately.
     */
    public static final class AssistLaunchActivity extends Activity {
        private boolean authorized;
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            Intent intent = getIntent();
            String token = intent == null ? null : intent.getStringExtra(TOKEN_EXTRA);
            if (!AssistLaunch.consumeToken(token) || !fromGame()) {
                // Missing, stale, replayed or foreign start: never start anything here.
                finish();
                return;
            }
            authorized = true;
        }

        @Override protected void onResume() {
            super.onResume();
            if (!authorized) return;
            authorized = false;
            // The manager is visibly foregrounded here (this translucent activity is
            // resumed), so the foreground-service start is legal. startFromForeground
            // starts the service without blocking the main thread and records the real
            // outcome to the assistant diagnostics, so the in-game control can observe
            // the result through the next autoStatus poll instead of timing out.
            AssistLaunch.startFromForeground(getApplicationContext());
            long deadline = SystemClock.elapsedRealtime() + 5_000L;
            Handler handler = new Handler(Looper.getMainLooper());
            Runnable check = new Runnable() {
                @Override public void run() {
                    if (AssistLaunch.isForegroundReady() || AssistLaunch.isStartBlocked()
                            || SystemClock.elapsedRealtime() >= deadline) {
                        finish();
                    } else {
                        handler.postDelayed(this, 100L);
                    }
                }
            };
            handler.post(check);
        }

        /** Best-effort caller check; the single-use token is the actual gate. */
        private boolean fromGame() {
            try {
                Uri referrer = getReferrer();
                return referrer == null || GAME.equals(referrer.getAuthority());
            } catch (RuntimeException ignored) {
                return true;
            }
        }
    }
}
