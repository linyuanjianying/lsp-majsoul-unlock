package com.yefeng.majmax.hookprobe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Lightweight native theming for the in-game control. Everything here is drawn with
 * plain Android views and GradientDrawables: no resources, no Compose, no Bitmap or
 * blur behind the window. The "frosted" look is simulated with a translucent white
 * fill plus a thin outline and elevation, which is what actually works inside another
 * app's window on every Android version this build supports.
 *
 * The class is package private and only used by {@link GameTopControl}; it never
 * touches the provider, the polling or the manager app.
 */
final class GameTopUi {
    private GameTopUi() {}

    /* Palette: light, blue-accented, readable over bright and dark game scenes. */
    static final int INK = 0xFF1C2A3A;
    static final int INK_MUTED = 0xFF5B6B7C;
    static final int INK_FAINT = 0xFF8A99A8;
    static final int ACCENT = 0xFF2F6FE4;
    static final int ACCENT_WASH = 0x1A2F6FE4;
    static final int DIVIDER = 0x402F6FE4;
    static final int CARD = 0xCCFFFFFF;
    static final int CARD_PRESSED = 0xFFF3F7FD;
    static final int DANGER = 0xFFE04848;
    static final int DANGER_WASH = 0x14E04848;
    static final int TRACK_ON = ACCENT;
    static final int TRACK_OFF = 0xFFD3DBE4;
    static final int KNOB = 0xFFFFFFFF;
    static final int SHADOW = 0x1F1C2A3A;

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

    static GradientDrawable round(Context context, float radius, int color, int strokeColor,
                                  int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(context, radius));
        drawable.setColor(color);
        if (strokeWidthDp > 0) drawable.setStroke(dp(context, strokeWidthDp), strokeColor);
        return drawable;
    }

    /** Translucent inner card: white fill, hairline blue outline. */
    static GradientDrawable card(Context context) {
        return round(context, 14, CARD, 0x262F6FE4, 1);
    }

    /** Selection capsule (sidebar). */
    static GradientDrawable accentPill(Context context) {
        return round(context, 12, ACCENT, 0, 0);
    }

    /** Unselected capsule (sidebar). */
    static GradientDrawable quietPill(Context context) {
        return round(context, 12, 0xE6FFFFFF, 0x1F1C2A3A, 1);
    }

    static GradientDrawable dangerPill(Context context) {
        return round(context, 12, DANGER_WASH, 0x55E04848, 1);
    }

    /** Panel surface with a faint shadow. The caller sets elevation separately. */
    static GradientDrawable panel(Context context) {
        return round(context, 22, 0xF2FFFFFF, 0x332F6FE4, 1);
    }

    /** 1dp horizontal hairline used as separator between menu sections. */
    static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1)));
        params.topMargin = dp(context, 10);
        params.bottomMargin = dp(context, 10);
        line.setLayoutParams(params);
        return line;
    }

    /* --------------------------------------------------------------- text helpers *\/ */

    static android.widget.TextView text(Context context, float sp, int color, boolean bold) {
        android.widget.TextView view = new android.widget.TextView(context);
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

    /** Transparent, focusless ripple: the shape comes from the background drawable. */
    static void pressable(View view, Runnable onClick) {
        view.setClickable(true);
        view.setFocusable(false);
        view.setOnClickListener(ignored -> onClick.run());
    }

    /* ------------------------------------------------------------------- toggle *\/ */

    /**
     * Two-state switch drawn by hand: a rounded track plus a white knob, animated by
     * a single view property so no animator or XML shape resource is required.
     * {@code setOn} only changes the visuals; taps are handled by the owner.
     */
    static final class Switch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final float height;
        /** Width of the tappable box; the track is painted {@code overlap} wider. */
        private final float width;
        private final float overlap;
        private float position;
        private boolean on;
        private boolean busy;

        Switch(Context context) {
            super(context);
            height = dp(context, 22);
            width = dp(context, 36);
            overlap = dp(context, 4);
            setLayoutParams(new ViewGroup.LayoutParams((int) width, (int) height));
            setContentDescription("开关");
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension((int) width, (int) height);
        }

        void setOn(boolean value, boolean animate) {
            if (on == value && !animate) {
                position = value ? 1f : 0f;
                invalidate();
                return;
            }
            on = value;
            float target = value ? 1f : 0f;
            if (!animate || !isAttachedToWindow()) {
                position = target;
                invalidate();
                return;
            }
            // Local, self-cancelling knob animation on the view's own message queue;
            // no animator object has to be tracked or cancelled by the owner.
            animateTo(target);
        }

        void setBusy(boolean value) {
            if (busy == value) return;
            busy = value;
            setAlpha(value ? .55f : 1f);
        }

        private void animateTo(float target) {
            final long start = android.os.SystemClock.uptimeMillis();
            final float from = position;
            final float delta = target - from;
            post(new Runnable() {
                private int guard;
                @Override public void run() {
                    float t = Math.min(1f, (android.os.SystemClock.uptimeMillis() - start) / 140f);
                    position = from + delta * (1f - (1f - t) * (1f - t));
                    invalidate();
                    if (t < 1f && guard++ < 30) post(this);
                }
            });
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float pad = dp(getContext(), 1);
            // The track is painted wider than its layout box and overlaps the card
            // padding to the right; that strip is never hit-tested, so the card's own tap
            // area owns everything left of the track.
            float right = Math.min(getWidth() + overlap, getParent() == null
                ? getWidth() + overlap : ((View) getParent()).getWidth() - getLeft());
            oval.set(pad, pad, right, getHeight() - pad);
            float radius = oval.height() / 2f;
            int track = on ? TRACK_ON : TRACK_OFF;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(track);
            canvas.drawRoundRect(oval, radius, radius, paint);

            float knobR = radius - dp(getContext(), 2);
            float cx = oval.left + radius + (oval.width() - 2 * radius) * position;
            paint.setColor(KNOB);
            paint.setShadowLayer(dp(getContext(), 1), 0, dp(getContext(), .5f), 0x33000000);
            canvas.drawCircle(cx, oval.centerY(), knobR, paint);
            paint.clearShadowLayer();
            if (busy) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(getContext(), 1.4f));
                paint.setColor(0x8F2F6FE4);
                canvas.drawRoundRect(oval, radius, radius, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }

    /* -------------------------------------------------------------- drag handle *\/ */

    /**
     * Keeps the whole in-game control draggable without swallowing game touches: the
     * panel itself is a small wrap-content child of the game's decor view, and inside
     * it only this handle listens for touch events. The menu stays tap-only so a drag
     * can never be mistaken for a switch press. The resting position is clamped to the
     * current window size, which is re-read on every drag, so rotation is handled.
     */
    static final class DragController implements View.OnTouchListener {
        private final FrameLayout.LayoutParams params;
        private final View host;
        private final int slop;

        /** Called once per drag, after the user moved the handle past the touch slop. */
        private Runnable onDragged;

        private boolean dragging;
        private float downX;
        private float downY;
        private int startLeft;
        private int startTop;
        private FrameLayout parent;

        DragController(View host, FrameLayout.LayoutParams params) {
            this.host = host;
            this.params = params;
            this.slop = ViewConfiguration.get(host.getContext()).getScaledTouchSlop();
        }

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = false;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startLeft = params.leftMargin;
                    startTop = params.topMargin;
                    view.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) < slop) return true;
                    if (!dragging) {
                        dragging = true;
                        view.setPressed(false);
                        if (onDragged != null) onDragged.run();
                    }
                    place(startLeft + Math.round(dx), startTop + Math.round(dy));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    view.setPressed(false);
                    if (!dragging) view.performClick();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        }

        void setOnDragged(Runnable callback) { onDragged = callback; }

        /** Applies a position in decor coordinates, clamped to the visible window. */
        private void place(int left, int top) {
            if (parent == null) parent = findParent();
            int maxX = parent == null ? Integer.MAX_VALUE : parent.getWidth() - host.getWidth();
            int maxY = parent == null ? Integer.MAX_VALUE : parent.getHeight() - host.getHeight();
            params.leftMargin = Math.max(0, maxX <= 0 ? 0 : Math.min(left, maxX));
            params.topMargin = Math.max(0, maxY <= 0 ? 0 : Math.min(top, maxY));
            params.gravity = Gravity.TOP | Gravity.START;
            host.setLayoutParams(params);
        }

        private FrameLayout findParent() {
            ViewGroup group = host.getParent() instanceof ViewGroup ? (ViewGroup) host.getParent() : null;
            return group instanceof FrameLayout ? (FrameLayout) group : null;
        }

        /**
         * Called after the panel changed size (collapse/expand) or the window was
         * resized. {@code recentre} puts the control back top-centre, which is what an
         * untouched control wants; otherwise the user's position is only clamped so it
         * never ends up partly or fully outside the rotated window.
         */
        void reflow(final boolean recentre) {
            host.post(() -> {
                if (parent == null) parent = findParent();
                if (parent == null) return;
                int viewW = host.getWidth();
                int viewH = host.getHeight();
                if (viewW <= 0 || viewH <= 0) return;
                int maxX = Math.max(0, parent.getWidth() - viewW);
                int maxY = Math.max(0, parent.getHeight() - viewH);
                if (recentre) {
                    params.leftMargin = Math.max(0, maxX / 2);
                    params.topMargin = Math.max(0, dp(host.getContext(), 6));
                } else {
                    params.leftMargin = Math.min(Math.max(0, params.leftMargin), maxX);
                    params.topMargin = Math.min(Math.max(0, params.topMargin), maxY);
                }
                params.gravity = Gravity.TOP | Gravity.START;
                host.setLayoutParams(params);
            });
        }
    }

    /** Small coloured dot used to show the live state next to the title. */
    static View dot(Context context, int color) {
        View view = new View(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        view.setBackground(shape);
        int size = dp(context, 8);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.gravity = Gravity.CENTER_VERTICAL;
        params.rightMargin = dp(context, 6);
        view.setLayoutParams(params);
        return view;
    }

    static int withAlpha(int color, float alpha) {
        int a = Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
