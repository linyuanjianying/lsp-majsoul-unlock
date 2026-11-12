package com.yefeng.majmax.hookprobe;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;

/**
 * Design tokens of the in-game assistant panel: one place that answers "how many pixels is
 * this design value on this screen".
 *
 * <p>The panel was redesigned from the {@code queshen-panel-kit} hand-off (preview
 * {@code 雀魂助手 · 菜单优化预览.html}). That kit is a 2376x1080 stage whose short side is
 * 1080px, and every number in it — 640px panel, 52px header, 58px tab, 84px setting row,
 * 64px status row, 72px collapsed bar — is a pixel of that stage. The kit ships resources,
 * a Compose variant and pixel-to-dp conversions at density 2.75; none of them can be used
 * here, because this control is a child of the *game's* decor view and must stay plain
 * Android views built in code (no resource ids, no inflater, no Compose). So the kit's
 * design values are reproduced here as tokens and turned into view pixels by {@link #px}
 * and {@link #sp}.
 *
 * <h2>Why this class exists: one scale, not two</h2>
 *
 * The menu is drawn inside {@code GameTopControl.ScaledMenuHost}, which lays the panel out at
 * {@code 1 / HOST_SCALE} of the size the finger sees and then applies {@code setScaleX/Y}.
 * That host is kept — it is what makes the panel's touch box exactly match its visible box.
 * The design, however, is expressed in *visible* pixels. Applying both scalings to one
 * dimension (design px -> dp at 2.75 *and* the host's .75) is what would shrink the new
 * panel to 480px instead of 640px, so the host factor is divided out here, once:
 *
 * <pre>
 *   visible px = design px * screenScale            (screenScale = shortSide / 1080)
 *   pre-scale px = visible px / HOST_SCALE            (the panel's own coordinate space)
 *   dp = pre-scale px / density
 *   sp = pre-scale px / scaledDensity
 * </pre>
 *
 * On the reporter's device (1080 short side, density 2.75) {@code screenScale} is 1, so
 * {@code px()} hands back exactly {@code design px / .75} and the panel the user sees is the
 * design, pixel for pixel. The layout probe asserts precisely that round trip.
 *
 * <p>{@link #scale} follows the kit's rule — the short side of the screen, clamped to
 * 0.75..1.6 — so a smaller or larger phone scales the whole panel with the game's own UI
 * instead of keeping a fixed dp size. {@code followScreen = false} reproduces the kit's
 * second mode (fixed dp on every device).
 */
final class GameTopDesign {
    private GameTopDesign() {}

    /* ------------------------------------------------------------------ scale */

    /** Short side of the design stage, in px. */
    static final float SHORT_SIDE_PX = 1080f;
    /** Density the kit converted its px to dp with; kept for documentation and probes. */
    static final float DESIGN_DENSITY = 2.75f;
    /** The kit never scales below this, so the copy stays readable on a small screen. */
    static final float MIN_SCALE = .75f;
    /** ...and never above this, so a tablet does not get a wall of panel. */
    static final float MAX_SCALE = 1.6f;
    /**
     * Pre-scale factor of {@code GameTopControl.ScaledMenuHost}. The panel is laid out at
     * {@code 1 / HOST_SCALE} and drawn at {@code HOST_SCALE}, so every design value has to be
     * divided by it once. {@code GameTopDesignTest} in the layout probe pins both sides of
     * this contract.
     */
    static final float HOST_SCALE = .75f;

    /** The kit's first mode: follow the screen (default). */
    static boolean followScreen = true;

    /** {@code shortSide / 1080}, clamped to the kit's range; never zero. */
    static float scale(Context context) {
        if (!followScreen) return 1f;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        if (metrics == null) return 1f;
        int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        if (shortSide <= 0) return 1f;
        float raw = shortSide / SHORT_SIDE_PX;
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, raw));
    }

    /** Visible px of a design value, i.e. what the finger measures. */
    static int visiblePx(Context context, float designPx) {
        return Math.round(designPx * scale(context));
    }

    /** Pre-scale px of a design value, in the panel's own layout space. */
    static int px(Context context, float designPx) {
        return Math.round(designPx * scale(context) / HOST_SCALE);
    }

    /** Pre-scale px as a float, for radii and strokes that end up in a drawable. */
    static float pxFloat(Context context, float designPx) {
        return designPx * scale(context) / HOST_SCALE;
    }

    /** Text size (sp) whose drawn height is the design value. */
    static float sp(Context context, float designPx) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float scaledDensity = metrics == null || metrics.scaledDensity <= 0f
            ? 1f : metrics.scaledDensity;
        return (designPx * scale(context) / HOST_SCALE) / scaledDensity;
    }

    /** Stroke width in px, never below one pixel (a 0px stroke draws nothing). */
    static int strokePx(Context context, float designPx) {
        return Math.max(1, Math.round(pxFloat(context, designPx)));
    }

    /* --------------------------------------------------------------- geometry */

    /* Panel surface (design px of the 1080-short-side stage). */
    static final int PANEL_W = 640;
    static final int PANEL_RADIUS = 28;
    static final float PANEL_STROKE = 1.5f;
    static final int PANEL_PAD_H = 16;
    static final int PANEL_PAD_TOP = 16;
    static final int PANEL_PAD_BOTTOM = 18;
    /** Collapsed bar: the same header inside 10px of vertical padding. */
    static final int PANEL_PAD_COLLAPSED_H = 18;
    static final int PANEL_PAD_COLLAPSED_V = 10;
    /** 72 = 10 + 52 + 10, the whole collapsed status bar. */
    static final int COLLAPSED_H = PANEL_PAD_COLLAPSED_V * 2 + 52;
    /** Anchor of the panel inside the stage: 24px from the top edge (avoid the corner icons). */
    static final int ANCHOR_TOP = 24;

    /* Header. */
    static final int HEADER_H = 52;
    static final int HEADER_GAP = 14;
    static final int HEADER_INSET = 2;
    static final int DOT = 14;
    static final int DOT_HALO = 24;
    static final int PILL_PAD_H = 16;
    static final int PILL_PAD_V = 6;
    static final int CHEVRON = 22;
    static final int CHEVRON_GAP = 4;

    /* Segmented control. */
    static final int TABS_MARGIN_TOP = 12;
    static final int TABS_MARGIN_BOTTOM = 14;
    static final int TABS_H = 58;
    static final int TABS_PAD = 5;
    static final int TABS_RADIUS = 18;
    static final int TAB_RADIUS = 13;
    static final int TAB_GAP = 4;

    /* Rows. */
    static final int ROW_H = 84;
    static final int ROW_H_STATUS = 64;
    static final int ROW_RADIUS = 18;
    static final int ROW_GAP = 8;
    static final int ROW_PAD_START = 24;
    static final int ROW_PAD_END = 20;
    static final int ROW_INNER_GAP = 12;
    /** Rows slide up by this much while they fade in on expand. */
    static final int ROW_TRANSLATE = 8;

    /* Switch. */
    static final int TOGGLE_W = 78;
    static final int TOGGLE_H = 44;
    static final int TOGGLE_THUMB = 36;
    static final int TOGGLE_MARGIN = 4;
    /**
     * Invisible horizontal padding added to each side of the switch's *touch* box. The drawn
     * track keeps its design size; only the tap area grows, exactly like the handle's lip.
     */
    static final int TOGGLE_TOUCH_PAD = 10;

    /* Value row (opponent names) and the ⓘ of a setting row. */
    static final int CHIP_PAD_H = 16;
    static final int CHIP_PAD_V = 6;
    static final int CHIP_RADIUS = 12;
    static final int INFO = 24;
    /** Segmented cell of the inline naming settings, tall enough to tap. */
    static final int CELL_H = 52;
    /** Editor field inside the inline naming settings. */
    static final int FIELD_H = 52;

    /* ------------------------------------------------------------- typography */

    static final float TEXT_TITLE = 30;
    static final float TEXT_TAB = 24;
    static final float TEXT_PILL = 20;
    static final float TEXT_ROW_TITLE = 28;
    static final float TEXT_ROW_DESC = 20;
    static final float TEXT_CHIP = 22;
    static final float TEXT_STATUS_KEY = 24;
    static final float TEXT_STATUS_VALUE = 26;
    static final float TEXT_INFO = 15;
    /** The title tracks its letters by 6% in the design (TextView: ems). */
    static final float LETTER_SPACING = .06f;

    /* ----------------------------------------------------------------- motion */

    static final long MS_EXPAND = 280L;
    static final long MS_COLLAPSE = 220L;
    static final long MS_CONTENT_IN = 200L;
    static final long MS_CONTENT_IN_DELAY = 80L;
    static final long MS_CONTENT_OUT = 120L;
    static final long MS_ROW_STAGGER = 30L;
    static final long MS_TOGGLE = 180L;
    static final long MS_TAB = 220L;
    static final long MS_TAB_CONTENT = 160L;

    /** cubic-bezier(.2,.8,.2,1) — the kit's "expand". */
    static final float[] EASE_EXPAND = {.2f, .8f, .2f, 1f};
    /** cubic-bezier(.3,0,.8,.15) — the kit's "collapse": quicker, settles late. */
    static final float[] EASE_COLLAPSE = {.3f, 0f, .8f, .15f};
    /** cubic-bezier(.2,0,0,1) — the kit's "standard", used by fades and the switch. */
    static final float[] EASE_STANDARD = {.2f, 0f, 0f, 1f};

    /**
     * Evaluates a CSS cubic-bezier easing at {@code t} (0..1).
     *
     * <p>The curve is a parametric bezier over (x, y) with the two control points; CSS
     * easing is defined as {@code y(x)}, so the x parameter has to be solved first. 24
     * bisection steps are ~1e-7 of the curve: far below one pixel of travel, and cheaper
     * than Newton with a derivative guard.
     */
    static float ease(float[] bezier, float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float x1 = bezier[0], y1 = bezier[1], x2 = bezier[2], y2 = bezier[3];
        float low = 0f, high = 1f, u = t;
        for (int i = 0; i < 24; i++) {
            u = (low + high) / 2f;
            float x = bezierAxis(u, x1, x2);
            if (x < t) low = u;
            else high = u;
        }
        return bezierAxis(u, y1, y2);
    }

    /** {@code value} limited to 0..1; used by the per-row fade timeline. */
    static float clamp(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    /** One axis of a cubic bezier with endpoints 0 and 1. */
    private static float bezierAxis(float u, float p1, float p2) {
        float v = 1f - u;
        return 3f * v * v * u * p1 + 3f * v * u * u * p2 + u * u * u;
    }

    /**
     * The system "animator duration scale" (Settings > Developer options > Animation scale).
     * 0 means animations are switched off, which is what the kit's README promises the
     * panel honours. A failed read is treated as 1 (animate): a missing setting must never
     * disable the panel's own motion.
     */
    static float animatorScale(Context context) {
        try {
            return Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (RuntimeException | LinkageError ignored) {
            return 1f;
        }
    }

    /**
     * True when this view can run a frame animation right now.
     *
     * <p>{@code getHandler() != null} is the platform's own answer to "am I attached to a
     * window": a detached view has no handler, and posting frames to it would leave the
     * panel half-animated forever. That also keeps the JVM layout probes deterministic:
     * they build the tree without a window, so every geometry they measure is the *final*
     * geometry, never an animation frame.
     */
    static boolean canAnimate(View view) {
        if (view == null || !view.isAttachedToWindow() || view.getHandler() == null) return false;
        return animatorScale(view.getContext()) > 0f;
    }

    /* ----------------------------------------------------------------- colours */

    /* The panel is violet-tinted, not navy: 88%/86% opaque so the table stays visible. */
    static final int PANEL_BG_TOP = 0xE01A1634;
    static final int PANEL_BG_BOTTOM = 0xDB0E1022;
    static final int PANEL_STROKE_COLOR = 0x52F5A830;

    static final int INK = 0xFFF3EEFC;
    static final int INK_KEY = 0xB2F3EEFC;
    static final int INK_TAB_OFF = 0x99F3EEFC;
    static final int INK_SECONDARY = 0x8CF3EEFC;
    static final int INK_OFF = 0x73F3EEFC;

    static final int ROW_BG = 0x0DFFFFFF;
    static final int TAB_CONTAINER_BG = 0x0FFFFFFF;
    static final int CHIP_BG = 0x17FFFFFF;
    static final int TRACK_OFF = 0x24FFFFFF;
    static final int THUMB = 0xFFFFFFFF;

    static final int AMBER_LIGHT = 0xFFFFC257;
    static final int AMBER_DARK = 0xFFF19A1C;
    static final int AMBER_TEXT = 0xFFFFC468;
    static final int ON_AMBER = 0xFF2A1A00;
    static final int AMBER_PILL_BG = 0x24F5A830;
    /** Ripple of every pressable surface: the accent at 20%. */
    static final int RIPPLE = 0x33F5A830;

    static final int MINT = 0xFF5FE0A8;
    /** Failure copy. The design has no error colour; this is the shipped red, kept. */
    static final int DANGER = 0xFFE04848;

    /**
     * Window reserve, in pre-scale px: the window minus the panel's top anchor and a small
     * breathing gap. The panel is always measured against this *and* against whatever box
     * its parent hands down, whichever is smaller.
     */
    static int windowReserve(Context context, int windowHeightPx) {
        int used = px(context, ANCHOR_TOP) + px(context, 8);
        return Math.max(0, Math.round((windowHeightPx - used) / HOST_SCALE));
    }

    /* ----------------------------------------------------------------- motion */

    /**
     * Frame driver of the fold animation: one interpolated value, posted frame by frame,
     * with a hard "snap to the last frame" cancel.
     *
     * <p>The kit uses {@code ValueAnimator} and {@code ViewPropertyAnimator}; neither is used
     * here. Both animate *views*, and the panel folds by changing the padding and the band
     * height of a LinearLayout — a layout animation. A plain posted frame loop is also the
     * only driver that is safe in this process: it has no global animator queue to leak into,
     * it stops the moment its host is detached ({@code post} then never runs, and
     * {@link #cancel()} snaps the final frame), and it costs one allocation per fold.
     *
     * <p>{@link #cancel()} is the generation guard: it runs the last frame and the completion
     * callback exactly once, so a fold that is interrupted by a tap, by a language rebuild or
     * by teardown can never leave the panel half-open.
     */
    static final class Motion {
        /** Receives the eased 0..1 value of every frame, including the final snap. */
        interface Frame {
            void frame(float eased);
        }

        private final View host;
        private final Runnable tick = new Runnable() {
            @Override public void run() { step(); }
        };
        private boolean running;
        private long startAt;
        private long duration;
        private float[] easing;
        private Frame frame;
        private Runnable end;

        Motion(View host) { this.host = host; }

        boolean running() { return running; }

        /**
         * Runs {@code onFrame} from the current time for {@code durationMs}, then calls
         * {@code onEnd} once. A frame still in flight is snapped first, so two folds can
         * never interleave.
         */
        void start(long durationMs, float[] easing, Frame onFrame, Runnable onEnd) {
            cancel();
            if (host == null || onFrame == null) {
                if (onEnd != null) onEnd.run();
                return;
            }
            this.duration = Math.max(1L, durationMs);
            this.easing = easing == null ? GameTopDesign.EASE_STANDARD : easing;
            this.frame = onFrame;
            this.end = onEnd;
            this.startAt = SystemClock.uptimeMillis();
            this.running = true;
            host.post(tick);
        }

        /** Jumps to the last frame and drops the pending callback. Safe to call twice. */
        void cancel() {
            if (!running) return;
            running = false;
            host.removeCallbacks(tick);
            Frame last = frame;
            Runnable done = end;
            frame = null;
            end = null;
            if (last != null) last.frame(1f);
            if (done != null) done.run();
        }

        private void step() {
            if (!running) return;
            Frame last = frame;
            Runnable done = end;
            float raw = (SystemClock.uptimeMillis() - startAt) / (float) duration;
            if (raw >= 1f || !host.isAttachedToWindow()) {
                running = false;
                frame = null;
                end = null;
                if (last != null) last.frame(1f);
                if (done != null) done.run();
                return;
            }
            if (last != null) last.frame(GameTopDesign.ease(easing, raw));
            host.post(tick);
        }
    }
}
