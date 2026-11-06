package com.yefeng.majmax.hookprobe;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.yefeng.majmax.hookprobe.manager.AssistLaunch;

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
 * </ul>
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
    private LinearLayout panel;
    private TextView handle;
    private TextView capsule;
    private LinearLayout menu;
    private TextView title;
    private TextView toggle;
    private TextView rematchRow;
    private TextView detail;
    private TextView manual;
    private TextView collapse;

    /** Survives activity switches so the menu does not collapse on every transition. */
    private int stage = STAGE_HANDLE;
    private int generation;
    private final GameHintLayer hints = new GameHintLayer();

    /* Last reply from the manager app. */
    private boolean available;
    private boolean enabled;
    private boolean demo;
    private String remoteMessage = "";
    private boolean unreachable;
    private boolean managerMissing;

    /**
     * True while the user has selected the system-overlay surface in the manager's
     * settings. In that mode this whole in-game control (handle, capsule, menu) is
     * hidden so it never duplicates the overlay, but polling continues so a switch
     * back to Hook mode is picked up immediately and the control reappears.
     */
    private boolean overlayMode;

    /**
     * Auto-rematch as reported by the manager's status reply. [rematch] is the persisted
     * opt-in (what the row shows and toggles); [rematchActive] is the effective gate
     * reported alongside it and is only used to explain why an enabled opt-in is inert.
     * Both are read-only here: the row's tap is the only writer, and it never touches
     * unattended mode, the service lifetime or the display mode.
     */
    private boolean rematch;
    private boolean rematchActive;

    /* Local, transient start sequence. */
    private boolean starting;
    private long startingAt;
    private String notice = "";
    private boolean noticeFailed;

    void register(Application app) {
        app.registerActivityLifecycleCallbacks(this);
    }

    /* ---------------------------------------------------------------- lifecycle */

    @Override public void onActivityResumed(Activity activity) { attach(activity); }

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
        if (panel != null && active.get() == activity) return;
        detach();
        if (activity.getWindow() == null) return;
        View decor = activity.getWindow().peekDecorView();
        if (!(decor instanceof ViewGroup)) return;
        active = new WeakReference<>(activity);
        panel = build(activity);
        ((ViewGroup) decor).addView(panel, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        applyState();
        hints.attach(activity);
        schedulePoll(0L);
    }

    private void detach() {
        hints.detach();
        generation++;
        main.removeCallbacks(pollTick);
        if (panel != null && panel.getParent() instanceof ViewGroup) {
            ((ViewGroup) panel.getParent()).removeView(panel);
        }
        panel = null;
        handle = capsule = title = toggle = rematchRow = detail = manual = collapse = null;
        menu = null;
        active.clear();
    }

    /* ---------------------------------------------------------------------- UI */

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }

    private TextView label(Activity activity, int size, int height, int padding) {
        TextView view = new TextView(activity);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(activity, padding), 0, dp(activity, padding), 0);
        view.setMinHeight(dp(activity, height));
        return view;
    }

    private LinearLayout build(Activity activity) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(225, 20, 31, 29));
        background.setCornerRadii(new float[] {
            0, 0, 0, 0, dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16)
        });
        root.setBackground(background);
        root.setElevation(dp(activity, 8));

        handle = label(activity, 14, 18, 14);
        handle.setText("⌄");
        handle.setContentDescription("展开雀魂助手胶囊");
        handle.setOnClickListener(view -> {
            stage = STAGE_CAPSULE;
            applyState();
        });
        root.addView(handle);

        capsule = label(activity, 14, 34, 14);
        capsule.setContentDescription("打开雀魂助手菜单");
        capsule.setOnClickListener(view -> {
            stage = STAGE_MENU;
            applyState();
            schedulePoll(0L);
        });
        root.addView(capsule);

        menu = new LinearLayout(activity);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setGravity(Gravity.CENTER_HORIZONTAL);
        menu.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 6));
        root.addView(menu);

        title = label(activity, 12, 22, 10);
        title.setGravity(Gravity.CENTER);
        menu.addView(title);

        toggle = label(activity, 14, 44, 16);
        toggle.setOnClickListener(view -> onToggle());
        menu.addView(toggle);

        // Independent of the unattended row above: this only flips the persisted
        // auto-rematch preference, so it stays tappable while unattended mode is off.
        rematchRow = label(activity, 14, 44, 16);
        rematchRow.setOnClickListener(view -> onRematchToggle());
        menu.addView(rematchRow);

        detail = label(activity, 11, 16, 10);
        menu.addView(detail);

        manual = label(activity, 12, 32, 12);
        manual.setTextColor(Color.rgb(140, 214, 186));
        manual.setOnClickListener(view -> openManager());
        menu.addView(manual);

        collapse = label(activity, 12, 30, 14);
        collapse.setText("收起  ⌃");
        collapse.setTextColor(Color.rgb(170, 190, 185));
        collapse.setOnClickListener(view -> {
            stage = STAGE_HANDLE;
            applyState();
        });
        menu.addView(collapse);
        return root;
    }

    private static void setText(TextView view, String text) {
        if (view == null) return;
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private void applyState() {
        if (panel == null) return;
        if (overlayMode) {
            // The user switched to the system-overlay surface in the manager. Hide the
            // entire in-game control (handle, capsule and menu) so nothing is shown
            // twice, but keep polling so the control can reappear on switch-back.
            show(handle, false);
            show(capsule, false);
            show(menu, false);
            return;
        }
        show(handle, stage == STAGE_HANDLE);
        show(capsule, stage == STAGE_CAPSULE);
        show(menu, stage == STAGE_MENU);
        if (stage == STAGE_CAPSULE) setText(capsule, "雀魂助手 · " + shortState() + "  ▾");
        if (stage != STAGE_MENU) return;

        setText(title, "实时模式：" + modeText());
        setText(toggle, toggleText());
        toggle.setAlpha(starting ? .55f : 1f);
        setText(rematchRow, rematchText());
        rematchRow.setTextColor(rematchActive ? Color.rgb(140, 214, 186) : Color.rgb(170, 190, 185));
        setText(detail, detailText());
        detail.setTextColor(noticeFailed ? Color.rgb(255, 158, 128) : Color.rgb(196, 206, 203));

        String manualText = manualText();
        setText(manual, manualText);
        show(manual, manualText != null);
        setText(collapse, "收起  ⌃");
    }

    private static void show(View view, boolean visible) {
        if (view == null) return;
        int wanted = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != wanted) view.setVisibility(wanted);
    }

    private String shortState() {
        if (starting) return "启动中";
        if (enabled) return "无人值守 开";
        if (available) return "已就绪";
        if (demo) return "自检中";
        return "未启动";
    }

    private String modeText() {
        if (starting && !available) return "正在启动助手服务…";
        if (!available && demo) return "模型自检中 · 暂不可开启";
        if (!available) return "助手未启动";
        String suffix = remoteMessage.isEmpty() ? "" : " · " + clip(remoteMessage, 16);
        return enabled ? "无人值守已开启" + suffix : "助手已就绪 · 无人值守关闭" + suffix;
    }

    private String toggleText() {
        if (starting) return "启动中…";
        return enabled ? "关闭无人值守" : "开启无人值守";
    }

    /**
     * The row doubles as switch and explanation: the tap target is one line, so the
     * available state (why an enabled opt-in is not acting yet, or why it cannot be
     * armed) is folded into the label instead of a second view.
     */
    private String rematchText() {
        String state = rematch ? "开" : "关";
        String note;
        if (demo && rematch) note = "自检中不生效";
        else if (rematch && !enabled) note = "待无人值守开启";
        else if (rematch && !rematchActive) note = "待条件满足";
        else if (rematch) note = "仅按下一局原按钮";
        else note = "点按开启";
        return "整场后续局 · " + state + "（" + note + "）";
    }

    private String detailText() {
        if (!notice.isEmpty()) return notice;
        if (!available) return "点击开启即自动启动管理应用的前台分析服务";
        return enabled ? "点击关闭只关闭无人值守，助手服务继续运行" : "开启后按 AI 首选操作，可随时在此关闭";
    }

    private String manualText() {
        if (starting) return null;
        if (unreachable) return managerMissing ? "未安装管理应用 · 点击打开" : "管理应用无响应 · 点击打开";
        if (noticeFailed) return "点击打开管理应用";
        return null;
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
        notice = "正在启动分析与无人值守…";
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
                notice = managerMissing ? "未找到管理应用 · 请先安装" : "管理应用无响应 · 点击重试";
            } else {
                applyReply(reply);
                if (AssistLaunch.RUNNING.equals(state) || AssistLaunch.STARTED.equals(state)) {
                    notice = "已请求启动 · 等待服务就绪";
                } else if (AssistLaunch.RESTRICTED.equals(state) || AssistLaunch.FORBIDDEN.equals(state)) {
                    String token = reply.getString("token");
                    if (launchTrampoline(token)) {
                        notice = "正在通过管理应用完成启动…";
                    } else {
                        starting = false;
                        noticeFailed = true;
                        notice = "无法打开管理应用 · 请手动开启助手";
                    }
                } else {
                    starting = false;
                    noticeFailed = true;
                    notice = AssistLaunch.UNAVAILABLE.equals(state)
                        ? "无法启动前台服务 · 点击重试" : "启动中断 · 点击重试";
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
        notice = wanted ? "正在开启整场后续局…" : "正在关闭整场后续局…";
        noticeFailed = false;
        applyState();
        Bundle args = new Bundle();
        args.putBoolean("enabled", wanted);
        call("rematchSet", args, REQUEST_TIMEOUT_MS, reply -> {
            actionBusy.set(false);
            if (reply == null) {
                noticeFailed = true;
                notice = "管理应用无响应 · 设置可能未保存";
            } else {
                applyReply(reply);
                // The reply carries the stored value, so a write that did not stick is
                // reported instead of leaving the row claiming the new state.
                noticeFailed = rematch != wanted;
                notice = rematch != wanted ? "设置未保存 · 当前" + (rematch ? "开" : "关")
                    : wanted ? "整场后续局已开启" : "整场后续局已关闭 · 无人值守未变";
            }
            applyState();
            schedulePoll(POLL_MENU_MS);
        });
    }

    private void disable() {
        if (!actionBusy.compareAndSet(false, true)) return;
        notice = "正在关闭无人值守…";
        noticeFailed = false;
        starting = false;
        applyState();
        Bundle args = new Bundle();
        args.putBoolean("enabled", false);
        call("autoSet", args, REQUEST_TIMEOUT_MS, reply -> {
            actionBusy.set(false);
            if (reply == null) {
                noticeFailed = true;
                notice = "管理应用无响应 · 模式可能仍在开启";
            } else {
                applyReply(reply);
                noticeFailed = enabled;
                notice = enabled ? "未能关闭无人值守 · 点击重试" : "已关闭无人值守 · 助手服务保持运行";
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
            notice = "未安装管理应用";
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
            notice = "启动超时 · 点击重试";
            applyState();
        }
        if (!actionBusy.get() && pollBusy.compareAndSet(false, true)) {
            call("autoStatus", null, REQUEST_TIMEOUT_MS, reply -> {
                pollBusy.set(false);
                if (reply == null || "unreachable".equals(reply.getString("state", ""))) {
                    unreachable = true;
                    managerMissing = reply != null && !reply.getBoolean("installed", true);
                    available = false;
                    enabled = false;
                    demo = false;
                    remoteMessage = "";
                    // Without a reply the manager's mode is unknown; show the in-game
                    // control (rather than hide it) so the user can still reach the
                    // "open manager" action from the capsule.
                    overlayMode = false;
                } else {
                    applyReply(reply);
                    if (starting && available && enabled) {
                        starting = false;
                        notice = "";
                        noticeFailed = false;
                    }
                }
                applyState();
            });
        }
        schedulePoll(stage == STAGE_MENU || starting ? POLL_MENU_MS : POLL_IDLE_MS);
    }

    private void applyReply(Bundle reply) {
        available = reply.getBoolean("available");
        enabled = reply.getBoolean("enabled");
        demo = reply.getBoolean("demo");
        String message = reply.getString("message", "");
        remoteMessage = message == null ? "" : message.trim();
        overlayMode = reply.getBoolean("overlayMode", false);
        rematch = reply.getBoolean("rematch", rematch);
        rematchActive = reply.getBoolean("rematchActive", false);
        unreachable = false;
        managerMissing = false;
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
