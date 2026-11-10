package com.yefeng.majmax.hookprobe;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
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
 * Surface behaviour: the control is a fixed, top-centre tab, never a draggable
 * floating window. It starts half-hidden at the top edge (a transparent rounded pill
 * with only ~8dp on screen), the first tap shows the full 雀魂助手 capsule, the second
 * tap opens the menu below the top edge, and the collapse row (or ~6s without use)
 * folds it back to the handle. Nothing in the provider protocol is involved in any of
 * that: the stages are pure local view state.
 *
 * Nothing here blocks the game's main thread: provider calls run on a worker pool
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

    /** Sidebar sections of the expanded menu. */
    private static final int TAB_CONTROL = 0;
    private static final int TAB_STATUS = 1;

    /* Plain tag keys for the card parts (no resource ids exist in this module). */
    private static final int TAG_TITLE = 0x7E010001;
    private static final int TAG_STATE = 0x7E010002;
    private static final int TAG_NOTE = 0x7E010003;
    private static final int TAG_SWITCH = 0x7E010004;

    /** Top inset for the capsule and the menu; the half-hidden tab ignores it. */
    private static final int TOP_GAP_DP = 8;
    /** Idle window before the capsule / menu folds back into the handle. */
    private static final long IDLE_COLLAPSE_MS = 6_000L;
    /**
     * Fall-back delay while something must not be interrupted: a touch still down, a
     * start sequence running, or a provider write in flight.
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

    /** Expanded menu: light rounded panel with a left sidebar and three switch cards. */
    private LinearLayout menu;
    private TextView title;
    private View titleDot;
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

    /* Switch card 2: auto-rematch (rematchSet), independent of card 1. */
    private LinearLayout rematchCard;
    private TextView rematchTitle;
    private TextView rematchState;
    private TextView rematchNote;
    private GameTopUi.Switch rematchSwitch;

    /* 开关卡 3：推荐与番点面板（panelSet），与卡 1、卡 2 完全独立。 */
    private LinearLayout panelCard;
    private TextView panelTitle;
    private TextView panelState;
    private TextView panelNote;
    private GameTopUi.Switch panelSwitch;

    /* Status page: runtime facts and the start/stop/manual/collapse actions. */
    private TextView statusMode;
    private TextView statusDetail;
    private TextView statusService;
    private TextView statusRematch;
    private TextView startStop;
    private LinearLayout manualRow;
    private LinearLayout collapseRow;

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
    private TabView handleTab;
    private TextView capsule;
    /** Only keeps the wrap-content root clamped top-centre; it never drives a drag. */
    private SizeWatcher hooks;

    /**
     * Idle auto-collapse: while the capsule or the menu is showing, this brings the
     * control back to the handle after {@link #IDLE_COLLAPSE_MS} without interaction.
     * Only a user interaction (or an action still running) restarts it - polling and
     * {@link #applyState()} never do, so a one-second status refresh cannot keep the
     * menu open forever.
     */
    private final Runnable collapseTick = new Runnable() {
        @Override public void run() {
            collapseIfIdle();
        }
    };
    private long lastTouchAt;
    /** True between ACTION_DOWN and ACTION_UP/CANCEL inside the menu area. */
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
        panel = build(activity);
        // Wrap-content child of the decor view: it is only as large as the visible
        // stage, never a full-screen surface, so the game keeps every other touch.
        panelParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ((ViewGroup) decorView).addView(panel, panelParams);
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
        tabControl = tabStatus = null;
        tabControlLabel = tabStatusLabel = null;
        pageControl = pageStatus = null;
        unattendedCard = null;
        unattendedTitle = unattendedState = unattendedNote = null;
        unattendedSwitch = null;
        rematchCard = null;
        rematchTitle = rematchState = rematchNote = null;
        rematchSwitch = null;
        panelCard = null;
        panelTitle = panelState = panelNote = null;
        panelSwitch = null;
        statusMode = statusDetail = statusService = statusRematch = null;
        startStop = null;
        manualRow = collapseRow = null;
        active.clear();
    }
    /* ---------------------------------------------------------------------- UI */

    private static int dp(Context context, int value) {
        return GameTopUi.dp(context, value);
    }

    /** Rounds a pressable background so taps get a ripple inside the rounded shape. */
    private static void ripple(View view, GradientDrawable shape) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x1F2F6FE4), shape, null));
        } else {
            view.setBackground(shape);
        }
    }

    private TextView cell(Context context, float size, int color, boolean bold) {
        TextView view = GameTopUi.text(context, size, color, bold);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6));
        view.setMinHeight(dp(context, 32));
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
        restartIdleTimer();
        applyState();
        schedulePoll(0L);
    }

    private StageRoot build(Activity activity) {
        StageRoot root = new StageRoot(activity);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        // Compact dark handle matching the reference top-centre entry.
        // The capsule body is drawn above the viewport; only its curved bottom remains.
        handleTab = new TabView(activity,
            GameTopUi.round(activity, 26, 0xDD141E25, 0x553F505C, 1));
        handleTab.setContentDescription(t("展开雀魂助手"));
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

        // Full "雀魂助手" capsule, ~160x44dp, just below the top edge.
        capsule = cell(activity, 13, 0xFFE3EAF0, false);
        capsule.setGravity(Gravity.CENTER);
        capsule.setPadding(dp(activity, 16), 0, dp(activity, 16), 0);
        capsule.setMinHeight(dp(activity, 44));
        capsule.setContentDescription(t("打开雀魂助手菜单"));
        // One-line pill: translated copy trims inside the same measured box.
        InGameLocalizer.layout(capsule, 1);
        ripple(capsule, GameTopUi.round(activity, 22, 0xEE1C2732, 0x66536373, 1));
        capsule.setElevation(dp(activity, 4));
        // openMenu() arms the idle window, so the capsule needs no touch listener of its
        // own; a plain click keeps the ripple and the accessibility tap working.
        capsule.setOnClickListener(view -> openMenu());
        FrameLayout.LayoutParams capsuleParams = new FrameLayout.LayoutParams(
            dp(activity, 160), dp(activity, 44), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
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
        root.addView(menuHost, menuParams);

        // Outer touch watcher. It only observes: every event is handed straight back to
        // the normal child/click path (return false), so switches, rows and the scroll
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

    /** Restarts the idle window; the handle stage has no timer to run. */
    private void restartIdleTimer() {
        main.removeCallbacks(collapseTick);
        lastTouchAt = SystemClock.elapsedRealtime();
        if (stage == STAGE_HANDLE) return;
        // A start sequence or a settings write must not land on a collapsed view: the
        // reply is kept and repainted, but the fall-back waits until it is done.
        long delay = actionBusy.get() || starting ? ACTION_HOLD_MS : IDLE_COLLAPSE_MS;
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
        long idle = SystemClock.elapsedRealtime() - lastTouchAt;
        if (idle < IDLE_COLLAPSE_MS - 250L) {
            main.postDelayed(collapseTick, Math.max(250L, IDLE_COLLAPSE_MS - idle));
            return;
        }
        collapse();
    }

    /** Back to the half-hidden handle. The timer is not armed again from here. */
    private void collapse() {
        stopIdleTimer();
        if (stage == STAGE_HANDLE) return;
        stage = STAGE_HANDLE;
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
                width = Math.max(width, child.getMeasuredWidth() + params.leftMargin + params.rightMargin);
                height = Math.max(height, child.getMeasuredHeight() + params.topMargin + params.bottomMargin);
            }
            if (maxW > 0) width = Math.min(width, maxW);
            if (maxH > 0) height = Math.min(height, maxH);
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
        }
    }

    /** A compact menu with matching visual, layout and touch bounds. */
    private static final class ScaledMenuHost extends FrameLayout {
        private static final float SCALE = .75f;
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
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(visualHeight / SCALE), MeasureSpec.EXACTLY));
            setMeasuredDimension(Math.round(child.getMeasuredWidth() * SCALE),
                Math.round(child.getMeasuredHeight() * SCALE));
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

    /** Top-clipped capsule: visible and touchable bounds use the reference proportions. */
    private static final class TabView extends View {
        private final Drawable background;

        TabView(Context context, GradientDrawable pill) {
            super(context);
            background = pill;
            setFocusable(false);
            setFocusableInTouchMode(false);
            setClickable(true);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int availableWidth = metrics.widthPixels;
            int availableHeight = metrics.heightPixels;
            if (getRootView().getWidth() > 0) availableWidth = getRootView().getWidth();
            if (getRootView().getHeight() > 0) availableHeight = getRootView().getHeight();
            int width = Math.max(GameTopUi.dp(getContext(), 28), Math.round(availableWidth * .0445f));
            int height = Math.max(GameTopUi.dp(getContext(), 12), Math.round(availableHeight * .054f));
            height = Math.min(height, GameTopUi.dp(getContext(), 28));
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            // A full rounded capsule extends off-screen, matching the reference's
            // shallow lower arc rather than a detached floating rectangle.
            int fullHeight = Math.max(getHeight() * 3, GameTopUi.dp(getContext(), 48));
            background.setBounds(0, getHeight() - fullHeight, getWidth(), getHeight());
            background.draw(canvas);
            android.graphics.Paint mark = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            mark.setColor(0xFFE3EAF0);
            mark.setStyle(android.graphics.Paint.Style.STROKE);
            mark.setStrokeWidth(Math.max(2f, getHeight() * .045f));
            mark.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            android.graphics.Path chevron = new android.graphics.Path();
            chevron.moveTo(getWidth() * .44f, getHeight() * .58f);
            chevron.lineTo(getWidth() * .50f, getHeight() * .78f);
            chevron.lineTo(getWidth() * .56f, getHeight() * .58f);
            canvas.drawPath(chevron, mark);
        }

        @Override public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            setAlpha(pressed ? .65f : 1f);
        }
    }

    /**
     * Vertical LinearLayout capped to a maximum height. Used for the expanded panel so
     * it can never grow past the window in landscape; the inner pages are measured
     * against the cap, and the top of the panel (header, sidebar) stays reachable.
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
            int limit = bounded
                ? Math.min(MeasureSpec.getSize(heightSpec), maxHeight)
                : maxHeight;
            if (maxHeight > 0) {
                heightSpec = MeasureSpec.makeMeasureSpec(limit,
                    MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                        ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthSpec, heightSpec);
            int measured = getMeasuredHeight();
            if (maxHeight > 0 && measured > limit) {
                setMeasuredDimension(getMeasuredWidth(), limit);
            }
        }
    }
    /** The expanded panel: white rounded surface, sidebar, two pages. */
    private LinearLayout buildMenu(Activity activity) {
        int maxHeight = Math.round(activity.getResources().getDisplayMetrics().heightPixels
            - GameTopUi.density(activity) * (TOP_GAP_DP + 8));
        LinearLayout panelView = new CappedColumn(activity, maxHeight);
        panelView.setBackground(GameTopUi.panel(activity));
        panelView.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 18));
        panelView.setElevation(dp(activity, 12));
        // Width is capped so a narrow portrait screen never pushes the panel off-screen;
        // the status page scrolls instead of growing past the window height.
        int width = Math.min(dp(activity, 272),
            Math.max(dp(activity, 232), Math.round(screenWidthDp(activity) * 0.84f)));
        panelView.setMinimumWidth(width);


        panelView.addView(buildHeader(activity));
        panelView.addView(GameTopUi.divider(activity));

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        panelView.addView(body, bodyParams);

        LinearLayout sidebar = new LinearLayout(activity);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sidebarParams = new LinearLayout.LayoutParams(
            dp(activity, 66), ViewGroup.LayoutParams.WRAP_CONTENT);
        sidebarParams.rightMargin = dp(activity, 10);
        body.addView(sidebar, sidebarParams);

        tabControl = sidebarTab(activity, t("控制"), () -> selectTab(TAB_CONTROL));
        tabControlLabel = (TextView) tabControl.getChildAt(0);
        sidebar.addView(tabControl);
        sidebar.addView(spacer(activity, 6));
        tabStatus = sidebarTab(activity, t("状态"), () -> selectTab(TAB_STATUS));
        tabStatusLabel = (TextView) tabStatus.getChildAt(0);
        sidebar.addView(tabStatus);

        android.widget.ScrollView pageScroll = new android.widget.ScrollView(activity);
        pageScroll.setFillViewport(true);
        body.addView(pageScroll, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        FrameLayout pages = new FrameLayout(activity);
        pageScroll.addView(pages, new android.widget.ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pageControl = buildControlPage(activity);
        pages.addView(pageControl, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pageStatus = buildStatusPage(activity);
        pages.addView(pageStatus, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panelView.addView(GameTopUi.divider(activity));
        collapseRow = actionRow(activity, t("收起面板"), GameTopUi.INK_MUTED, null);
        collapseRow.setOnClickListener(view -> {
            // A tap on the collapse row is an explicit user request: no idle wait.
            collapse();
        });
        panelView.addView(collapseRow);
        return panelView;
    }

    private LinearLayout buildHeader(Activity activity) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(activity, 2), 0, dp(activity, 2), 0);

        titleDot = GameTopUi.dot(activity, GameTopUi.INK_FAINT);
        header.addView(titleDot);

        title = GameTopUi.text(activity, 16, GameTopUi.INK, true);
        title.setText(t("雀魂助手"));
        header.addView(title, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return header;
    }

    private LinearLayout sidebarTab(Activity activity, String label, Runnable action) {
        LinearLayout tabView = new LinearLayout(activity);
        tabView.setOrientation(LinearLayout.VERTICAL);
        tabView.setGravity(Gravity.CENTER);
        tabView.setPadding(dp(activity, 6), dp(activity, 8), dp(activity, 6), dp(activity, 8));
        tabView.setMinimumHeight(dp(activity, 52));
        TextView text = GameTopUi.text(activity, 15, GameTopUi.INK_MUTED, true);
        text.setText(label);
        text.setGravity(Gravity.CENTER);
        tabView.addView(text);
        tabView.setOnClickListener(view -> action.run());
        tabView.setBackground(GameTopUi.quietPill(activity));
        return tabView;
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

        page.addView(spacer(activity, 6));

        rematchCard = switchCard(activity, page, t("自动下一局"),
            t("整场牌局结束后自动继续"),
            () -> onRematchToggle());
        rematchTitle = (TextView) rematchCard.getTag(TAG_TITLE);
        rematchState = (TextView) rematchCard.getTag(TAG_STATE);
        rematchNote = (TextView) rematchCard.getTag(TAG_NOTE);
        rematchSwitch = (GameTopUi.Switch) rematchCard.getTag(TAG_SWITCH);

        page.addView(spacer(activity, 6));

        // Click-through fixed panel content: recommendation / honour points. Its own
        // switch, live via `panelSet`; it never closes any other feature.
        panelCard = switchCard(activity, page, t("推荐与番点"),
            t("点击穿透固定面板上的推荐与番点"),
            () -> onPanelToggle());
        panelTitle = (TextView) panelCard.getTag(TAG_TITLE);
        panelState = (TextView) panelCard.getTag(TAG_STATE);
        panelNote = (TextView) panelCard.getTag(TAG_NOTE);
        panelSwitch = (GameTopUi.Switch) panelCard.getTag(TAG_SWITCH);
        return page;
    }

    /**
     * One switch card: title, live state, switch on the right, and a note line that
     * explains why an enabled switch is not acting yet. The state is only ever painted
     * from the latest provider reply ({@link #applyReply}); taps go through the same
     * callbacks the old rows used.
     */
    private LinearLayout switchCard(Activity activity, LinearLayout page, String titleText,
                                    String subtitle, Runnable action) {
        LinearLayout card = vertical(activity);
        card.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
        ripple(card, GameTopUi.card(activity));
        card.setClickable(true);
        card.setContentDescription(titleText);
        card.setOnClickListener(view -> action.run());

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels = vertical(activity);
        TextView titleView = GameTopUi.text(activity, 16, GameTopUi.INK, true);
        titleView.setText(titleText);
        labels.addView(titleView);
        TextView stateView = GameTopUi.text(activity, 14, GameTopUi.INK_FAINT, false);
        stateView.setText(subtitle);
        stateView.setPadding(0, dp(activity, 2), 0, 0);
        labels.addView(stateView);
        top.addView(labels, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        GameTopUi.Switch toggle = new GameTopUi.Switch(activity);
        toggle.setContentDescription(titleText + "开关");
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        switchParams.leftMargin = dp(activity, 8);
        top.addView(toggle, switchParams);
        card.addView(top);

        TextView note = GameTopUi.text(activity, 14, GameTopUi.INK_MUTED, false);
        note.setPadding(0, dp(activity, 6), 0, 0);
        // Translated copy may be longer than the Chinese source; it wraps inside the
        // card's existing width instead of widening it (no size or padding is touched).
        InGameLocalizer.layout(titleView, 2);
        InGameLocalizer.layout(stateView, 2);
        InGameLocalizer.layout(note, 3);
        card.addView(note);

        // Filled by buildControlPage right after this call; no resource ids involved.
        card.setTag(TAG_TITLE, titleView);
        card.setTag(TAG_STATE, stateView);
        card.setTag(TAG_NOTE, note);
        card.setTag(TAG_SWITCH, toggle);
        page.addView(card);
        return card;
    }

    /** Page 2: runtime facts plus the manual / start-stop actions. */
    private LinearLayout buildStatusPage(Activity activity) {
        LinearLayout page = vertical(activity);

        LinearLayout card = vertical(activity);
        card.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
        card.setBackground(GameTopUi.card(activity));

        statusMode = GameTopUi.text(activity, 15, GameTopUi.INK, true);
        card.addView(statusMode);
        statusDetail = GameTopUi.text(activity, 14, GameTopUi.INK_MUTED, false);
        statusDetail.setPadding(0, dp(activity, 4), 0, 0);
        card.addView(statusDetail);
        statusService = GameTopUi.text(activity, 14, GameTopUi.INK_MUTED, false);
        statusService.setPadding(0, dp(activity, 4), 0, 0);
        card.addView(statusService);
        statusRematch = GameTopUi.text(activity, 14, GameTopUi.INK_MUTED, false);
        statusRematch.setPadding(0, dp(activity, 4), 0, 0);
        card.addView(statusRematch);
        InGameLocalizer.layout(statusMode, 3);
        InGameLocalizer.layout(statusDetail, 3);
        InGameLocalizer.layout(statusService, 2);
        InGameLocalizer.layout(statusRematch, 2);
        page.addView(card);

        page.addView(spacer(activity, 8));

        startStop = cell(activity, 12, Color.WHITE, true);
        startStop.setMinHeight(dp(activity, 40));
        ripple(startStop, GameTopUi.round(activity, 12, GameTopUi.ACCENT, 0, 0));
        startStop.setOnClickListener(view -> onToggle());
        InGameLocalizer.layout(startStop, 2);
        page.addView(startStop, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        manualRow = actionRow(activity, t("打开管理应用"), GameTopUi.ACCENT, null);
        manualRow.setOnClickListener(view -> openManager());
        page.addView(manualRow);
        return page;
    }

    private LinearLayout actionRow(Activity activity, String label, int color, String description) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setMinimumHeight(dp(activity, 36));
        row.setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 6));
        TextView text = GameTopUi.text(activity, 15, color, true);
        text.setText(label);
        row.addView(text);
        if (description != null) row.setContentDescription(description);
        ripple(row, GameTopUi.round(activity, 12, GameTopUi.CARD, 0x262F6FE4, 1));
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
            show(menu, false);
            return;
        }
        boolean menuShown = stage == STAGE_MENU;
        show(menu, menuShown);
        show(capsule, stage == STAGE_CAPSULE);
        show(handleTab, stage == STAGE_HANDLE);
        if (stage == STAGE_CAPSULE) setText(capsule, t("雀魂助手") + " · " + shortState() + "  ▸");
        if (menuShown) {
            paintTab();
            paintSwitches();
            paintStatusPage();
            paintActions();
        }
        // Polling repaints arrive here every few seconds and deliberately never touch
        // the idle window: only a real interaction (restartIdleTimer) re-arms it, and the
        // capsule / menu are only entered through those interaction paths.
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
        applyState();
    }

    /** Blue capsule for the active sidebar entry, white capsule for the other one. */
    private void paintTab() {
        boolean control = tab == TAB_CONTROL;
        styleTab(tabControl, tabControlLabel, control);
        styleTab(tabStatus, tabStatusLabel, !control);
        show(pageControl, control);
        show(pageStatus, !control);
    }

    private static void styleTab(View pill, TextView label, boolean selected) {
        if (pill == null || label == null) return;
        Context context = pill.getContext();
        pill.setBackground(selected ? GameTopUi.accentPill(context) : GameTopUi.quietPill(context));
        int color = selected ? Color.WHITE : GameTopUi.INK_MUTED;
        if (label.getCurrentTextColor() != color) label.setTextColor(color);
    }

    /** Live header: a coloured dot plus the current mode in one short phrase. */
    private void paintSwitches() {
        if (title == null) return;
        setText(title, t("雀魂助手") + " · " + headerState());
        int dotColor = starting ? GameTopUi.INK_FAINT
            : enabled ? GameTopUi.ACCENT
            : available ? 0xFF43B581
            : demo ? 0xFFE2A93B
            : unreachable ? GameTopUi.DANGER
            : GameTopUi.INK_FAINT;
        Drawable dot = titleDot == null ? null : titleDot.getBackground();
        if (dot instanceof GradientDrawable) {
            ((GradientDrawable) dot).setColor(dotColor);
        }

        // Card 1 is a pure view of the mode: enabled only when the manager says so,
        // and never editable while a start sequence or another action is in flight.
        if (unattendedSwitch != null) {
            unattendedSwitch.setOn(enabled, true);
            unattendedSwitch.setBusy(starting || actionBusy.get());
        }
        String unattendedStateText = unattendedStateText();
        setCardText(unattendedCard, TAG_STATE, unattendedState, unattendedStateText);
        if (unattendedState != null) {
            // The first line doubles as subtitle until the first reply arrives, so it
            // always says what the card does or what it is doing right now.
            unattendedState.setTextColor(available || starting || unreachable
                ? GameTopUi.INK_MUTED : GameTopUi.INK_FAINT);
        }
        setText(unattendedNote, unattendedNoteText());
        int unattendedTone = noticeFailed && noticeRefersToUnattended()
            ? GameTopUi.DANGER : GameTopUi.INK_MUTED;
        if (unattendedNote != null) unattendedNote.setTextColor(unattendedTone);

        // Card 2 mirrors the stored rematch opt-in; it stays tappable while unattended
        // mode is off, exactly like the old single row did.
        if (rematchSwitch != null) {
            rematchSwitch.setOn(rematch, true);
            rematchSwitch.setBusy(actionBusy.get() && !starting);
        }
        setCardText(rematchCard, TAG_STATE, rematchState, rematch ? t("已开启") : t("已关闭"));
        if (rematchState != null) {
            rematchState.setTextColor(rematchActive ? GameTopUi.ACCENT
                : rematch ? 0xFFC08A1E : GameTopUi.INK_FAINT);
        }
        setText(rematchNote, rematchNoteText());
        if (rematchNote != null) {
            rematchNote.setTextColor(panelNoticeFailed()
                ? GameTopUi.DANGER : GameTopUi.INK_MUTED);
        }

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
            panelState.setTextColor(recommendationPanel ? GameTopUi.ACCENT : GameTopUi.INK_FAINT);
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
        setText(statusMode, modeText());
        if (statusMode != null) {
            statusMode.setTextColor(noticeFailed ? GameTopUi.DANGER : GameTopUi.INK);
        }
        setText(statusDetail, detailText());
        setText(statusService, serviceText());
        setText(statusRematch, rematchText());
        if (statusRematch != null) {
            statusRematch.setTextColor(rematchActive ? GameTopUi.ACCENT : GameTopUi.INK_MUTED);
        }
    }

    private void paintActions() {
        if (startStop != null) {
            setText(startStop, toggleText());
            startStop.setAlpha(starting ? .55f : 1f);
        }
        String manualText = manualText();
        if (manualRow != null) {
            show(manualRow, manualText != null);
            manualRow.setContentDescription(manualText == null ? t("打开管理应用") : manualText);
            if (manualRow.getChildCount() > 0 && manualRow.getChildAt(0) instanceof TextView) {
                TextView label = (TextView) manualRow.getChildAt(0);
                setText(label, manualText == null ? t("打开管理应用") : manualText);
                label.setTextColor(noticeFailed ? GameTopUi.DANGER : GameTopUi.ACCENT);
            }
        }
    }

    private String headerState() {
        if (!display.isDecided()) return t("读取显示模式");
        if (starting) return t("启动中");
        if (enabled) return t("无人值守开");
        if (available) return t("待机");
        if (demo) return t("自检中");
        if (unreachable) return t("未连接");
        return t("未启动");
    }

    private String unattendedStateText() {
        if (starting) return t("启动中…");
        if (enabled) return t("运行中");
        if (demo) return t("自检中 · 暂不可开");
        if (!available) return t("未启动");
        return t("已关闭");
    }

    /**
     * Why an "on" switch is not acting yet, or what a tap will do. Kept short: the
     * card is narrow, so the line may wrap to a second line inside it but never grows
     * the card's width.
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
        return t("牌局结束后自动继续，与上项独立");
    }

    /** Note line of card 3: why it does or does not draw, in one short phrase. */
    private String panelNoteText() {
        if (panelNoticeFailed()) return notice;
        if (recommendationPanel) return t("固定面板显示建议牌与番点 · 不影响其他功能");
        return t("已隐藏建议牌与番点 · 其他功能不受影响");
    }

    private String serviceText() {
        if (unreachable) return managerMissing ? t("管理应用 · 未安装") : t("管理应用 · 无响应");
        if (!available && demo) return t("前台服务 · 自检中");
        if (!available) return t("前台服务 · 未运行");
        return t("前台服务 · 运行中") + (remoteMessage.isEmpty() ? "" : " · " + clip(remoteMessageText(), 14));
    }

    /** One-phrase state for the collapsed capsule. */
    private String shortState() {
        if (starting) return t("启动中");
        if (enabled) return t("无人值守 开");
        if (available) return t("已就绪");
        if (demo) return t("自检中");
        return t("未启动");
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
     * label can keep a string from the previous language.
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
        tabControl = tabStatus = null;
        tabControlLabel = tabStatusLabel = null;
        pageControl = pageStatus = null;
        unattendedCard = null;
        unattendedTitle = unattendedState = unattendedNote = null;
        unattendedSwitch = null;
        rematchCard = null;
        rematchTitle = rematchState = rematchNote = null;
        rematchSwitch = null;
        panelCard = null;
        panelTitle = panelState = panelNote = null;
        panelSwitch = null;
        statusMode = statusDetail = statusService = statusRematch = null;
        startStop = null;
        manualRow = collapseRow = null;

        panel = build(activity);
        parent.addView(panel, panelParams);
        this.stage = stage;
        this.tab = tab;
        if (hooks == null) {
            hooks = new SizeWatcher(activity);
            parent.addOnLayoutChangeListener(hooks);
        }
    }

    /* ------------------------------------------------------------------- copy */

    /**
     * The language was just re-seeded or switched. The next [applyState] rebuilds the
     * views of the current stage (same stage, same geometry) so every label is painted
     * from scratch in the new language — including the ones that are only written at
     * build time. Nothing accumulates: the stage root is reused, not added to.
     */
    private boolean languageStale;

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
     * three card titles, the collapse row and the hand-off row.
     *
     * Called from [applyState] on every pass, so a language switch reaches the *static*
     * labels too and not only the values that were already dynamic. Nothing here adds a
     * listener or a view, which is what keeps repeated replies from stacking anything.
     */
    private void paintStatic() {
        if (tabControlLabel != null && !TextUtils.equals(tabControlLabel.getText(), t("控制"))) {
            setText(tabControlLabel, t("控制"));
        }
        if (tabStatusLabel != null && !TextUtils.equals(tabStatusLabel.getText(), t("状态"))) {
            setText(tabStatusLabel, t("状态"));
        }
        if (tabControl != null) setDescription(tabControl, t("控制"));
        if (tabStatus != null) setDescription(tabStatus, t("状态"));
        InGameLocalizer.layout(tabControlLabel, 1);
        InGameLocalizer.layout(tabStatusLabel, 1);

        setStaticCard(unattendedCard, "无人值守", "助手服务自动分析并出牌", true);
        setStaticCard(rematchCard, "自动下一局", "整场牌局结束后自动继续", false);
        setStaticCard(panelCard, "推荐与番点", "点击穿透固定面板上的推荐与番点", false);

        paintActionRow(collapseRow, "收起面板");
        // The hand-off row only names its static label while it has no live label of its
        // own; [paintActions] owns it otherwise.
        if (manualRow == null || manualText() == null) paintActionRow(manualRow, "打开管理应用");

        InGameLocalizer.layout(title, 1);
        InGameLocalizer.layout(capsule, 1);
        InGameLocalizer.layout(unattendedNote, 3);
        InGameLocalizer.layout(rematchNote, 3);
        InGameLocalizer.layout(panelNote, 3);
        InGameLocalizer.layout(statusMode, 3);
        InGameLocalizer.layout(statusDetail, 3);
        InGameLocalizer.layout(statusService, 2);
        InGameLocalizer.layout(statusRematch, 2);
        InGameLocalizer.layout(startStop, 2);
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
        InGameLocalizer.layout(titleView, 2);
        InGameLocalizer.layout(stateView, 2);
        InGameLocalizer.layout(noteView, 3);
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
            InGameLocalizer.layout(text, 2);
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
