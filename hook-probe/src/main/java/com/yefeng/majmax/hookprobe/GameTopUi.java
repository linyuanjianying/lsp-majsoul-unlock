package com.yefeng.majmax.hookprobe;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Native theming for the in-game control: the violet glass panel of the redesigned menu, its
 * rows, the segmented tab, the amber switch and the two hand-drawn header marks (the top
 * handle and the live state dot).
 *
 * <p>Everything here is plain Android views, GradientDrawables and Canvas paths. No resource,
 * no Compose, no bitmap and no blur: the control is a child of the game's own decor view, so
 * only what Android can draw everywhere is used, and the game's process has no theme, no
 * resource ids and no inflater for this package. Every icon is stroked as a vector path
 * instead of being set as a glyph, because the emoji / symbol fonts (▦ ▾ › ⓘ) are missing or
 * replaced on a fair share of devices and a missing glyph leaves an empty box where the
 * chevron should be.
 *
 * <p>Sizes come from {@link GameTopDesign}: the panel is designed in the pixels of a
 * 2376x1080 stage, and {@code px()} turns a design value into the panel's own pre-scale
 * pixels, so what the finger sees is the design. Colours are the design's violet/amber/mint
 * palette, <em>except</em> for {@link HandleView}, which keeps the shipped deep-blue/gold
 * marks it has always drawn: the resting handle was explicitly left alone.
 *
 * <p>The class is package private and only used by {@link GameTopControl}; it never touches
 * the provider, the polling or the manager app.
 */
final class GameTopUi {
    private GameTopUi() {}

    /* ------------------------------------------------------------ handle palette */

    /*
     * The handle's own palette. Deliberately unchanged: the floating lip is not part of the
     * redesign, so its gold hairline, its gold chevron and its state dot keep the exact
     * values they shipped with.
     */
    /** Gold outline of the handle. */
    static final int BORDER = 0xFFB68A48;
    /** Selected/shipped gold; also the handle's chevron. */
    static final int GOLD = 0xFFF6AD35;
    static final int ICON_GOLD = GOLD;
    /** Live state of the handle: the shipped green/amber, not the design's mint. */
    static final int STATE_GREEN = 0xFF43D07C;
    static final int STATE_AMBER = 0xFFE2A93B;
    /** Failure copy. Shared with the panel (the design has no error colour). */
    static final int DANGER = GameTopDesign.DANGER;
    /** Hairline of the handle's own outline, in px. */
    private static final int STROKE = 1;

    /* ------------------------------------------------------------------ helpers */

    static float density(Context context) {
        float value = context.getResources().getDisplayMetrics().density;
        return value <= 0f ? 1f : value;
    }

    static int dp(Context context, float value) {
        return (int) (value * density(context) + .5f);
    }

    static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    /** Rounded shape from dp values (the handle still measures itself in dp). */
    static GradientDrawable round(Context context, float radius, int color, int strokeColor,
                                  int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(context, radius));
        drawable.setColor(color);
        if (strokeWidthDp > 0) drawable.setStroke(dp(context, strokeWidthDp), strokeColor);
        return drawable;
    }

    /** Solid rounded shape from *design* px ({@link GameTopDesign}). */
    static GradientDrawable solid(Context context, float radiusDesignPx, int color) {
        return shape(context, radiusDesignPx, color, 0, 0f);
    }

    /** Solid rounded shape with an optional design-px hairline. */
    static GradientDrawable shape(Context context, float radiusDesignPx, int color,
                                  int strokeColor, float strokeDesignPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(GameTopDesign.pxFloat(context, radiusDesignPx));
        drawable.setColor(color);
        if (strokeDesignPx > 0f) {
            drawable.setStroke(GameTopDesign.strokePx(context, strokeDesignPx), strokeColor);
        }
        return drawable;
    }

    /** Vertical two-stop gradient, the design's accent and panel surface. */
    static GradientDrawable gradient(Context context, float radiusDesignPx, int top, int bottom) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(GameTopDesign.pxFloat(context, radiusDesignPx));
        drawable.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        drawable.setColors(new int[]{top, bottom});
        drawable.setColor(top);
        return drawable;
    }

    /** Ripple-tinted pressable background for an already shaped drawable. */
    static void pressable(View view, GradientDrawable shape) {
        if (view == null || shape == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.setBackground(new RippleDrawable(
                ColorStateList.valueOf(GameTopDesign.RIPPLE), shape, null));
        } else {
            view.setBackground(shape);
        }
    }

    /* ------------------------------------------------------------- text helpers */

    static TextView text(Context context, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold || Build.VERSION.SDK_INT < 28) {
            view.setTypeface(bold ? medium() : Typeface.SANS_SERIF);
        } else {
            view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        view.setIncludeFontPadding(false);
        return view;
    }

    /** Label whose size is a design px value (see {@link GameTopDesign#sp}). */
    static TextView label(Context context, float designPx, int color, boolean bold) {
        return text(context, GameTopDesign.sp(context, designPx), color, bold);
    }

    static int withAlpha(int color, float alpha) {
        int a = Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /* --------------------------------------------------------------- surfaces */

    /** The panel itself: violet glass, large radius, a single amber hairline. */
    static GradientDrawable panel(Context context) {
        GradientDrawable drawable = gradient(context, GameTopDesign.PANEL_RADIUS,
            GameTopDesign.PANEL_BG_TOP, GameTopDesign.PANEL_BG_BOTTOM);
        drawable.setStroke(GameTopDesign.strokePx(context, GameTopDesign.PANEL_STROKE),
            GameTopDesign.PANEL_STROKE_COLOR);
        return drawable;
    }

    /** One row inside the panel: white at 5%, the design's "row" surface. */
    static GradientDrawable row(Context context) {
        return solid(context, GameTopDesign.ROW_RADIUS, GameTopDesign.ROW_BG);
    }

    /** The segmented container behind the two tabs. */
    static GradientDrawable tabBand(Context context) {
        return solid(context, GameTopDesign.TABS_RADIUS, GameTopDesign.TAB_CONTAINER_BG);
    }

    /** The selected tab: the amber gradient with the dark ink on top. */
    static GradientDrawable tabActive(Context context) {
        return gradient(context, GameTopDesign.TAB_RADIUS,
            GameTopDesign.AMBER_LIGHT, GameTopDesign.AMBER_DARK);
    }

    /** The unselected tab: no fill at all, it sits in the segmented container. */
    static GradientDrawable tabIdle(Context context) {
        return solid(context, GameTopDesign.TAB_RADIUS, Color.TRANSPARENT);
    }

    /** The value pill of a value row ("A·B·C"), and the inline editor field. */
    static GradientDrawable chip(Context context) {
        return solid(context, GameTopDesign.CHIP_RADIUS, GameTopDesign.CHIP_BG);
    }

    /** Header run-state pill: amber text on amber at 14%. */
    static GradientDrawable statePill(Context context) {
        return solid(context, GameTopDesign.PILL_PAD_H * 62f, GameTopDesign.AMBER_PILL_BG);
    }

    /** One cell of the inline naming segmented control. */
    static GradientDrawable cell(Context context, boolean selected) {
        return selected
            ? gradient(context, GameTopDesign.CHIP_RADIUS,
                GameTopDesign.AMBER_LIGHT, GameTopDesign.AMBER_DARK)
            : solid(context, GameTopDesign.CHIP_RADIUS, GameTopDesign.CHIP_BG);
    }

    /** The primary action of the status page: the amber gradient, full row width. */
    static GradientDrawable primary(Context context) {
        return gradient(context, GameTopDesign.ROW_RADIUS,
            GameTopDesign.AMBER_LIGHT, GameTopDesign.AMBER_DARK);
    }

    /** Text field of the inline naming settings: quiet fill, no outline. */
    static GradientDrawable field(Context context) {
        return solid(context, 8, GameTopDesign.CHIP_BG);
    }

    /* ------------------------------------------------------------------ icons */

    /** Which vector icon a {@link Glyph} draws. No glyph font is involved. */
    static final int ICON_GRID = 0;
    static final int ICON_CHEVRON_DOWN = 1;
    static final int ICON_CHEVRON_UP = 2;
    static final int ICON_INFO = 3;
    static final int ICON_CHEVRON_RIGHT = 4;

    /**
     * A small vector icon painted on a Canvas: the four-square menu grid, the up / down /
     * right chevron and the ⓘ mark. Every path is scaled into the view's own box, so the same
     * view keeps its look in every density and in every language.
     *
     * <p>The box is given in the panel's pre-scale pixels ({@link GameTopDesign#px}), not in
     * dp: the redesign sizes its marks in design pixels like everything else.
     */
    static final class Glyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF box = new RectF();
        private final int kind;
        private final float stroke;
        /** Nominal box, kept for {@link #onMeasure} when no explicit box is set. */
        private final int sizePx;

        Glyph(Context context, int kind, int color, int sizePx, float strokePx) {
            super(context);
            this.kind = kind;
            this.sizePx = sizePx;
            this.stroke = strokePx;
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setLayoutParams(new ViewGroup.LayoutParams(sizePx, sizePx));
        }

        void setColor(int color) {
            if (paint.getColor() == color) return;
            paint.setColor(color);
            invalidate();
        }

        /**
         * Square box of {@code sizePx}, unless the caller laid a tighter box on it.
         *
         * <p>It used to trust only {@code params.width/height > 0}, so a caller that
         * passed its own small box through the LINEAR layout params it was handed came back
         * at the nominal size too — except it measured 0 and pushed the whole card taller.
         * Reading the minimum size as well keeps the requested box no matter which way the
         * parent expressed it.
         */
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            ViewGroup.LayoutParams params = getLayoutParams();
            int wantW = params != null && params.width > 0
                ? params.width : Math.max(sizePx, getSuggestedMinimumWidth());
            int wantH = params != null && params.height > 0
                ? params.height : Math.max(sizePx, getSuggestedMinimumHeight());
            setMeasuredDimension(resolveSize(wantW, widthSpec), resolveSize(wantH, heightSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            switch (kind) {
                case ICON_GRID: {
                    // 2x2 squares: rounded, stroked, evenly spaced inside the box.
                    float side = Math.min(w, h);
                    float gap = side * .18f;
                    float cell = (side - gap) / 2f;
                    float left = (w - side) / 2f;
                    float top = (h - side) / 2f;
                    float radius = cell * .26f;
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(stroke);
                    box.set(left, top, left + cell, top + cell);
                    canvas.drawRoundRect(box, radius, radius, paint);
                    box.offset(cell + gap, 0);
                    canvas.drawRoundRect(box, radius, radius, paint);
                    box.offset(0, cell + gap);
                    canvas.drawRoundRect(box, radius, radius, paint);
                    box.offset(-(cell + gap), 0);
                    canvas.drawRoundRect(box, radius, radius, paint);
                    break;
                }
                case ICON_CHEVRON_DOWN:
                case ICON_CHEVRON_UP: {
                    float half = Math.min(w, h) * .20f;
                    float y = getHeight() / 2f;
                    float drop = half * .62f;
                    path.reset();
                    if (kind == ICON_CHEVRON_DOWN) {
                        path.moveTo(w / 2f - half, y - drop / 2f);
                        path.lineTo(w / 2f, y + drop / 2f);
                        path.lineTo(w / 2f + half, y - drop / 2f);
                    } else {
                        path.moveTo(w / 2f - half, y + drop / 2f);
                        path.lineTo(w / 2f, y - drop / 2f);
                        path.lineTo(w / 2f + half, y + drop / 2f);
                    }
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(stroke);
                    canvas.drawPath(path, paint);
                    break;
                }
                case ICON_CHEVRON_RIGHT: {
                    float half = Math.min(w, h) * .20f;
                    float x = w / 2f;
                    float drop = half * .62f;
                    path.reset();
                    path.moveTo(x - drop / 2f, h / 2f - half);
                    path.lineTo(x + drop / 2f, h / 2f);
                    path.lineTo(x - drop / 2f, h / 2f + half);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(stroke);
                    canvas.drawPath(path, paint);
                    break;
                }
                case ICON_INFO:
                default: {
                    float r = Math.min(w, h) / 2f - stroke;
                    float cx = w / 2f;
                    float cy = h / 2f;
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(stroke);
                    canvas.drawCircle(cx, cy, r, paint);
                    paint.setStyle(Paint.Style.FILL);
                    float dotR = Math.max(stroke * .9f, r * .13f);
                    canvas.drawCircle(cx, cy - r * .42f, dotR, paint);
                    float barTop = cy - r * .12f;
                    float barBottom = cy + r * .48f;
                    paint.setStrokeWidth(Math.max(stroke, dotR * 1.7f));
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(cx, barTop, cx, barBottom, paint);
                    break;
                }
            }
        }
    }

    /* ------------------------------------------------------------- the handle */

    /* ------------------------------------------------------------- the handle */

    /**
     * The resting entry at the top edge: a shallow U-shaped tab that hangs from the screen
     * edge, not a vertical pill.
     *
     * <p>Two boxes, and only one of them is painted:
     *
     * <ul>
     *   <li>{@link #WIDTH_DP} x {@link #VISIBLE_DP} (84x30dp, 231x83px at the reporter's
     *       2.75 density) is the visible body: a dark, translucent deep-blue U with a gold
     *       hairline, its square top edge sitting flush on the screen's bottom edge when the
     *       window bar is not drawn, and only its rounded lower lip on the table.</li>
     *   <li>{@link #TOUCH_WIDTH_DP} x {@link #TOUCH_DP} (108x44dp) is the touch box. The 12dp
     *       on each side and the 14dp below the lip are padding: they take the tap, they are
     *       never filled, and no background is painted behind them.</li>
     * </ul>
     *
     * <p>Inside the lip one row carries everything: the live state dot on the left, the
     * gold down chevron on the right. The dot used to sit above the chevron in a 26dp-wide
     * pill, which read as two stacked marks; they now share one horizontal band.
     *
     * <p>The dashed line of the design mock-up is documentation only: nothing dashed is
     * drawn here.
     */
    static final class HandleView extends View {
        /** Visible body width: the wide, flat tab of the design. */
        static final int WIDTH_DP = 84;
        /** Visible height of the lip below the top edge. */
        static final int VISIBLE_DP = 30;
        /** Touch box height: the lip plus 14dp of invisible padding below it. */
        static final int TOUCH_DP = 44;
        /** Touch box width: the body plus 12dp of invisible padding on each side. */
        static final int TOUCH_WIDTH_DP = 108;

        private final GradientDrawable body;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path chevron = new Path();
        private int stateColor = STATE_AMBER;

        HandleView(Context context) {
            super(context);
            // Shallow lip: an 11dp radius on a 30dp body, so it reads as a wide U and not
            // as a round button. The body is translucent so the table stays visible behind.
            body = round(context, 11, 0xCC111A2C, BORDER, STROKE);
            setClickable(true);
            setFocusable(false);
        }

        void setStateColor(int color) {
            if (stateColor == color) return;
            stateColor = color;
            invalidate();
        }

        int stateColor() { return stateColor; }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = Math.max(dp(getContext(), TOUCH_WIDTH_DP), dp(getContext(), WIDTH_DP));
            int height = dp(getContext(), TOUCH_DP);
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) return;
            // The visible lip is centred in the touch box; the extra width either side is
            // touch area only and is never filled or outlined.
            int lipWidth = Math.min(width, dp(getContext(), WIDTH_DP));
            int lipHeight = Math.min(height, dp(getContext(), VISIBLE_DP));
            int lipLeft = (width - lipWidth) / 2;
            // The body starts above the viewport: only the rounded lower part of the U shows.
            // Only a little over two lip heights are hidden: enough that the upper corners
            // (11dp radius) are off-screen, shallow enough that the lip reads as a tab and not
            // as a tall hook — and it keeps the dot/chevron pair centred on the visible band.
            int above = Math.max(lipHeight * 2, dp(getContext(), 76));
            body.setBounds(lipLeft, lipHeight - above, lipLeft + lipWidth, lipHeight);
            body.draw(canvas);

            float centerX = width / 2f;
            float stroke = Math.max(dp(getContext(), 1.5f), lipHeight * .055f);
            float lipMidY = lipHeight / 2f;
            // One row: dot on the left, chevron on the right, both centred on the lip.
            // Each mark is ~17dp from the lip's centre: a symmetric gap that keeps the pair
            // as one row inside the 84dp lip, away from the rounded ends where the marks
            // would look glued to the corners (~96px of a 231px lip, dot and chevron alike).
            float gap = Math.max(dp(getContext(), 17), lipWidth * .20f);
            float radius = Math.max(dp(getContext(), 3.2f), lipHeight * .115f);
            float dotX = centerX - gap;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(stateColor);
            canvas.drawCircle(dotX, lipMidY, radius, paint);

            float half = Math.max(dp(getContext(), 4.6f), lipHeight * .175f);
            float chevronX = centerX + gap;
            float drop = half * .62f;
            chevron.reset();
            chevron.moveTo(chevronX - half, lipMidY - drop / 2f);
            chevron.lineTo(chevronX, lipMidY + drop / 2f);
            chevron.lineTo(chevronX + half, lipMidY - drop / 2f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(ICON_GOLD);
            canvas.drawPath(chevron, paint);
        }

        @Override public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            setAlpha(pressed ? .7f : 1f);
        }
    }

    /* --------------------------------------------------------- the state dot */

    /**
     * The live state mark of the panel header: a 14px dot inside a 24px halo of its own
     * colour at 18%, exactly the kit's {@code assistant_status_dot} layer-list — drawn by
     * hand because two stacked ovals in one drawable would need a LayerDrawable and the
     * halo has to be recoloured with the dot.
     *
     * <p>The mark is a square box of {@link GameTopDesign#DOT_HALO} design px; the extra
     * 10px around the dot are the halo, not padding, so nothing else can occupy them.
     */
    static final class StatusDot extends View {
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int box;

        StatusDot(Context context) {
            super(context);
            box = GameTopDesign.px(context, GameTopDesign.DOT_HALO);
            setColor(STATE_AMBER);
            setLayoutParams(new ViewGroup.LayoutParams(box, box));
        }

        void setColor(int color) {
            if (core.getColor() == color) return;
            core.setColor(color);
            // The halo is the same state colour at 18%, so a state change never leaves the
            // glow of the previous state around the new dot.
            halo.setColor(withAlpha(color, .18f));
            invalidate();
        }

        int color() { return core.getColor(); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(resolveSize(box, widthSpec), resolveSize(box, heightSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w / 2f;
            float cy = h / 2f;
            float haloRadius = Math.min(w, h) / 2f;
            float coreRadius = haloRadius * (GameTopDesign.DOT / (float) GameTopDesign.DOT_HALO);
            canvas.drawCircle(cx, cy, haloRadius, halo);
            canvas.drawCircle(cx, cy, coreRadius, core);
        }
    }

    /* ----------------------------------------------------------------- toggle */

    /**
     * The switch of a setting row, drawn by hand: a 78x44px rounded track (amber gradient
     * when on, white at 14% when off) with a 36px white thumb that slides between 4px and
     * 38px, over {@link GameTopDesign#MS_TOGGLE} with the design's standard easing.
     *
     * <p>The view's box is larger than the track: 10px of invisible padding on each side and
     * the full row height vertically are tap area only and are never painted, the same trick
     * the handle uses. The track itself keeps the design's size, so the row looks exactly
     * like the hand-off while the finger gets a ~59x63 visible px target.
     *
     * <p>The thumb animates on the view's own queue and never needs an animator, so the owner
     * never has to cancel one: a state change just restarts the interpolation, and a detach
     * simply leaves the last drawn frame.
     */
    static final class Switch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private final GradientDrawable trackOn;
        private final GradientDrawable trackOff;
        /** Painted size of the track, pre-scale px. */
        private final int trackWidth;
        private final int trackHeight;
        private final int thumbSize;
        private final int margin;
        private final int padH;
        private float position;
        private boolean on;
        private boolean busy;
        /** Frame token: bumped on every state change so a stale frame cannot paint. */
        private int frame;

        Switch(Context context) {
            super(context);
            trackWidth = GameTopDesign.px(context, GameTopDesign.TOGGLE_W);
            trackHeight = GameTopDesign.px(context, GameTopDesign.TOGGLE_H);
            thumbSize = GameTopDesign.px(context, GameTopDesign.TOGGLE_THUMB);
            margin = GameTopDesign.px(context, GameTopDesign.TOGGLE_MARGIN);
            padH = GameTopDesign.px(context, GameTopDesign.TOGGLE_TOUCH_PAD);
            trackOn = gradient(context, GameTopDesign.TOGGLE_H / 2f,
                GameTopDesign.AMBER_LIGHT, GameTopDesign.AMBER_DARK);
            trackOff = solid(context, GameTopDesign.TOGGLE_H / 2f, GameTopDesign.TRACK_OFF);
            setLayoutParams(new ViewGroup.LayoutParams(trackWidth + 2 * padH, trackHeight));
            setContentDescription("开关");
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            ViewGroup.LayoutParams params = getLayoutParams();
            int wantW = Math.max(trackWidth + 2 * padH,
                params != null && params.width > 0 ? params.width : 0);
            int wantH = Math.max(trackHeight, params != null && params.height > 0 ? params.height : 0);
            setMeasuredDimension(resolveSize(wantW, widthSpec), resolveSize(wantH, heightSpec));
        }

        void setOn(boolean value, boolean animate) {
            boolean unchanged = on == value;
            on = value;
            float target = value ? 1f : 0f;
            // The 1 s poll repaints every switch: only a real change (or a frame still in
            // flight towards the same target) may start an interpolation.
            if (unchanged && position == target) return;
            if (!animate || !GameTopDesign.canAnimate(this)) {
                frame++;
                position = target;
                invalidate();
                return;
            }
            slideTo(target);
        }

        void setBusy(boolean value) {
            if (busy == value) return;
            busy = value;
            setAlpha(value ? .55f : 1f);
        }

        boolean isOn() { return on; }

        /** Current thumb position, 0 = off, 1 = on; used by the probes. */
        float position() { return position; }

        /**
         * Interpolates the thumb. The frame token is what makes this cancel-safe: any newer
         * call (a tap, a poll repaint, a rebuild) bumps it, and the older runnable stops
         * itself on its next frame instead of fighting the newer one.
         */
        private void slideTo(float target) {
            final int token = ++frame;
            final float from = position;
            final float delta = target - from;
            final long start = SystemClock.uptimeMillis();
            final long duration = Math.max(1L, GameTopDesign.MS_TOGGLE);
            post(new Runnable() {
                @Override public void run() {
                    if (token != frame) return;
                    float raw = (SystemClock.uptimeMillis() - start) / (float) duration;
                    if (raw >= 1f) {
                        position = target;
                        invalidate();
                        return;
                    }
                    position = from + delta * GameTopDesign.ease(GameTopDesign.EASE_STANDARD, raw);
                    invalidate();
                    post(this);
                }
            });
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float left = (w - trackWidth) / 2f;
            float top = (h - trackHeight) / 2f;
            track.set(left, top, left + trackWidth, top + trackHeight);
            GradientDrawable body = on ? trackOn : trackOff;
            body.setBounds((int) track.left, (int) track.top,
                (int) track.right, (int) track.bottom);
            body.draw(canvas);

            float radius = thumbSize / 2f;
            float travel = trackWidth - 2 * margin - thumbSize;
            float cx = track.left + margin + radius + travel * position;
            float cy = track.centerY();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(GameTopDesign.THUMB);
            paint.setShadowLayer(GameTopDesign.px(getContext(), 2), 0,
                GameTopDesign.px(getContext(), 1), 0x59000000);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.clearShadowLayer();
            if (busy) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, GameTopDesign.pxFloat(getContext(), 1.5f)));
                paint.setColor(withAlpha(GameTopDesign.AMBER_TEXT, .55f));
                canvas.drawRoundRect(track, track.height() / 2f, track.height() / 2f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }
}
