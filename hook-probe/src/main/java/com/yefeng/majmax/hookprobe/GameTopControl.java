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
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.yefeng.majmax.hookprobe.helper.InGameLocalizer;
import com.yefeng.majmax.hookprobe.manager.AssistLaunch;
import com.yefeng.majmax.hookprobe.manager.ManagerLanguage;
import com.yefeng.majmax.hookprobe.manager.OpponentNaming;
import com.yefeng.majmax.hookprobe.manager.OpponentNamingMode;
import com.yefeng.majmax.hookprobe.manager.OpponentNamingSettings;

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
     * Card slot remembering whether a naming cell is painted as selected, so the 1 s poll does
     * not rebuild a RippleDrawable for three cells on every pass. Unrelated to the four slots
     * above; it is only ever read and written by {@link #paintNaming}.
     */
    private static final int TAG_NAMING_SELECTED = 0x7E010007;

    /**
     * Every size of the panel is a design value, taken from {@link GameTopDesign} — the
     * hand-off's 2376x1080 stage in px (640 wide panel, 52 header, 58 tab, 84 setting row,
     * 64 status row, 72 collapsed bar). There are deliberately no local dp constants any
     * more: a second set of numbers is exactly how the previous build ended up with a
     * 998x648 panel whose chrome ate a third of the height.
     *
     * <p>The only local geometry left is the touch padding of the handle (its own class,
     * unchanged) and the top anchor of the panel, which comes from
     * {@link GameTopDesign#ANCHOR_TOP}.
     */

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

    /**
     * The one panel of this surface. It is built once per attach and is the *same* view in
     * both non-handle stages: collapsed it is a 72px status bar (dot, name, run-state pill,
     * chevron), expanded it is the 640x530 panel with the tab band and the pages. That is the
     * kit's `.np` / `.np.c` pair — one element, two paddings — instead of the two separate
     * views (a hand-drawn capsule and a menu) the previous build had, which is what made the
     * collapse look like a cut rather than a fold.
     */
    private PanelColumn menu;
    /** Host of {@link #menu}: it owns the .75 pre-scale of the panel (see ScaledMenuHost). */
    private ScaledMenuHost menuHost;
    /** Whole header row: it is the collapse / expand toggle (the kit's clickable `.hd`). */
    private LinearLayout headerRow;
    private GameTopUi.StatusDot titleDot;
    private TextView title;
    private TextView statePill;
    /** The chevron: up while expanded, rotated 180deg while collapsed. */
    private GameTopUi.Glyph chevron;
    /** Everything below the header: gone while the panel is collapsed. */
    private LinearLayout body;
    /** Vertical band of the active page; the panel caps it, the ScrollView scrolls it. */
    private android.widget.ScrollView band;
    /** Natural content of both pages, measured with an unbounded height. */
    private PagesHost pages;

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

    /* Card 4: opponent display names. One shared choice, stored by the manager and applied to
     * the Rust modder by the game process; nothing here writes a preference of its own. */
    /** Segmented control order: normal, preset names, custom. */
    private static final OpponentNamingMode[] NAMING_MODES = {
        OpponentNamingMode.NORMAL, OpponentNamingMode.ABC, OpponentNamingMode.CUSTOM
    };
    private LinearLayout namingCard;
    /** The always-visible value row: title, note, the value pill and the disclosure arrow. */
    private LinearLayout namingRow;
    private TextView namingTitle;
    private TextView namingState;
    private TextView namingNote;
    /** Value pill of the row: the current mode, or the three custom names joined. */
    private TextView namingValue;
    /** The "›" of the value row; rotated 90deg while the settings below are open. */
    private GameTopUi.Glyph namingChevron;
    /** The inline settings block below the row, hidden until the row is tapped. */
    private LinearLayout namingSettings;
    private boolean namingOpen;
    /** Three segmented cells, in {@link #NAMING_MODES} order. */
    private final TextView[] namingOptions = new TextView[3];
    /** Container of the segmented cells (the quiet band behind them). */
    private LinearLayout namingModes;
    /** The three label editors; only on screen while the custom mode is selected. */
    private LinearLayout namingEditor;
    private final EditText[] namingInputs = new EditText[3];
    /** Seat labels of the editors (left / top / right), repainted on a language switch. */
    private final TextView[] namingLabels = new TextView[3];
    private TextView namingSave;
    private GameTopUi.Switch namingForceRefreshSwitch;
    /** Last choice the manager reported; the painters read only this field. */
    private OpponentNaming naming = OpponentNaming.decode(null);
    /** Serializes the naming writes. Deliberately not {@link #actionBusy}: a name change is
     * independent of unattended mode, the service and the display mode. */
    private final AtomicBoolean namingBusy = new AtomicBoolean();
    /** Failure copy of card 4, kept in Chinese so a language switch can re-translate it. */
    private String namingNoticeKey = "";
    private boolean namingNoticeFailed;

    /* Status page: key/value rows of the real polled state, the advice line and the actions. */
    private GameTopUi.StatusDot statusDot;
    private TextView statusService;
    private TextView statusMode;
    private TextView statusRematch;
    private TextView statusPanel;
    private TextView statusDetail;
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
    /** The resting lip. Not part of the redesign: its behaviour, drawing and size are as shipped. */
    private GameTopUi.HandleView handleTab;

    /**
     * True while the panel is drawn collapsed (the 72px status bar). The stage is the source
     * of truth — {@code STAGE_MENU} means expanded — and this field only mirrors what the
     * views currently show, so a frame animation can be interrupted and re-synced.
     */
    private boolean panelCollapsed = true;
    /** Frame driver of the fold animation; null while the panel is at rest. */
    private GameTopDesign.Motion motion;
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
        if (motion != null) motion.cancel();
        motion = null;
        menu = null;
        menuHost = null;
        headerRow = null;
        body = null;
        band = null;
        pages = null;
        title = null;
        titleDot = null;
        statePill = null;
        chevron = null;
        panelCollapsed = true;
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
        releaseNamingViews();
        statusDot = null;
        statusMode = statusDetail = statusService = statusRematch = statusPanel = null;
        startStop = null;
        manualRow = null;
        active.clear();
    }

    /**
     * Drops the naming card's view references. Called from both teardown paths (detach and the
     * language-switch rebuild), so the next {@link #buildNamingCard} starts from a clean slate
     * and a closed menu does not pin the views (and their focus listeners) it just dropped.
     */
    private void releaseNamingViews() {
        namingCard = null;
        namingTitle = namingState = namingNote = null;
        namingEditor = null;
        namingSave = null;
        for (int slot = 0; slot < namingOptions.length; slot++) {
            namingOptions[slot] = null;
            namingInputs[slot] = null;
            namingLabels[slot] = null;
        }
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

    /* -------------------------------------------------------------- design px */

    /** Match-parent child of {@code heightDesignPx}, in the panel's own pixel space. */
    private static LinearLayout.LayoutParams fixed(Context context, float heightDesignPx) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            GameTopDesign.px(context, heightDesignPx));
    }

    /** Same, with a design-px gap above it: the row rhythm of the design. */
    private static LinearLayout.LayoutParams fixed(Context context, float heightDesignPx,
                                                   float gapDesignPx) {
        LinearLayout.LayoutParams params = fixed(context, heightDesignPx);
        params.topMargin = GameTopDesign.px(context, gapDesignPx);
        return params;
    }

    /**
     * A row of the design: as tall as its own minimum (84px), which lets the ⓘ explanation of
     * card 2 and the naming settings grow their card by exactly the lines they add. A fixed
     * height would clip them instead, which is why the rows are not measured to a constant.
     */
    private static LinearLayout.LayoutParams rowBox(Context context, float gapDesignPx) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (gapDesignPx > 0f) params.topMargin = GameTopDesign.px(context, gapDesignPx);
        return params;
    }

    /**
     * Opens the panel on the control page (from a tap on the collapsed bar or an explicit
     * call) and asks for a fresh status immediately. This is the only place the expanded
     * stage is entered, so every entry point arms the idle window exactly once.
     */
    private void openMenu() {
        if (stage == STAGE_MENU) return;
        stage = STAGE_MENU;
        tab = TAB_CONTROL;
        resetTransientMenuState();
        restartIdleTimer();
        setCollapsed(false, true);
        applyState();
        schedulePoll(0L);
    }

    /**
     * A freshly opened menu starts with card 2's long explanation folded and the naming
     * settings closed, so the panel keeps its compact height and every row stays on screen.
     */
    private void resetTransientMenuState() {
        rematchDetailOpen = false;
        namingOpen = false;
    }

    private StageRoot build(Activity activity) {
        StageRoot root = new StageRoot(activity);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        // Resting entry: the shipped 84x30dp lip in its 108x44dp touch box. The redesign
        // starts below it — nothing here was touched.
        handleTab = new GameTopUi.HandleView(activity);
        handleTab.setContentDescription(t("展开雀魂助手"));
        handleTab.setStateColor(stateColor());
        handleTab.setOnClickListener(view -> {
            stage = STAGE_CAPSULE;
            restartIdleTimer();
            setCollapsed(true, true);
            applyState();
        });
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        handleParams.topMargin = 0;
        root.addView(handleTab, handleParams);

        menu = buildMenu(activity);
        // The host owns the .75 pre-scale: it measures the panel wider than it is drawn and
        // reports its own box as exactly what the finger sees (see ScaledMenuHost).
        menuHost = new ScaledMenuHost(activity);
        menuHost.addView(menu, new FrameLayout.LayoutParams(
            GameTopDesign.px(activity, GameTopDesign.PANEL_W),
            ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        menuParams.topMargin = GameTopDesign.px(activity, GameTopDesign.ANCHOR_TOP);
        root.addView(menuHost, menuParams);

        // The panel starts folded: the collapsed bar is a normal state of this view, not a
        // separate surface, so nothing has to be animated to show it.
        setCollapsed(true, false);
        return root;
    }

    /**
     * Records menu interactions for the idle timer and never consumes the event.
     * ACTION_DOWN arms a hold so a finger resting on the panel (reading the status) does
     * not trigger the fall-back mid-read.
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

    /**
     * Back to the half-hidden handle, without folding through the bar: the idle window and
     * the outside tap both leave the surface entirely. The timer is not armed again here.
     */
    private void collapse() {
        stopIdleTimer();
        if (stage == STAGE_HANDLE) return;
        stage = STAGE_HANDLE;
        setCollapsed(true, true);
        resolveOutsideClick();
        applyState();
    }

    /**
     * The header of the panel is the fold toggle of the design: expanded -> collapsed bar,
     * collapsed bar -> expanded panel. It never jumps straight to the handle, so a tap never
     * hides the surface the user is reading.
     */
    private void togglePanel() {
        if (stage == STAGE_HANDLE) return;
        if (stage == STAGE_MENU) {
            stage = STAGE_CAPSULE;
            setCollapsed(true, true);
        } else {
            stage = STAGE_MENU;
            setCollapsed(false, true);
        }
        restartIdleTimer();
        applyState();
    }

    /**
     * Folds or unfolds the panel.
     *
     * <p>Geometry is applied immediately and only *visuals* are animated, in one direction:
     *
     * <ul>
     *   <li>the outer padding eases between 16/18 (expanded) and 10/10 (collapsed) over the
     *       design's 280ms expand / 220ms collapse curves — that is the box closing;</li>
     *   <li>the body of the panel (tab band + pages) fades in over 200ms after the design's
     *       80ms delay and slides up 8px, or fades out over 120ms and slides down 8px. It is
     *       made GONE only once it is invisible, so the panel's height never visibly jumps
     *       over a half-faded row;</li>
     *   <li>the chevron rotates between 0 and 180 degrees over the same call.</li>
     * </ul>
     *
     * <p>If the platform cannot animate right now ({@link GameTopDesign#canAnimate}: no
     * window, or the system animator duration scale is 0) the final state is applied in one
     * step. That is also what makes the layout probes deterministic: they build the tree
     * without a window, so they only ever measure the final geometry.
     */
    private void setCollapsed(boolean collapsed, boolean animate) {
        boolean same = panelCollapsed == collapsed;
        panelCollapsed = collapsed;
        if (menu == null) return;

        int padH = GameTopDesign.px(menu.getContext(), collapsed
            ? GameTopDesign.PANEL_PAD_COLLAPSED_H : GameTopDesign.PANEL_PAD_H);
        int padV = GameTopDesign.px(menu.getContext(), collapsed
            ? GameTopDesign.PANEL_PAD_COLLAPSED_V : GameTopDesign.PANEL_PAD_TOP);
        int padBottom = GameTopDesign.px(menu.getContext(), collapsed
            ? GameTopDesign.PANEL_PAD_COLLAPSED_V : GameTopDesign.PANEL_PAD_BOTTOM);
        float rotation = collapsed ? 180f : 0f;

        if (motion != null) motion.cancel();
        if (!animate || !GameTopDesign.canAnimate(menu) || (same && !collapsed)) {
            applyFoldedFrame(collapsed, 1f);
            return;
        }

        final boolean folding = collapsed;
        final int fromTop = menu.getPaddingTop();
        final int fromBottom = menu.getPaddingBottom();
        final int toTop = padV;
        final int toBottom = padBottom;
        final int toLeft = padH;
        final View content = body;
        final long duration = folding ? GameTopDesign.MS_COLLAPSE : GameTopDesign.MS_EXPAND;
        final float[] easing = folding ? GameTopDesign.EASE_COLLAPSE : GameTopDesign.EASE_EXPAND;

        if (!folding && content != null) {
            // The body has to exist while it fades in; it was GONE while collapsed.
            content.setAlpha(0f);
            show(content, true);
        }
        if (!folding && chevron != null) chevron.setRotation(0f);

        motion = new GameTopDesign.Motion(menu);
        motion.start(duration, easing, eased -> {
            int top = Math.round(fromTop + (toTop - fromTop) * eased);
            int bottom = Math.round(fromBottom + (toBottom - fromBottom) * eased);
            menu.setPadding(toLeft, top, toLeft, bottom);
            if (chevron != null) chevron.setRotation(rotation * eased);
            if (content != null) content.setAlpha(folding ? 1f - eased : eased);
            applyRowMotion(folding, eased, duration);
        }, () -> {
            motion = null;
            applyFoldedFrame(folding, 1f);
        });
    }

    /**
     * Applies the resting geometry of one fold state. Called directly when there is nothing
     * to animate, and at the end (or on cancel) of a fold, so an interrupted animation can
     * never leave the panel between two states.
     */
    private void applyFoldedFrame(boolean collapsed, float eased) {
        if (menu == null) return;
        Context context = menu.getContext();
        int padH = GameTopDesign.px(context, collapsed
            ? GameTopDesign.PANEL_PAD_COLLAPSED_H : GameTopDesign.PANEL_PAD_H);
        int padTop = GameTopDesign.px(context, collapsed
            ? GameTopDesign.PANEL_PAD_COLLAPSED_V : GameTopDesign.PANEL_PAD_TOP);
        int padBottom = GameTopDesign.px(context, collapsed
            ? GameTopDesign.PANEL_PAD_COLLAPSED_V : GameTopDesign.PANEL_PAD_BOTTOM);
        menu.setPadding(padH, padTop, padH, padBottom);
        show(body, !collapsed);
        if (body != null) {
            body.setAlpha(1f);
            body.setTranslationY(0f);
        }
        if (chevron != null) chevron.setRotation(collapsed ? 180f : 0f);
        resetRowMotion();
        menu.requestLayout();
    }

    /** Rows fade in one after another (30ms apart) and slide up 8px, exactly as in the kit. */
    private void applyRowMotion(boolean folding, float eased, long duration) {
        if (pageControl == null && pageStatus == null) return;
        LinearLayout page = tab == TAB_STATUS ? pageStatus : pageControl;
        if (page == null) return;
        float elapsed = eased * duration;
        int count = page.getChildCount();
        for (int i = 0; i < count; i++) {
            View row = page.getChildAt(i);
            if (row == null || row.getVisibility() == View.GONE) continue;
            float raw = folding
                ? GameTopDesign.clamp((elapsed) / (float) GameTopDesign.MS_CONTENT_OUT)
                : GameTopDesign.clamp((elapsed - GameTopDesign.MS_CONTENT_IN_DELAY
                    - i * GameTopDesign.MS_ROW_STAGGER) / (float) GameTopDesign.MS_CONTENT_IN);
            row.setAlpha(folding ? 1f - raw : raw);
            row.setTranslationY(folding ? raw * GameTopDesign.px(menu.getContext(),
                GameTopDesign.ROW_TRANSLATE) : (1f - raw) * GameTopDesign.px(menu.getContext(),
                GameTopDesign.ROW_TRANSLATE));
        }
    }

    /** Puts every page row back to its resting look: opaque and in place. */
    private void resetRowMotion() {
        for (LinearLayout page : new LinearLayout[]{pageControl, pageStatus}) {
            if (page == null) continue;
            for (int i = 0; i < page.getChildCount(); i++) {
                View row = page.getChildAt(i);
                if (row == null) continue;
                row.setAlpha(1f);
                row.setTranslationY(0f);
            }
        }
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
            if (menu != null) {
                menu.setReserve(GameTopDesign.windowReserve(menu.getContext(),
                    menu.getResources().getDisplayMetrics().heightPixels));
                menu.requestLayout();
            }
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
     * real window size, so the wrap-content panel and CENTER_HORIZONTAL gravity still work.
     *
     * <p>Outside touches: while the menu is open the root becomes clickable and any tap
     * that is not consumed by the panel reaches it, which closes the menu — the exact
     * action of the outside-tap watcher. The root's own bounds are now the visible panel (the
     * handle is GONE), so the game's hand tiles and buttons outside it are untouched.
     *
     * <p>No gesture of its own: the root deliberately overrides neither
     * {@code onInterceptTouchEvent} nor {@code onTouchEvent}. The two pages are switched only
     * by their own tab shells, the switches only by the right-hand {@link GameTopUi.Switch},
     * and the only touch behaviour left inside the panel is the ScrollView's normal vertical
     * scrolling.
     */
    private final class StageRoot extends FrameLayout {
        StageRoot(Context context) { super(context); }
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

    /**
     * The one deliberate scale of this surface: the panel is laid out at {@code 1 / SCALE} of
     * its visible size and drawn at {@code SCALE}, so Android's own touch transform maps a
     * finger position onto the child exactly. Its own measured box is the *visible* box,
     * which is what the stage root and the outside-tap test use.
     *
     * <p>{@link GameTopDesign#px} already divided the .75 out of every design value, so the
     * panel is 640 design px wide on the finger's side of this host — not 640 * .75.
     */
    private static final class ScaledMenuHost extends FrameLayout {
        float SCALE = GameTopDesign.HOST_SCALE;
        ScaledMenuHost(Context context) { super(context); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            View child = getChildAt(0);
            if (child == null) {
                setMeasuredDimension(0, 0);
                return;
            }
            Context context = getContext();
            int availableWidth = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? 0 : MeasureSpec.getSize(widthSpec);
            int availableHeight = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                ? 0 : MeasureSpec.getSize(heightSpec);
            int designWidth = GameTopDesign.visiblePx(context, GameTopDesign.PANEL_W);
            int visibleWidth = availableWidth > 0
                ? Math.min(designWidth, availableWidth) : designWidth;
            int preWidth = Math.max(1, Math.round(visibleWidth / SCALE));
            // The height is the panel's own: the design asks for a 530px card on a device
            // whose window could hold 900, and a ScrollView band that is handed the whole
            // window would have nothing left to scroll. The parent's box is only a ceiling.
            int maxPreHeight = availableHeight > 0 ? Math.round(availableHeight / SCALE) : 0;
            int childHeightSpec = maxPreHeight > 0
                ? MeasureSpec.makeMeasureSpec(maxPreHeight, MeasureSpec.AT_MOST)
                : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            child.measure(MeasureSpec.makeMeasureSpec(preWidth, MeasureSpec.EXACTLY),
                childHeightSpec);
            setMeasuredDimension(visibleWidth, Math.round(child.getMeasuredHeight() * SCALE));
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            View child = getChildAt(0);
            if (child == null) return;
            child.setPivotX(0f);
            child.setPivotY(0f);
            child.setScaleX(SCALE);
            child.setScaleY(SCALE);
            child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
        }
    }

    /**
     * The panel: a vertical stack of a fixed header, the tab band and the scrolling pages,
     * measured to its own content and clamped to the window reserve.
     *
     * <p>It replaced the old "cap everything to the window reserve" column, which made the
     * panel always as tall as the window allowed (648 visible px) no matter how little the
     * content needed: that is why the previous build's page had empty space under the last
     * row and why the scroll band never reported an overflow. Two measure passes fix it —
     * one for the natural height (band at wrap_content), one for the height the content
     * really needs (band at weight 1, so a long page scrolls instead of growing the panel).
     */
    private static final class PanelColumn extends LinearLayout {
        private final int chromeHeight;
        private final int tabBlock;
        private int reserve;
        private View body;
        private View band;
        private boolean fitted;

        PanelColumn(Context context, int reserve, int chromeHeight, int tabBlock) {
            super(context);
            this.reserve = Math.max(0, reserve);
            this.chromeHeight = Math.max(0, chromeHeight);
            this.tabBlock = Math.max(0, tabBlock);
            setOrientation(VERTICAL);
        }

        /** The scrolling body and its band: both are re-measured by this column. */
        void bindPages(View body, View band) {
            this.body = body;
            this.band = band;
        }

        void setReserve(int value) {
            int next = Math.max(0, value);
            if (next == reserve) return;
            reserve = next;
            requestLayout();
        }

        /**
         * Two passes: first the natural height of the content (both the body and the band at
         * wrap_content), then exactly the height that content needs — capped by
         * {@link #reserve}. In the second pass the band gets an explicit height, so a page
         * that is taller than the panel scrolls inside it instead of growing it ("scroll
         * correctness" is these two numbers agreeing with what the ScrollView sees).
         */
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int size = MeasureSpec.getSize(heightSpec);
            int mode = MeasureSpec.getMode(heightSpec);
            int cap = reserve;
            if (mode == MeasureSpec.EXACTLY) cap = size;
            else if (mode == MeasureSpec.AT_MOST) cap = cap > 0 ? Math.min(cap, size) : size;
            if (cap <= 0) {
                super.onMeasure(widthSpec, heightSpec);
                return;
            }
            fit(false);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            int natural = getMeasuredHeight();
            int wanted = Math.min(natural, cap);
            fit(true);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(wanted, MeasureSpec.EXACTLY));
            setMeasuredDimension(getMeasuredWidth(), wanted);
        }

        /**
         * Pins the body and the band to a definite height (final pass) or lets them wrap (the
         * natural pass). Explicit heights instead of LinearLayout weights: a weighted child
         * inside an AT_MOST parent is handed the whole budget by the platform, which is
         * exactly how the previous build always ended up with a full-height panel.
         */
        private void fit(boolean definite) {
            if (fitted == definite && definite) return;
            fitted = definite;
            int bodyHeight = ViewGroup.LayoutParams.WRAP_CONTENT;
            int bandHeight = ViewGroup.LayoutParams.WRAP_CONTENT;
            if (definite) {
                bodyHeight = Math.max(0, getMeasuredHeight() - getPaddingTop()
                    - getPaddingBottom() - chromeHeight);
                bandHeight = Math.max(0, bodyHeight - tabBlock);
            }
            setHeight(body, bodyHeight);
            setHeight(band, bandHeight);
        }

        private static void setHeight(View view, int height) {
            if (view == null) return;
            ViewGroup.LayoutParams raw = view.getLayoutParams();
            if (!(raw instanceof LinearLayout.LayoutParams)) return;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) raw;
            params.weight = 0f;
            // Mutated in place: the parent already holds this instance, and calling
            // setLayoutParams() from inside a measure pass would request another layout.
            params.height = height;
        }
    }

    /**
     * Holds the two pages and reports the taller of them, whatever height budget the parent
     * hands down. That is what lets the panel's natural measure pass learn the real content
     * height of a page even though the pages sit inside a ScrollView.
     */
    private static final class PagesHost extends FrameLayout {
        PagesHost(Context context) { super(context); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? getSuggestedMinimumWidth() : MeasureSpec.getSize(widthSpec);
            if (width <= 0) width = Math.max(1, getMeasuredWidth());
            int height = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                height = Math.max(height, child.getMeasuredHeight());
            }
            setMeasuredDimension(width, height);
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
            }
        }
    }

    /**
     * The panel: violet glass with a single amber hairline, the clickable header, and the
     * body (tab band + pages).
     *
     * <p>Vertical budget of the expanded control page, in design px of the 1080-short-side
     * stage, which is what the finger measures:
     *
     * <pre>
     *   panel padding        16 + 18        34
     *   header                 52           86
     *   tab band margin-top    12           98
     *   tab band               58          156
     *   tab band margin-bottom 14          170
     *   4 setting rows       4*84 + 3*8    530
     * </pre>
     *
     * So the whole control page is 530px of a 1080px-tall stage: four rows, one screen, no
     * scrolling. The status page is 406px (four 64px key/value rows, the advice line and the
     * primary action) and only scrolls once its conditional hand-off row appears.
     */
    private PanelColumn buildMenu(Activity activity) {
        int reserve = GameTopDesign.windowReserve(activity,
            activity.getResources().getDisplayMetrics().heightPixels);
        PanelColumn panel = new PanelColumn(activity, reserve,
            GameTopDesign.px(activity, GameTopDesign.HEADER_H),
            GameTopDesign.px(activity, GameTopDesign.TABS_H)
                + GameTopDesign.px(activity, GameTopDesign.TABS_MARGIN_TOP)
                + GameTopDesign.px(activity, GameTopDesign.TABS_MARGIN_BOTTOM));
        panel.setBackground(GameTopUi.panel(activity));
        panel.setElevation(GameTopDesign.px(activity, 12));
        int padH = GameTopDesign.px(activity, GameTopDesign.PANEL_PAD_H);
        panel.setPadding(padH, GameTopDesign.px(activity, GameTopDesign.PANEL_PAD_TOP), padH,
            GameTopDesign.px(activity, GameTopDesign.PANEL_PAD_BOTTOM));

        headerRow = buildHeader(activity);
        panel.addView(headerRow, fixed(activity, GameTopDesign.HEADER_H));

        body = vertical(activity);
        panel.addView(body, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout tabs = buildTabs(activity);
        LinearLayout.LayoutParams tabParams = fixed(activity, GameTopDesign.TABS_H,
            GameTopDesign.TABS_MARGIN_TOP);
        tabParams.bottomMargin = GameTopDesign.px(activity, GameTopDesign.TABS_MARGIN_BOTTOM);
        body.addView(tabs, tabParams);

        pages = new PagesHost(activity);
        pageControl = buildControlPage(activity);
        pages.addView(pageControl, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pageStatus = buildStatusPage(activity);
        pages.addView(pageStatus, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        band = new android.widget.ScrollView(activity);
        band.setFillViewport(false);
        band.setVerticalScrollBarEnabled(false);
        band.setClipToPadding(false);
        band.addView(pages, new android.widget.ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(band, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.bindPages(body, band);
        return panel;
    }

    /**
     * Header: the live state dot, the name, the run-state pill and the fold chevron, in one
     * 52px row. The whole row — not just the chevron — is the fold toggle, which is what the
     * hand-off's clickable `.hd` does.
     */
    private LinearLayout buildHeader(Activity activity) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(GameTopDesign.px(activity, GameTopDesign.HEADER_INSET), 0, 0, 0);
        header.setClickable(true);
        header.setFocusable(true);
        header.setContentDescription(t("收起面板"));
        ripple(header, GameTopUi.solid(activity, GameTopDesign.HEADER_H / 2f,
            android.graphics.Color.TRANSPARENT));
        // One listener, created with the row: a language switch rebuilds the tree instead of
        // re-binding, so no listener can stack.
        header.setOnClickListener(view -> togglePanel());

        titleDot = new GameTopUi.StatusDot(activity);
        header.addView(titleDot, new LinearLayout.LayoutParams(
            GameTopDesign.px(activity, GameTopDesign.DOT_HALO),
            GameTopDesign.px(activity, GameTopDesign.DOT_HALO)));

        title = GameTopUi.label(activity, GameTopDesign.TEXT_TITLE, GameTopDesign.INK, true);
        title.setText(t("雀魂助手"));
        title.setLetterSpacing(GameTopDesign.LETTER_SPACING);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.leftMargin = GameTopDesign.px(activity, GameTopDesign.HEADER_GAP);
        header.addView(title, titleParams);

        header.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));

        statePill = GameTopUi.label(activity, GameTopDesign.TEXT_PILL,
            GameTopDesign.AMBER_TEXT, false);
        statePill.setGravity(Gravity.CENTER);
        statePill.setPadding(GameTopDesign.px(activity, GameTopDesign.PILL_PAD_H),
            GameTopDesign.px(activity, GameTopDesign.PILL_PAD_V),
            GameTopDesign.px(activity, GameTopDesign.PILL_PAD_H),
            GameTopDesign.px(activity, GameTopDesign.PILL_PAD_V));
        statePill.setBackground(GameTopUi.statePill(activity));
        header.addView(statePill, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        chevron = new GameTopUi.Glyph(activity, GameTopUi.ICON_CHEVRON_UP,
            GameTopDesign.INK_TAB_OFF, GameTopDesign.px(activity, GameTopDesign.CHEVRON),
            GameTopDesign.pxFloat(activity, 1.8f));
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(
            GameTopDesign.px(activity, GameTopDesign.CHEVRON),
            GameTopDesign.px(activity, GameTopDesign.CHEVRON));
        chevronParams.leftMargin = GameTopDesign.px(activity, GameTopDesign.CHEVRON_GAP);
        header.addView(chevron, chevronParams);
        return header;
    }

    /**
     * The segmented control of the design: one quiet rounded container, two amber-gradient
     * halves. Both halves share the row's half width, so the hit target is the full half
     * rather than only the drawn text, and this row is the ONLY way to switch page: no
     * root-level gesture, no swipe.
     */
    private LinearLayout buildTabs(Activity activity) {
        LinearLayout tabs = new LinearLayout(activity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setBackground(GameTopUi.tabBand(activity));
        int pad = GameTopDesign.px(activity, GameTopDesign.TABS_PAD);
        tabs.setPadding(pad, pad, pad, pad);
        bindTab(activity, controlTab, TAB_CONTROL);
        bindTab(activity, statusTab, TAB_STATUS);
        tabs.addView(controlTab.shell, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        right.leftMargin = GameTopDesign.px(activity, GameTopDesign.TAB_GAP);
        tabs.addView(statusTab.shell, right);
        return tabs;
    }

    /**
     * Builds one tab part and binds its click listener exactly once. Rebuilding the menu
     * on a language switch drops the old shell together with its listener; the new shell
     * gets a fresh one, so nothing can accumulate on a listener list.
     *
     * <p>The shell — not the label, not the root — is the tap target: {@code selectTab}
     * is reached from here and from nowhere else on the touch path, so a swipe, a scroll or
     * a tap on the rows can never change page.
     */
    private void bindTab(Activity activity, TabView view, int id) {
        LinearLayout shell = new LinearLayout(activity);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setGravity(Gravity.CENTER);
        shell.setClickable(true);
        TextView label = GameTopUi.label(activity, GameTopDesign.TEXT_TAB,
            GameTopDesign.INK_TAB_OFF, true);
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

    /**
     * Page 1: the four compact rows of the design — unattended, auto-rematch, recommendation
     * and opponent names — every one of them a single 84px row with two text lines (title,
     * then "state + note") and the switch on the right. Four rows plus the chrome are 530 of
     * the stage's 1080px, so the whole page is on screen without scrolling.
     */
    private LinearLayout buildControlPage(Activity activity) {
        LinearLayout page = vertical(activity);

        unattendedCard = switchCard(activity, t("无人值守"),
            t("助手服务自动分析并出牌"), () -> onToggle(), null);
        unattendedTitle = (TextView) unattendedCard.getTag(TAG_TITLE);
        unattendedState = (TextView) unattendedCard.getTag(TAG_STATE);
        unattendedNote = (TextView) unattendedCard.getTag(TAG_NOTE);
        unattendedSwitch = (GameTopUi.Switch) unattendedCard.getTag(TAG_SWITCH);
        page.addView(unattendedCard, rowBox(activity, 0f));

        // Card 2 is the only card with a ⓘ: it is handed to the card builder so the
        // button really exists in the tree before anyone wires it up or reads it back.
        GameTopUi.Glyph info = new GameTopUi.Glyph(activity, GameTopUi.ICON_INFO,
            GameTopDesign.INK_TAB_OFF, GameTopDesign.px(activity, GameTopDesign.INFO),
            GameTopDesign.pxFloat(activity, 1.6f));
        rematchCard = switchCard(activity, t("自动续打"), t("整场牌局结束后自动继续"),
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
        page.addView(rematchCard, rowBox(activity, GameTopDesign.ROW_GAP));

        // Click-through fixed panel content: recommendation / honour points. Its own
        // switch, live via `panelSet`; it never closes any other feature.
        panelCard = switchCard(activity, t("推荐与番点"), t("穿透固定面板上的推荐与番点"),
            () -> onPanelToggle(), null);
        panelTitle = (TextView) panelCard.getTag(TAG_TITLE);
        panelState = (TextView) panelCard.getTag(TAG_STATE);
        panelNote = (TextView) panelCard.getTag(TAG_NOTE);
        panelSwitch = (GameTopUi.Switch) panelCard.getTag(TAG_SWITCH);
        page.addView(panelCard, rowBox(activity, GameTopDesign.ROW_GAP));

        // Card 4: opponent display names — the design's *value row*. It has no switch: the
        // right-hand side is the current value and a disclosure arrow that opens the
        // settings inline, so the choice keeps three values without a second screen.
        namingCard = buildNamingCard(activity);
        page.addView(namingCard, rowBox(activity, GameTopDesign.ROW_GAP));
        return page;
    }

    /**
     * Opponent-naming row: title + note on the first two lines, the current value in a pill
     * and a "›" on the right. Tapping the row unfolds the settings underneath it — the three
     * mode cells and, for the custom mode, one editor per seat plus a save row — without
     * leaving the page and without adding a second screen to go back from.
     *
     * <p>The card is a pure view of the shared choice: {@link #paintNaming} writes every value
     * from the last status reply, and a tap sends the requested value through the UID-checked
     * provider ({@code namingSet}). The stored value comes back in the reply and is painted the
     * same way a poll is, so the cells can never show a mode the manager did not save.
     *
     * <p>Seat order matches the Rust rewrite: left (kamicha), top (toimen), right (shimocha). A
     * three-player table has no toimen, so the middle editor is simply unused there.
     *
     * <p>Every label is created here and translated in {@link #paintNaming}, so a language
     * switch repaints this card with the rest of the menu instead of leaving old copy behind.
     */
    private LinearLayout buildNamingCard(Activity activity) {
        LinearLayout card = vertical(activity);
        // Published before the tree is finished: the painter below reads this field, and it must
        // never paint a half-built card into a *different* slot.
        namingCard = card;
        card.setMinimumHeight(GameTopDesign.px(activity, GameTopDesign.ROW_H));
        card.setBackground(GameTopUi.row(activity));

        namingRow = new LinearLayout(activity);
        namingRow.setOrientation(LinearLayout.HORIZONTAL);
        namingRow.setGravity(Gravity.CENTER_VERTICAL);
        namingRow.setMinimumHeight(GameTopDesign.px(activity, GameTopDesign.ROW_H));
        namingRow.setPadding(GameTopDesign.px(activity, GameTopDesign.ROW_PAD_START), 0,
            GameTopDesign.px(activity, GameTopDesign.ROW_PAD_END), 0);
        namingRow.setClickable(true);
        namingRow.setContentDescription(t("对手名称"));
        // One listener per row: a tap anywhere on it (value, title or arrow) is the same
        // disclosure. It never writes a preference by itself.
        namingRow.setOnClickListener(view -> {
            namingOpen = !namingOpen;
            applyState();
            restartIdleTimer();
        });

        LinearLayout labels = vertical(activity);
        namingTitle = GameTopUi.label(activity, GameTopDesign.TEXT_ROW_TITLE,
            GameTopDesign.INK, true);
        labels.addView(namingTitle);

        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        namingState = GameTopUi.label(activity, GameTopDesign.TEXT_ROW_DESC,
            GameTopDesign.INK_OFF, true);
        line.addView(namingState);
        namingNote = GameTopUi.label(activity, GameTopDesign.TEXT_ROW_DESC,
            GameTopDesign.INK_SECONDARY, false);
        namingNote.setMaxLines(1);
        namingNote.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        noteParams.leftMargin = GameTopDesign.px(activity, GameTopDesign.ROW_GAP);
        line.addView(namingNote, noteParams);
        labels.addView(line);
        namingRow.addView(labels, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        namingValue = GameTopUi.label(activity, GameTopDesign.TEXT_CHIP, GameTopDesign.INK, false);
        namingValue.setGravity(Gravity.CENTER);
        namingValue.setPadding(GameTopDesign.px(activity, GameTopDesign.CHIP_PAD_H),
            GameTopDesign.px(activity, GameTopDesign.CHIP_PAD_V),
            GameTopDesign.px(activity, GameTopDesign.CHIP_PAD_H),
            GameTopDesign.px(activity, GameTopDesign.CHIP_PAD_V));
        namingValue.setBackground(GameTopUi.chip(activity));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.leftMargin = GameTopDesign.px(activity, GameTopDesign.ROW_INNER_GAP);
        namingRow.addView(namingValue, valueParams);

        namingChevron = new GameTopUi.Glyph(activity, GameTopUi.ICON_CHEVRON_RIGHT,
            GameTopDesign.INK_OFF, GameTopDesign.px(activity, GameTopDesign.CHEVRON),
            GameTopDesign.pxFloat(activity, 1.8f));
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(
            GameTopDesign.px(activity, GameTopDesign.CHEVRON),
            GameTopDesign.px(activity, GameTopDesign.CHEVRON));
        chevronParams.leftMargin = GameTopDesign.px(activity, GameTopDesign.CHEVRON_GAP);
        namingRow.addView(namingChevron, chevronParams);
        card.addView(namingRow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        namingSettings = vertical(activity);
        int padH = GameTopDesign.px(activity, GameTopDesign.ROW_PAD_START);
        namingSettings.setPadding(padH, 0, GameTopDesign.px(activity, GameTopDesign.ROW_PAD_END),
            GameTopDesign.px(activity, GameTopDesign.TABS_MARGIN_BOTTOM));

        namingModes = new LinearLayout(activity);
        namingModes.setOrientation(LinearLayout.HORIZONTAL);
        namingModes.setGravity(Gravity.CENTER_VERTICAL);
        namingModes.setBackground(GameTopUi.tabBand(activity));
        int cellPad = GameTopDesign.px(activity, GameTopDesign.TABS_PAD);
        namingModes.setPadding(cellPad, cellPad, cellPad, cellPad);
        for (int slot = 0; slot < NAMING_MODES.length; slot++) {
            final OpponentNamingMode mode = NAMING_MODES[slot];
            TextView option = GameTopUi.label(activity, GameTopDesign.TEXT_ROW_DESC,
                GameTopDesign.INK_TAB_OFF, true);
            option.setGravity(Gravity.CENTER);
            option.setMinHeight(GameTopDesign.px(activity, GameTopDesign.CELL_H));
            // One listener per cell, created with the cell; a language switch rebuilds the
            // whole page instead of re-binding, so no listener can stack.
            option.setOnClickListener(view -> onNamingMode(mode));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (slot > 0) params.leftMargin = GameTopDesign.px(activity, GameTopDesign.TAB_GAP);
            namingModes.addView(option, params);
            namingOptions[slot] = option;
        }
        namingSettings.addView(namingModes, fixed(activity,
            GameTopDesign.CELL_H + 2 * GameTopDesign.TABS_PAD));

        namingEditor = vertical(activity);
        namingEditor.setPadding(0, GameTopDesign.px(activity, GameTopDesign.ROW_GAP), 0, 0);
        for (int slot = 0; slot < namingInputs.length; slot++) {
            namingEditor.addView(namingEditorRow(activity, slot),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    GameTopDesign.px(activity, GameTopDesign.FIELD_H + GameTopDesign.ROW_GAP)));
        }
        namingSettings.addView(namingEditor);

        namingForceRefreshSwitch = new GameTopUi.Switch(activity);
        LinearLayout refreshRow = vertical(activity);
        refreshRow.setOnClickListener(view -> onNamingForceRefresh());
        namingForceRefreshSwitch.setOn(naming.forceRefreshForJava, false);
        TextView refreshLabel = GameTopUi.label(activity, GameTopDesign.TEXT_ROW_DESC, GameTopDesign.INK, true);
        refreshLabel.setText(t("变更后强制刷新牌桌"));
        refreshRow.addView(refreshLabel);
        refreshRow.addView(namingForceRefreshSwitch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, GameTopDesign.px(activity, GameTopDesign.CELL_H)));
        namingSettings.addView(refreshRow, fixed(activity, GameTopDesign.CELL_H * 2, GameTopDesign.ROW_GAP));
        namingSave = GameTopUi.label(activity, GameTopDesign.TEXT_CHIP, GameTopDesign.INK, true);
        namingSave.setGravity(Gravity.CENTER);
        namingSave.setBackground(GameTopUi.cell(activity, false));
        namingSave.setOnClickListener(view -> onNamingSave());
        namingSettings.addView(namingSave, fixed(activity, GameTopDesign.CELL_H,
            GameTopDesign.ROW_GAP));
        card.addView(namingSettings);

        // Paint the fresh tree immediately: the first status reply arrives a moment later, and
        // an unpainted card would show three empty cells and an empty editor block until then.
        paintNaming();
        return card;
    }

    /**
     * One labelled editor of the custom block. The editor is a plain single-line text field:
     * the panel is a child of the game's own decor view, so the system keyboard works exactly
     * as it does for the game, and the ScrollView above it scrolls the focused field into
     * view. The label is written in {@link #paintNaming}.
     */
    private LinearLayout namingEditorRow(Activity activity, int slot) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = GameTopUi.label(activity, GameTopDesign.TEXT_ROW_DESC,
            GameTopDesign.INK_KEY, false);
        label.setPadding(0, 0, GameTopDesign.px(activity, GameTopDesign.ROW_INNER_GAP), 0);
        row.addView(label, new LinearLayout.LayoutParams(
            GameTopDesign.px(activity, 28 * 2.2f), ViewGroup.LayoutParams.WRAP_CONTENT));
        EditText input = new EditText(activity);
        input.setTextSize(GameTopDesign.sp(activity, GameTopDesign.TEXT_ROW_DESC));
        input.setTextColor(GameTopDesign.INK);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setBackground(GameTopUi.field(activity));
        input.setPadding(GameTopDesign.px(activity, GameTopDesign.CHIP_PAD_H),
            GameTopDesign.px(activity, GameTopDesign.CHIP_PAD_V),
            GameTopDesign.px(activity, GameTopDesign.CHIP_PAD_H),
            GameTopDesign.px(activity, GameTopDesign.CHIP_PAD_V));
        input.setHint(t("留空保留原名"));
        namingLabels[slot] = label;
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inputParams.topMargin = GameTopDesign.px(activity, GameTopDesign.ROW_GAP);
        row.addView(input, inputParams);
        // Committing on focus loss is what makes the panel safe to fold away: the poll stops
        // overwriting a field that still has the finger's focus, and leaving the field saves it.
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) onNamingSave();
        });
        namingInputs[slot] = input;
        return row;
    }

    /**
     * Long explanation of the auto-next-game card, only visible while its ⓘ is open.
     * It is built together with the card (so a language switch rebuilds it too) and is
     * hidden by {@link #paintSwitches()}, which owns the expanded flag. The card grows by
     * this one line while it is open; the row itself keeps its 84px minimum.
     */
    private void addRematchDetail(Activity activity) {
        if (rematchCard == null) return;
        rematchDetail = GameTopUi.label(activity, GameTopDesign.TEXT_ROW_DESC,
            GameTopDesign.INK_SECONDARY, false);
        rematchDetail.setText(t("只调用游戏的“下一局”原按钮，不伪造请求；未开启时不排队"));
        rematchDetail.setPadding(GameTopDesign.px(activity, GameTopDesign.ROW_PAD_START),
            GameTopDesign.px(activity, GameTopDesign.ROW_GAP),
            GameTopDesign.px(activity, GameTopDesign.ROW_PAD_END),
            GameTopDesign.px(activity, GameTopDesign.ROW_GAP));
        InGameLocalizer.layout(rematchDetail, 3);
        rematchDetail.setVisibility(View.GONE);
        rematchCard.setTag(TAG_DETAIL, rematchDetail);
        rematchCard.addView(rematchDetail);
    }

    /**
     * One setting row of the design: the title on the first line, the state word and the note
     * inline on the second, arranged left of the switch. The switch is the only tap target on
     * the row (the row itself carries no listener), so a tap on the copy can never flip a
     * feature by accident — the wiring the gesture probe pins.
     *
     * <p>The row is {@code ROW_H} design px tall via its minimum, not a fixed height, so the
     * ⓘ explanation of card 2 can grow it by one line while it is open.
     */
    private LinearLayout switchCard(Activity activity, String titleText,
                                    String subtitle, Runnable action, GameTopUi.Glyph info) {
        LinearLayout card = vertical(activity);
        // The switch keeps 10px of its own invisible touch padding on each side, so the row
        // only has to reserve the design's 20px end padding minus that padding.
        card.setPadding(GameTopDesign.px(activity, GameTopDesign.ROW_PAD_START), 0,
            GameTopDesign.px(activity, GameTopDesign.ROW_PAD_END
                - GameTopDesign.TOGGLE_TOUCH_PAD), 0);
        card.setMinimumHeight(GameTopDesign.px(activity, GameTopDesign.ROW_H));
        card.setContentDescription(titleText);
        // Card labels never toggle a setting; only the right-hand switch does.
        ripple(card, GameTopUi.row(activity));

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels = vertical(activity);
        LinearLayout titleLine = new LinearLayout(activity);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = GameTopUi.label(activity, GameTopDesign.TEXT_ROW_TITLE,
            GameTopDesign.INK, true);
        titleView.setText(titleText);
        titleLine.addView(titleView);
        if (info != null) {
            // The ⓘ sits inside the row but is not part of the row's tap target: it only
            // opens the explanation, it never flips the switch.
            info.setContentDescription(titleText + "说明");
            LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                GameTopDesign.px(activity, GameTopDesign.INFO),
                GameTopDesign.px(activity, GameTopDesign.INFO));
            infoParams.leftMargin = GameTopDesign.px(activity, GameTopDesign.ROW_INNER_GAP);
            titleLine.addView(info, infoParams);
        }
        labels.addView(titleLine);

        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView stateView = GameTopUi.label(activity, GameTopDesign.TEXT_ROW_DESC,
            GameTopDesign.INK_OFF, true);
        stateView.setText(subtitle);
        line.addView(stateView);
        // The note shares the second line with the state word, exactly like the design's
        // ".d" line, and is capped at one row with an ellipsis: it answers "what does a tap
        // do", and a failure notice is what the card has to say right now.
        TextView note = GameTopUi.label(activity, GameTopDesign.TEXT_ROW_DESC,
            GameTopDesign.INK_SECONDARY, false);
        note.setMaxLines(1);
        note.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        noteParams.leftMargin = GameTopDesign.px(activity, GameTopDesign.ROW_GAP);
        line.addView(note, noteParams);
        labels.addView(line);
        top.addView(labels, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        GameTopUi.Switch toggle = new GameTopUi.Switch(activity);
        toggle.setContentDescription(titleText + "开关");
        toggle.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
            GameTopDesign.px(activity, GameTopDesign.TOGGLE_W
                + 2 * GameTopDesign.TOGGLE_TOUCH_PAD),
            GameTopDesign.px(activity, GameTopDesign.ROW_H));
        switchParams.leftMargin = GameTopDesign.px(activity, Math.max(0,
            GameTopDesign.ROW_INNER_GAP - GameTopDesign.TOGGLE_TOUCH_PAD));
        top.addView(toggle, switchParams);
        card.addView(top, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        InGameLocalizer.layout(titleView, 1);
        InGameLocalizer.layout(stateView, 1);
        InGameLocalizer.layout(note, 1);

        // Filled by buildControlPage right after this call; no resource ids involved.
        // `info` is null for the cards that have no ⓘ; the tag is only written when the
        // button exists, and buildControlPage never touches a slot it did not get.
        card.setTag(TAG_TITLE, titleView);
        card.setTag(TAG_STATE, stateView);
        card.setTag(TAG_NOTE, note);
        card.setTag(TAG_SWITCH, toggle);
        if (info != null) card.setTag(TAG_INFO, info);
        return card;
    }

    /** One key/value row of the status page, with its live value view. */
    private static final class Kv {
        LinearLayout root;
        TextView value;
    }

    /**
     * Builds one 64px key/value row: the key at 70% on the left, the value in the design's
     * strong 26px style on the right. {@code withDot} adds the live state dot to the row that
     * reports whether the service is really running.
     */
    private Kv kvRow(Activity activity, String keyZh, boolean withDot) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(GameTopDesign.px(activity, GameTopDesign.ROW_H_STATUS));
        row.setPadding(GameTopDesign.px(activity, GameTopDesign.ROW_PAD_START), 0,
            GameTopDesign.px(activity, GameTopDesign.ROW_PAD_END), 0);
        row.setBackground(GameTopUi.row(activity));

        TextView key = GameTopUi.label(activity, GameTopDesign.TEXT_STATUS_KEY,
            GameTopDesign.INK_KEY, false);
        key.setText(t(keyZh));
        InGameLocalizer.layout(key, 1);
        row.addView(key, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (withDot) {
            statusDot = new GameTopUi.StatusDot(activity);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                GameTopDesign.px(activity, GameTopDesign.DOT_HALO),
                GameTopDesign.px(activity, GameTopDesign.DOT_HALO));
            dotParams.rightMargin = GameTopDesign.px(activity, GameTopDesign.ROW_GAP);
            row.addView(statusDot, dotParams);
        }

        TextView value = GameTopUi.label(activity, GameTopDesign.TEXT_STATUS_VALUE,
            GameTopDesign.INK, true);
        value.setMaxLines(1);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(value, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Kv kv = new Kv();
        kv.root = row;
        kv.value = value;
        return kv;
    }

    /**
     * Page 2: the real polled state as four key/value rows — service, unattended mode,
     * auto-rematch, recommendation panel — the advice line, and the two actions.
     *
     * <p>Nothing here is placeholder copy: every value comes from the last status reply, and a
     * row always reports what the control actually knows (for example "未启动", never a dash
     * or a fake number). The design's own status page had five placeholder rows (uptime,
     * current round, finished games, latency); those numbers do not exist in this module, so
     * they are not invented here — the rows are the four switches the panel really owns.
     */
    private LinearLayout buildStatusPage(Activity activity) {
        LinearLayout page = vertical(activity);

        Kv service = kvRow(activity, "服务状态", true);
        statusService = service.value;
        page.addView(service.root, fixed(activity, GameTopDesign.ROW_H_STATUS));

        Kv mode = kvRow(activity, "无人值守", false);
        statusMode = mode.value;
        page.addView(mode.root, fixed(activity, GameTopDesign.ROW_H_STATUS, GameTopDesign.ROW_GAP));

        Kv rematchRow = kvRow(activity, "自动下一局", false);
        statusRematch = rematchRow.value;
        page.addView(rematchRow.root,
            fixed(activity, GameTopDesign.ROW_H_STATUS, GameTopDesign.ROW_GAP));

        Kv panelRow = kvRow(activity, "推荐与番点", false);
        statusPanel = panelRow.value;
        page.addView(panelRow.root,
            fixed(activity, GameTopDesign.ROW_H_STATUS, GameTopDesign.ROW_GAP));

        // The advice / notice line: what a tap does, or what just failed. It is a text line,
        // not a row, so it never pretends to be a key/value pair.
        statusDetail = statusLine(activity);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = GameTopDesign.px(activity, GameTopDesign.ROW_GAP);
        page.addView(statusDetail, detailParams);

        // The same start/stop action the first card of the control page offers, kept here
        // because this page is where a user looks when the assistant is not doing anything.
        startStop = GameTopUi.label(activity, GameTopDesign.TEXT_ROW_TITLE,
            GameTopDesign.ON_AMBER, true);
        startStop.setGravity(Gravity.CENTER);
        startStop.setMinimumHeight(GameTopDesign.px(activity, GameTopDesign.ROW_H));
        ripple(startStop, GameTopUi.primary(activity));
        startStop.setOnClickListener(view -> onToggle());
        InGameLocalizer.layout(startStop, 2, InGameLocalizer.Align.CENTER);
        page.addView(startStop, fixed(activity, GameTopDesign.ROW_H, GameTopDesign.ROW_GAP));

        manualRow = actionRow(activity, t("打开管理应用"), GameTopDesign.AMBER_TEXT);
        manualRow.setOnClickListener(view -> openManager());
        page.addView(manualRow, fixed(activity, GameTopDesign.ROW_H, GameTopDesign.ROW_GAP));
        return page;
    }

    /** The advice line of the status page: one wrapping label, painted by the poll. */
    private TextView statusLine(Activity activity) {
        TextView line = GameTopUi.label(activity, GameTopDesign.TEXT_ROW_DESC,
            GameTopDesign.INK_SECONDARY, false);
        line.setPadding(GameTopDesign.px(activity, GameTopDesign.ROW_PAD_START), 0,
            GameTopDesign.px(activity, GameTopDesign.ROW_PAD_END), 0);
        return line;
    }

    /** One secondary action row of the status page (the hand-off to the manager app). */
    private LinearLayout actionRow(Activity activity, String label, int color) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setMinimumHeight(GameTopDesign.px(activity, GameTopDesign.ROW_H));
        row.setPadding(GameTopDesign.px(activity, GameTopDesign.ROW_PAD_START), 0,
            GameTopDesign.px(activity, GameTopDesign.ROW_PAD_END), 0);
        TextView text = GameTopUi.label(activity, GameTopDesign.TEXT_ROW_TITLE, color, true);
        text.setText(label);
        text.setGravity(Gravity.CENTER);
        InGameLocalizer.layout(text, 2, InGameLocalizer.Align.CENTER);
        row.addView(text, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ripple(row, GameTopUi.row(activity));
        return row;
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
            // (handle and panel) and the hint layer, and keep the poll quiet.
            stopIdleTimer();
            stage = STAGE_HANDLE;
            show(handleTab, false);
            show(menuHost, false);
            resolveOutsideClick();
            return;
        }
        // The panel is one view in two states: the collapsed status bar (72px) or the
        // expanded card. The stage decides which, and setCollapsed() only touches the
        // geometry when the state or the running animation says so — a poll that arrives
        // every second never restarts a fold.
        boolean menuShown = stage != STAGE_HANDLE;
        show(menuHost, menuShown);
        show(handleTab, stage == STAGE_HANDLE);
        if (menuShown && motion == null && panelCollapsed != (stage != STAGE_MENU)) {
            setCollapsed(stage != STAGE_MENU, false);
        }
        // The root only accepts an outside tap while the panel is expanded (see StageRoot).
        resolveOutsideClick();
        // The handle carries its own live dot; paint it even when hidden so the next reveal
        // is already correct instead of flashing the previous colour.
        int state = stateColor();
        if (handleTab != null) handleTab.setStateColor(state);
        if (menuShown) {
            paintHeader();
            paintTab();
            paintSwitches();
            paintNaming();
            paintStatusPage();
            paintActions();
        }
        // Polling repaints arrive here every few seconds and deliberately never touch
        // the idle window: only a real interaction (restartIdleTimer) re-arms it, and the
        // panel stages are only entered through those interaction paths.
    }

    /** The root is clickable exactly while the expanded panel is on screen. */
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
        fadeActivePage();
    }

    /**
     * The design's tab switch: the new page fades in over 160ms and rises 6px. Nothing else
     * moves — the panel keeps its height and the scroll offset of each page is untouched.
     */
    private void fadeActivePage() {
        LinearLayout page = tab == TAB_STATUS ? pageStatus : pageControl;
        if (page == null) return;
        if (!GameTopDesign.canAnimate(page)) {
            page.setAlpha(1f);
            page.setTranslationY(0f);
            return;
        }
        page.setAlpha(0f);
        page.setTranslationY(GameTopDesign.px(page.getContext(), 6));
        GameTopDesign.Motion tabMotion = new GameTopDesign.Motion(page);
        tabMotion.start(GameTopDesign.MS_TAB_CONTENT, GameTopDesign.EASE_STANDARD, eased -> {
            page.setAlpha(eased);
            page.setTranslationY((1f - eased) * GameTopDesign.px(page.getContext(), 6));
        }, () -> {
            page.setAlpha(1f);
            page.setTranslationY(0f);
        });
    }

    /** Amber gradient for the active tab, transparent for the other one. */
    private void paintTab() {
        boolean control = tab == TAB_CONTROL;
        styleTab(controlTab, control);
        styleTab(statusTab, !control);
        show(pageControl, control);
        show(pageStatus, !control);
    }

    private static void styleTab(TabView view, boolean selected) {
        if (!view.bound()) return;
        Context context = view.shell.getContext();
        view.shell.setBackground(selected ? GameTopUi.tabActive(context)
            : GameTopUi.tabIdle(context));
        int color = selected ? GameTopDesign.ON_AMBER : GameTopDesign.INK_TAB_OFF;
        if (view.label.getCurrentTextColor() != color) view.label.setTextColor(color);
    }

    /** Live state colour of the *handle*, which keeps the shipped palette. */
    private int stateColor() {
        if (starting) return GameTopUi.STATE_AMBER;
        if (enabled) return GameTopUi.STATE_GREEN;
        if (available) return GameTopUi.STATE_AMBER;
        if (demo) return GameTopUi.STATE_AMBER;
        if (unreachable) return GameTopUi.DANGER;
        return GameTopUi.STATE_AMBER;
    }

    /**
     * The same state, in the design's own palette (mint for running, amber for pending, the
     * shared red for failures). The handle keeps {@link #stateColor()}: it is the one surface
     * the redesign does not touch, so its dot stays the green it has always been.
     */
    private int panelStateColor() {
        if (starting) return GameTopDesign.AMBER_TEXT;
        if (enabled) return GameTopDesign.MINT;
        if (available) return GameTopDesign.AMBER_TEXT;
        if (demo) return GameTopDesign.AMBER_TEXT;
        if (unreachable) return GameTopDesign.DANGER;
        return GameTopDesign.INK_OFF;
    }

    /**
     * True when the assistant is really running: the manager says unattended mode is on,
     * or its service answered but the mode is off. A start sequence is not "running" yet —
     * the pill says "starting" for that.
     */
    private boolean runningNow() {
        // A running service that still reports a self-test is not "running" for the user:
        // the same caveat that keeps the unattended card from switching on.
        return enabled || (available && !demo && !starting);
    }

    /**
     * Header: the live state dot, the run-state pill and the fold chevron. The pill is the
     * only run-state copy of the surface, so the collapsed bar and the expanded panel say
     * exactly the same thing about the same state.
     */
    private void paintHeader() {
        if (titleDot != null) titleDot.setColor(panelStateColor());
        String pill = statePillText();
        setText(statePill, pill);
        if (statePill != null) {
            boolean live = !starting && enabled;
            statePill.setTextColor(live ? GameTopDesign.AMBER_TEXT : GameTopDesign.INK_TAB_OFF);
            statePill.setBackground(GameTopUi.statePill(statePill.getContext()));
        }
        InGameLocalizer.layout(statePill, 1, InGameLocalizer.Align.CENTER);
        if (chevron != null) {
            chevron.setRotation(panelCollapsed ? 180f : 0f);
        }
        setDescription(headerRow, t(panelCollapsed ? "展开雀魂助手" : "收起面板"));
    }

    /** The short run-state word of the header pill; never truncated, the pill grows. */
    private String statePillText() {
        if (starting) return t("启动中");
        if (enabled) return t("无人值守中");
        if (available && !demo) return t("待机");
        if (demo) return t("自检中");
        if (unreachable) return t("服务未连接");
        return t("未开启");
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
        if (unattendedState != null) {
            unattendedState.setTextColor(starting || enabled ? runningNow()
                ? GameTopDesign.MINT : GameTopDesign.AMBER_TEXT : GameTopDesign.INK_OFF);
        }
        int unattendedTone = noticeFailed && noticeRefersToUnattended()
            ? GameTopDesign.DANGER : GameTopDesign.INK_SECONDARY;
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
            rematchState.setTextColor(rematch ? GameTopDesign.AMBER_TEXT : GameTopDesign.INK_OFF);
        }
        setText(rematchNote, rematchNoteText());
        if (rematchNote != null) {
            rematchNote.setTextColor(panelNoticeFailed()
                ? GameTopDesign.DANGER : GameTopDesign.INK_SECONDARY);
        }
        if (rematchInfo != null) {
            rematchInfo.setColor(rematchDetailOpen ? GameTopDesign.AMBER_TEXT
                : GameTopDesign.INK_TAB_OFF);
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
            panelState.setTextColor(recommendationPanel ? GameTopDesign.AMBER_TEXT
                : GameTopDesign.INK_OFF);
        }
        setText(panelNote, panelNoteText());
        if (panelNote != null) {
            panelNote.setTextColor(panelNoticeFailed() ? GameTopDesign.DANGER
                : GameTopDesign.INK_SECONDARY);
        }
    }

    /**
     * Live values of card 4: the value pill, the state word, the three cells, the custom
     * editors and the note.
     *
     * Called from [applyState] on every pass, so a new reply, a language switch and the first
     * paint after a rebuild all land here and no other method has to remember this card.
     *
     * <p>A field that still has the focus is never rewritten: the poll arrives every second
     * while the user types, and painting the stored value over a half-typed label would delete
     * it. Leaving the field saves it (the focus listener), and the reply then repaints it.
     */
    private void paintNaming() {
        if (namingCard == null) return;
        setText(namingTitle, t("对手名称"));
        InGameLocalizer.layout(namingTitle, 1);
        setText(namingState, namingStateText());
        if (namingForceRefreshSwitch != null) namingForceRefreshSwitch.setOn(naming.forceRefreshForJava, false);
        if (namingState != null) {
            namingState.setTextColor(namingNoticeFailed ? GameTopDesign.DANGER
                : namingBusy.get() ? GameTopDesign.INK_OFF : GameTopDesign.AMBER_TEXT);
            InGameLocalizer.layout(namingState, 1);
        }
        setText(namingValue, namingValueText());
        if (namingValue != null) InGameLocalizer.layout(namingValue, 1);
        if (namingChevron != null) namingChevron.setRotation(namingOpen ? 90f : 0f);
        for (int slot = 0; slot < namingOptions.length; slot++) {
            TextView option = namingOptions[slot];
            if (option == null) continue;
            boolean selected = NAMING_MODES[slot] == naming.getMode();
            String label = namingOptionText(NAMING_MODES[slot]);
            setText(option, label);
            option.setTextColor(selected ? GameTopDesign.ON_AMBER : GameTopDesign.INK_TAB_OFF);
            // A fresh RippleDrawable on every pass (this runs once per poll) is pure garbage;
            // the cell only has to change when the selection does.
            Object painted = option.getTag(TAG_NAMING_SELECTED);
            if (!(painted instanceof Boolean) || ((Boolean) painted) != selected) {
                GameTopUi.pressable(option, GameTopUi.cell(option.getContext(), selected));
                option.setTag(TAG_NAMING_SELECTED, selected);
            }
            setDescription(option, label);
            InGameLocalizer.layout(option, 1, InGameLocalizer.Align.CENTER);
        }
        boolean custom = naming.getMode() == OpponentNamingMode.CUSTOM;
        show(namingEditor, custom);
        show(namingSave, custom);
        setText(namingSave, t("保存名称"));
        if (namingSave != null) {
            namingSave.setTextColor(namingBusy.get() ? GameTopDesign.INK_OFF : GameTopDesign.INK);
            setDescription(namingSave, t("保存名称"));
            InGameLocalizer.layout(namingSave, 1, InGameLocalizer.Align.CENTER);
        }
        show(namingSettings, namingOpen);
        if (namingRow != null) {
            // The row keeps its 84px minimum and its whole width is the disclosure target:
            // a tap anywhere except the settings below opens or closes them.
            setDescription(namingRow, t("对手名称") + " · " + t(namingOpen ? "收起" : "设置"));
        }
        if (custom && namingOpen) {
            for (int slot = 0; slot < namingInputs.length; slot++) {
                TextView label = namingLabels[slot];
                setText(label, namingSlotText(slot));
                if (label != null) InGameLocalizer.layout(label, 1);
                EditText input = namingInputs[slot];
                if (input == null) continue;
                if (input.hasFocus()) continue;
                String stored = naming.nameAt(slot);
                if (!stored.contentEquals(input.getText())) input.setText(stored);
            }
        }
        setText(namingNote, namingNoteText());
        if (namingNote != null) {
            namingNote.setTextColor(namingNoticeFailed ? GameTopDesign.DANGER
                : GameTopDesign.INK_SECONDARY);
            InGameLocalizer.layout(namingNote, 1);
        }
        setDescription(namingCard, t("对手名称"));
    }

    /** Short mode word in the card's state line. */
    private String namingStateText() {
        if (naming.getMode() == OpponentNamingMode.ABC) return t("预设昵称");
        if (naming.getMode() == OpponentNamingMode.CUSTOM) return t("自定义");
        return t("正常显示");
    }

    /**
     * The value pill of the naming row: the current choice, or the three names themselves
     * while the custom mode is active — that is the design's "A·B·C" pill, and it is the only
     * place the three labels are visible while the settings are folded.
     */
    private String namingValueText() {
        if (naming.getMode() == OpponentNamingMode.ABC) return t("预设昵称");
        if (naming.getMode() != OpponentNamingMode.CUSTOM) return t("正常");
        StringBuilder joined = new StringBuilder();
        for (int slot = 0; slot < 3; slot++) {
            String name = naming.nameAt(slot);
            if (name == null || name.isEmpty()) continue;
            if (joined.length() > 0) joined.append('·');
            joined.append(name);
        }
        return joined.length() == 0 ? t("自定义") : clip(joined.toString(), 12);
    }

    /** Copy of one segmented cell. */
    private String namingOptionText(OpponentNamingMode mode) {
        if (mode == OpponentNamingMode.ABC) return t("预设昵称");
        if (mode == OpponentNamingMode.CUSTOM) return t("自定义");
        return t("正常");
    }

    /** Seat name of one editor, in the same order the Rust rewrite uses. */
    private String namingSlotText(int slot) {
        if (slot == 1) return t("对家");
        if (slot == 2) return t("右家");
        return t("左家");
    }

    /**
     * Note line of card 4: a failure or confirmation first, otherwise what the selected mode
     * does. The copy promises the current table because the in-game Lua layer
     * (MajsoulMaxNaming.lua) renames the seats of the table on screen and redraws it once the
     * stored choice changes; the `authGame` rewrite only covers the next one.
     */
    private String namingNoteText() {
        if (!namingNoticeKey.isEmpty()) return t(namingNoticeKey);
        if (naming.getMode() == OpponentNamingMode.ABC) return t("三位对手显示预设昵称 · 当前牌桌立即生效");
        if (naming.getMode() == OpponentNamingMode.CUSTOM) return t("留空的一席保留原名 · 当前牌桌立即生效");
        return t("保留对手原名 · 当前牌桌立即生效");
    }

    /** True when the last failed notice came from the unattended path. */
    private boolean noticeRefersToUnattended() {
        return noticeKey.contains("无人值守") || noticeKey.contains("启动")
            || noticeKey.contains("管理应用");
    }

    /** True when the last failed notice came from the panel (recommendation) path. */
    private boolean panelNoticeFailed() {
        return noticeFailed && noticeKey.contains("面板");
    }

    /**
     * Page 2 values. Every row is a fact from the last status reply; the advice line under
     * them carries the notice, the overlay error or what a tap will do.
     */
    private void paintStatusPage() {
        if (statusService == null) return;
        if (statusDot != null) {
            statusDot.setColor(panelStateColor());
            show(statusDot, runningNow() || starting);
        }
        setText(statusService, serviceValueText());
        if (statusService != null) {
            statusService.setTextColor(runningNow() ? GameTopDesign.MINT : GameTopDesign.INK);
        }
        setText(statusMode, unattendedStateText());
        if (statusMode != null) {
            statusMode.setTextColor(enabled ? GameTopDesign.AMBER_TEXT : GameTopDesign.INK_OFF);
        }
        setText(statusRematch, rematchValueText());
        if (statusRematch != null) {
            statusRematch.setTextColor(rematch ? GameTopDesign.AMBER_TEXT : GameTopDesign.INK_OFF);
        }
        setText(statusPanel, recommendationPanel ? t("已开启") : t("已关闭"));
        if (statusPanel != null) {
            statusPanel.setTextColor(recommendationPanel ? GameTopDesign.AMBER_TEXT
                : GameTopDesign.INK_OFF);
        }
        setText(statusDetail, detailText());
        if (statusDetail != null) {
            statusDetail.setTextColor(noticeFailed ? GameTopDesign.DANGER
                : GameTopDesign.INK_SECONDARY);
        }
    }

    private void paintActions() {
        if (startStop != null) {
            setText(startStop, toggleText());
            startStop.setAlpha(starting ? .55f : 1f);
            startStop.setTextColor(GameTopDesign.ON_AMBER);
        }
        String manualText = manualText();
        if (manualRow != null) {
            show(manualRow, manualText != null);
            manualRow.setContentDescription(manualText == null ? t("打开管理应用") : manualText);
            if (manualRow.getChildCount() > 0 && manualRow.getChildAt(0) instanceof TextView) {
                TextView label = (TextView) manualRow.getChildAt(0);
                setText(label, manualText == null ? t("打开管理应用") : manualText);
                label.setTextColor(noticeFailed ? GameTopDesign.DANGER
                    : GameTopDesign.AMBER_TEXT);
            }
        }
        if (headerRow != null) setDescription(headerRow,
            t(panelCollapsed ? "展开雀魂助手" : "收起面板"));
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

    /**
     * Value of the status page's service row. The full phrases of the previous build
     * ("前台服务 · 运行中") are kept verbatim as the row value, so the copy stays translated in
     * every language while the key is the row's own label.
     */
    private String serviceValueText() {
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
     * Value of the status page's auto-rematch row: the state word and, in brackets, the same
     * caveat the switch card's note line shows. The row's key is "自动下一局", so the value no
     * longer repeats it.
     */
    private String rematchValueText() {
        String state = rematch ? t("开") : t("关");
        String note;
        if (demo && rematch) note = t("自检中不生效");
        else if (rematch && !rematchActive) note = t("待条件满足");
        else if (rematch) note = t("仅按下一局原按钮");
        else note = t("点按开启");
        return state + "（" + note + "）";
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
        if (motion != null) motion.cancel();
        motion = null;
        menu = null;
        menuHost = null;
        headerRow = null;
        body = null;
        band = null;
        pages = null;
        title = null;
        titleDot = null;
        statePill = null;
        chevron = null;
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
        releaseNamingViews();
        statusDot = null;
        statusMode = statusDetail = statusService = statusRematch = statusPanel = null;
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
        if (headerRow != null) {
            setDescription(headerRow, t(panelCollapsed ? "展开雀魂助手" : "收起面板"));
        }
        if (chevron != null) chevron.setColor(GameTopDesign.INK_TAB_OFF);
        if (namingChevron != null) namingChevron.setColor(GameTopDesign.INK_OFF);
        if (titleDot != null) titleDot.setColor(panelStateColor());

        // The hand-off row only names its static label while it has no live label of its
        // own; [paintActions] owns it otherwise.
        if (manualRow == null || manualText() == null) paintActionRow(manualRow, "打开管理应用");

        setText(rematchDetail, t("只调用游戏的“下一局”原按钮，不伪造请求；未开启时不排队"));
        InGameLocalizer.layout(rematchDetail, 4);

        if (handleTab != null) setDescription(handleTab, t("展开雀魂助手"));

        InGameLocalizer.layout(unattendedNote, 1);
        InGameLocalizer.layout(rematchNote, 1);
        InGameLocalizer.layout(panelNote, 1);
        InGameLocalizer.layout(namingNote, 1);
        InGameLocalizer.layout(statusMode, 1);
        InGameLocalizer.layout(statusService, 1);
        InGameLocalizer.layout(statusRematch, 1);
        InGameLocalizer.layout(statusPanel, 1);
        InGameLocalizer.layout(statusDetail, 3);
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

    /**
     * Runtime switch of the opponent-naming mode.
     *
     * It sends only the requested mode: the three labels belong to the stored choice and are
     * left untouched, so switching back to "custom" restores exactly the labels the user typed
     * in either surface. The reply is applied like a poll, so a write the manager did not store
     * is reported instead of leaving a cell selected that the modder will never use.
     */
    private void onNamingForceRefresh() {
        if (namingBusy.get() || namingForceRefreshSwitch == null) return;
        boolean next = !naming.forceRefreshForJava;
        OpponentNamingSettings.INSTANCE.save(active.get(), new OpponentNaming(naming.getMode(), naming.namesForJava, next));
        naming = OpponentNaming.decode(OpponentNamingSettings.INSTANCE.read(active.get()).toJson().toString());
        paintNaming();
    }

    private void onNamingMode(OpponentNamingMode mode) {
        if (namingBusy.get() || mode == naming.getMode()) return;
        namingBusy.set(true);
        namingNoticeKey = "";
        namingNoticeFailed = false;
        applyState();
        restartIdleTimer();
        Bundle args = new Bundle();
        args.putString("mode", mode.getWire());
        call("namingSet", args, REQUEST_TIMEOUT_MS, reply -> {
            namingBusy.set(false);
            if (reply == null) {
                namingNoticeFailed = true;
                namingNoticeKey = "管理应用无响应 · 名称模式未保存";
            } else {
                applyReply(reply);
                namingNoticeFailed = naming.getMode() != mode;
                namingNoticeKey = namingNoticeFailed ? "名称模式未保存" : "";
            }
            applyState();
            schedulePoll(POLL_MENU_MS);
        });
    }

    /**
     * Saves the three custom labels (focus loss, or an explicit tap on the save row).
     *
     * The write also selects the custom mode: the editors only exist while it is selected, and
     * storing labels that are then never used would be a lie to the user. An unchanged set is
     * skipped, so simply folding the panel never issues a write.
     */
    private void onNamingSave() {
        if (namingBusy.get() || namingCard == null) return;
        String[] names = new String[namingInputs.length];
        boolean unchanged = naming.getMode() == OpponentNamingMode.CUSTOM;
        for (int slot = 0; slot < names.length; slot++) {
            EditText input = namingInputs[slot];
            names[slot] = input == null ? "" : String.valueOf(input.getText());
            if (!names[slot].equals(naming.nameAt(slot))) unchanged = false;
        }
        if (unchanged) return;
        namingBusy.set(true);
        namingNoticeKey = "";
        namingNoticeFailed = false;
        applyState();
        Bundle args = new Bundle();
        args.putString("mode", OpponentNamingMode.CUSTOM.getWire());
        args.putStringArray("names", names);
        call("namingSet", args, REQUEST_TIMEOUT_MS, reply -> {
            namingBusy.set(false);
            if (reply == null) {
                namingNoticeFailed = true;
                namingNoticeKey = "管理应用无响应 · 自定义名称未保存";
            } else {
                applyReply(reply);
                namingNoticeKey = "自定义名称已保存 · 当前牌桌立即生效";
                namingNoticeFailed = false;
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
        // Shared opponent-naming choice. A reply without the key (an older manager, a partial
        // reply) keeps the current value, exactly like `rematch` above, so a provider hiccup
        // can never flip the card back to "normal" behind the user's back.
        String namingWire = reply.getString("naming");
        if (namingWire != null) naming = OpponentNaming.decode(namingWire);
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
