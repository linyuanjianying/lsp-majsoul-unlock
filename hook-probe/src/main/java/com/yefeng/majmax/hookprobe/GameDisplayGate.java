package com.yefeng.majmax.hookprobe;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The game process's copy of the assistant display mode, and the only source of truth for
 * "does this process build the in-game surface at all".
 *
 * The rule is strict and one-shot per game process:
 *
 * <ul>
 *   <li>Nothing is decided and nothing is drawn until the provider answered at least once.
 *       The first ask is {@link #requestMode(Context)}, fired from
 *       {@link GameTopControl#register}; the periodic {@code autoStatus} poll only fills in the
 *       remaining fields afterwards.</li>
 *   <li>{@link Mode#HOOK} is the only mode that lets {@link GameTopControl} and
 *       {@link GameHintLayer} be constructed and attached.</li>
 *   <li>{@link Mode#OVERLAY} never builds them: no control view, no hint layer, no polling.
 *       The system overlay owns the surface for the whole life of this process.</li>
 *   <li>A failed, unreachable or undecidable answer keeps the gate {@link Mode#UNKNOWN}: the
 *       surface stays unpainted and a later ask retries. Nothing ever falls back to
 *       {@link Mode#HOOK}; that is the one direction the gate must never guess.</li>
 * </ul>
 *
 * Threading: the provider call runs on a short-lived worker thread and never blocks the
 * application's main thread; the result is applied on the main thread, which is also where
 * every read of the state happens. The mutable fields are additionally guarded by
 * {@link #lock} because {@link #apply(Bundle)} can be reached from the control's poll reply on
 * the main thread while a worker is still in flight.
 *
 * There is no lease, no acknowledgement and no owner: the mode is a single value that the
 * manager snapshots when its service starts.
 */
final class GameDisplayGate {

    enum Mode { UNKNOWN, HOOK, OVERLAY }

    /** Which value a decision came from; used for diagnostics only. */
    static final int SOURCE_UNKNOWN = 0;
    static final int SOURCE_SERVICE = 1;
    static final int SOURCE_SELECTION = 2;

    interface Listener {
        /**
         * Called on the main thread once, when this process learns it must build the
         * in-game surface ({@link Mode#HOOK}). Never called twice for the same process.
         */
        void onHookModeConfirmed();
    }

    private static final Uri ENDPOINT = Uri.parse("content://com.yefeng.majmax.hookprobe.ai/capture");

    /** Retry delays for an unresolved (UNKNOWN) gate; the last value repeats. */
    static final long[] RETRY_DELAYS_MS = {500L, 1_000L, 2_000L, 4_000L};

    private final Listener listener;
    private final Handler main;
    /** One ask in flight at a time, across all threads. */
    private final AtomicBoolean querying = new AtomicBoolean();
    private final Object lock = new Object();

    private Mode mode = Mode.UNKNOWN;
    private int source = SOURCE_UNKNOWN;
    private String nonce = "";
    private String overlayError = "";
    /** True once a *decidable* answer arrived; before that nothing may be drawn. */
    private boolean decided;
    private boolean handedOver;
    private int attempt;
    /** True once a retry is armed, so a burst of callers cannot queue several. */
    private boolean retryArmed;

    private final Runnable retryTick = new Runnable() {
        @Override public void run() {
            synchronized (lock) { retryArmed = false; }
            Context context = lastContext;
            if (context != null) requestMode(context);
        }
    };

    /** Application context of the last ask; used only by the retry timer. */
    private volatile Context lastContext;

    GameDisplayGate(Listener listener) {
        this.listener = listener;
        this.main = new Handler(Looper.getMainLooper());
    }

    /**
     * Builds a gate that reports into {@code onHook}. Kept as a static factory so
     * {@link GameTopControl} does not have to implement the interface.
     */
    static GameDisplayGate forHook(Runnable onHook) {
        return new GameDisplayGate(() -> onHook.run());
    }

    Mode getMode() {
        synchronized (lock) { return mode; }
    }

    int getSource() {
        synchronized (lock) { return source; }
    }

    String getOverlayError() {
        synchronized (lock) { return overlayError; }
    }

    /** True once a decidable answer arrived; until then nothing is drawn. */
    boolean isDecided() {
        synchronized (lock) { return decided; }
    }

    /** True while hook mode is confirmed — the only state in which surfaces may exist. */
    boolean hookSelected() {
        synchronized (lock) { return decided && mode == Mode.HOOK; }
    }

    /**
     * True while this game process runs in overlay mode. Diagnostic only: in this mode no
     * in-game view is built at all, so there is nothing to hide or re-show.
     */
    boolean overlaySelected() {
        synchronized (lock) { return decided && mode == Mode.OVERLAY; }
    }

    /**
     * Asks the manager which mode is running. Returns immediately: the provider call happens on
     * a worker thread and the result is applied (and possibly handed over) on the main thread.
     *
     * Safe to call repeatedly — from {@code register}, from {@code onActivityResumed} while the
     * gate is still unknown, or from the retry timer. A call while a request is in flight, or
     * once the mode is decided, does nothing but re-check the hand-over. When the answer is
     * unusable the gate stays {@link Mode#UNKNOWN} and one bounded retry is armed.
     */
    void requestMode(final Context context) {
        if (context == null) return;
        lastContext = context.getApplicationContext();
        boolean decidedNow;
        synchronized (lock) { decidedNow = decided; }
        if (decidedNow) {
            maybeHandOver();
            return;
        }
        if (!querying.compareAndSet(false, true)) return;
        final Context app = lastContext;
        Thread worker = new Thread(() -> {
            Bundle reply = null;
            try {
                reply = app.getContentResolver().call(ENDPOINT, "displayMode", null, new Bundle());
            } catch (RuntimeException | Error ignored) {
                // Manager missing / disabled / grant revoked: no answer at all.
            }
            final Bundle answer = reply;
            main.post(() -> {
                querying.set(false);
                if (answer != null && apply(answer)) {
                    maybeHandOver();
                } else {
                    scheduleRetry();
                }
            });
        }, "majmax-display-mode");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Applies the display fields of one reply. Returns true only when the reply produced a
     * decision (now or earlier).
     *
     * Guarded on purpose against every "no information" shape:
     * <ul>
     *   <li>{@code unreachable} / missing mode → nothing is decided;</li>
     *   <li>{@code displaySource == 0} (the manager itself could not read its state) → refused
     *       outright: that is exactly the case that used to arrive as a defaulted "hook";</li>
     *   <li>a reply without {@code displaySource} at all is only accepted when it carries a
     *       real mode value (an older manager), never for an absent/blank mode.</li>
     * </ul>
     * Once decided, later replies only refresh the reported overlay error.
     */
    boolean apply(Bundle reply) {
        if (reply == null) return isDecided();
        if ("unreachable".equals(reply.getString("state", ""))) return isDecided();
        String wire = reply.getString("mode");
        if (wire == null) wire = reply.getString("displayMode");
        if (wire == null) return isDecided();
        Mode next;
        if ("overlay".equals(wire)) next = Mode.OVERLAY;
        else if ("hook".equals(wire)) next = Mode.HOOK;
        else return isDecided();
        boolean hasSource = reply.containsKey("displaySource");
        int nextSource = hasSource ? reply.getInt("displaySource", SOURCE_UNKNOWN) : -1;
        if (hasSource && nextSource == SOURCE_UNKNOWN) {
            // The manager could not determine its own state. Its `mode` is a placeholder, not
            // an answer: refuse it and stay UNKNOWN rather than locking the gate to hook.
            return isDecided();
        }
        String nextNonce = reply.getString("displayNonce", "");
        if (nextNonce == null) nextNonce = "";
        synchronized (lock) {
            // The mode of one live service instance cannot change, and a different instance may
            // have been started from a different selection — but this process already decided
            // from its first valid answer, so a later reply never re-opens the decision.
            if (decided) {
                updateOverlayErrorLocked(reply);
                return true;
            }
            mode = next;
            nonce = nextNonce;
            source = hasSource ? nextSource : SOURCE_UNKNOWN;
            updateOverlayErrorLocked(reply);
            decided = true;
            attempt = 0;
            return true;
        }
    }

    private void updateOverlayErrorLocked(Bundle reply) {
        String error = reply.getString("overlayError", "");
        overlayError = error == null ? "" : error;
    }

    /** Arms one bounded retry for an unresolved gate. Main thread or locked callers only. */
    private void scheduleRetry() {
        long delay;
        synchronized (lock) {
            if (decided || retryArmed || lastContext == null) return;
            retryArmed = true;
            int index = Math.min(attempt, RETRY_DELAYS_MS.length - 1);
            delay = RETRY_DELAYS_MS[index];
            if (attempt < RETRY_DELAYS_MS.length) attempt++;
        }
        main.removeCallbacks(retryTick);
        main.postDelayed(retryTick, delay);
    }

    private void maybeHandOver() {
        synchronized (lock) {
            if (handedOver || !decided || mode != Mode.HOOK) return;
            handedOver = true;
        }
        listener.onHookModeConfirmed();
    }

    /** Compact status text; no game data. */
    String describe() {
        synchronized (lock) {
            StringBuilder text = new StringBuilder();
            text.append("mode=").append(mode.name().toLowerCase(java.util.Locale.ROOT));
            text.append(";source=").append(source);
            text.append(";decided=").append(decided);
            if (!overlayError.isEmpty()) text.append(";error=").append(overlayError);
            return text.toString();
        }
    }
}
