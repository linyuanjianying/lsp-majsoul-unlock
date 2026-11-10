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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Native theming for the in-game control: the deep-blue menu surface, its cards, the
 * gold selection capsule and the two hand-drawn header entries (the top handle and the
 * assistant capsule).
 *
 * <p>Everything here is plain Android views, GradientDrawables and Canvas paths. No
 * resource, no Compose, no bitmap and no blur: the control is a child of the game's own
 * decor view, so only what Android can draw everywhere is used. Every icon is stroked
 * as a vector path instead of being set as a glyph, because the emoji / symbol fonts
 * (▦ ▾ ⓘ) are missing or replaced on a fair share of devices and a missing glyph leaves
 * an empty box where the grid or the chevron should be.
 *
 * <p>The class is package private and only used by {@link GameTopControl}; it never
 * touches the provider, the polling or the manager app.
 */
final class GameTopUi {
    private GameTopUi() {}

    /* Palette: opaque deep blue surface, gold accent, cool-grey cards. */
    /** Menu / capsule surface: opaque so the table can never shine through the copy. */
    static final int SURFACE = 0xFF111A2C;
    /** Card fill: grey-blue, a step lighter than the surface. */
    static final int CARD = 0xFF20283A;
    static final int CARD_PRESSED = 0xFF273146;
    /** Gold outline of the panel, the handle and the capsule. */
    static final int BORDER = 0xFFB68A48;
    /** Card hairline: the same gold, much quieter. */
    static final int CARD_BORDER = 0x40B68A48;
    /** Selected tab fill and its dark label. */
    static final int GOLD = 0xFFF6AD35;
    static final int GOLD_PRESSED = 0xFFFFC65C;
    static final int INK_ON_GOLD = 0xFF17181C;
    /** Primary and secondary copy. */
    static final int INK = 0xFFE9EEF5;
    static final int INK_MUTED = 0xFFA9B5C6;
    static final int INK_FAINT = 0xFF7C889C;
    /** Live state dot of the handle / capsule / header. */
    static final int STATE_GREEN = 0xFF43D07C;
    static final int STATE_AMBER = 0xFFE2A93B;
    static final int DANGER = 0xFFE04848;
    static final int DIVIDER = 0x33B68A48;
    /** Toggle track colours. */
    static final int TRACK_ON = GOLD;
    static final int TRACK_OFF = 0xFF3A4457;
    static final int KNOB = 0xFFF4F7FB;
    /** Ink of the icons drawn on the surface. */
    static final int ICON = 0xFFE9EEF5;
    static final int ICON_GOLD = GOLD;
    static final int ICON_MUTED = 0xFF9AA6B8;

    /** Gold hairline used by the four-icon grid entry. */
    private static final int STROKE = 1;

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

    /* ------------------------------------------------------------- surfaces */

    /** The menu itself: opaque deep blue, large radius, thin gold outline. */
    static GradientDrawable panel(Context context) {
        return round(context, 20, SURFACE, BORDER, STROKE);
    }

    /** A card inside the menu: grey-blue fill with a quiet gold hairline. */
    static GradientDrawable card(Context context) {
        return card(context, false);
    }

    /** Card variant with the selected fill (used while a card is pressed). */
    static GradientDrawable card(Context context, boolean pressed) {
        return round(context, 14, pressed ? CARD_PRESSED : CARD, CARD_BORDER, STROKE);
    }

    /** Gold capsule for the selected two-part tab. */
    static GradientDrawable accentPill(Context context) {
        return round(context, 11, GOLD, 0, 0);
    }

    /** Quiet variant of the same capsule for the tab that is not selected. */
    static GradientDrawable quietPill(Context context) {
        return round(context, 11, CARD, CARD_BORDER, STROKE);
    }

    /** Outlined pill used by the header's run-state label. */
    static GradientDrawable statePill(Context context) {
        return round(context, 9, 0x1AB68A48, CARD_BORDER, STROKE);
    }

    /** Ripple-tinted pressable background for an already shaped drawable. */
    static void pressable(View view, GradientDrawable shape) {
        if (view == null || shape == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33F6AD35), shape, null));
        } else {
            view.setBackground(shape);
        }
    }

    /* ---------------------------------------------------------- text helpers */

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

    /** Small coloured dot that shows the live state next to a label. */
    static View dot(Context context, int color) {
        View view = new View(context);
        view.setBackground(dotShape(color));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            dp(context, 8), dp(context, 8));
        params.gravity = Gravity.CENTER_VERTICAL;
        params.rightMargin = dp(context, 6);
        view.setLayoutParams(params);
        return view;
    }

    /** Round shape of a state dot; repainted in place when the state changes. */
    static GradientDrawable dotShape(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    static void setDotColor(View dot, int color) {
        if (dot == null) return;
        if (dot.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) dot.getBackground()).setColor(color);
        } else {
            dot.setBackground(dotShape(color));
        }
    }

    static int withAlpha(int color, float alpha) {
        int a = Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /* ------------------------------------------------------------------ icons */

    /** Which vector icon a {@link Glyph} draws. No glyph font is involved. */
    static final int ICON_GRID = 0;
    static final int ICON_CHEVRON_DOWN = 1;
    static final int ICON_CHEVRON_UP = 2;
    static final int ICON_INFO = 3;

    /**
     * A small vector icon painted on a Canvas: the four-square menu grid, the up / down
     * chevron and the ⓘ mark. Every path is scaled into the view's own box, so the same
     * view keeps its look in every density and in every language.
     */
    static final class Glyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF box = new RectF();
        private final int kind;
        private final float stroke;
        /** Nominal square size, kept for {@link #onMeasure} when no explicit box is set. */
        private final float sizeDp;

        Glyph(Context context, int kind, int color, float sizeDp, float strokeDp) {
            super(context);
            this.kind = kind;
            this.sizeDp = sizeDp;
            this.stroke = dp(context, strokeDp);
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setLayoutParams(new ViewGroup.LayoutParams(dp(context, sizeDp), dp(context, sizeDp)));
        }

        void setColor(int color) {
            if (paint.getColor() == color) return;
            paint.setColor(color);
            invalidate();
        }

        /**
         * Square box of {@code sizeDp}, unless the caller laid a tighter box on it.
         *
         * <p>It used to trust only {@code params.width/height > 0}, so a caller that
         * passed its own small box through the LINEAR layout params it was handed (the
         * card's 16dp ⓘ, the header's 16dp chevron) came back at 16dp too — except it
         * measured 0 and pushed the whole card taller. Reading the minimum size as well
         * keeps the requested box no matter which way the parent expressed it.
         */
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            ViewGroup.LayoutParams params = getLayoutParams();
            int wantW = params != null && params.width > 0
                ? params.width : Math.max(dp(getContext(), sizeDp), getSuggestedMinimumWidth());
            int wantH = params != null && params.height > 0
                ? params.height : Math.max(dp(getContext(), sizeDp), getSuggestedMinimumHeight());
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
                    float inset = Math.min(w, h) * .30f;
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

    /* ----------------------------------------------------------- the capsule */

    /**
     * The assistant capsule: [state dot] [one short phrase] | [four-square grid].
     *
     * <p>Layout is fully in code (no LinearLayout child measuring), so the phrase never
     * gets an ellipsis: it is measured at the size it will be drawn and the capsule grows
     * instead of truncating its text. Tapping anywhere — phrase or grid — opens the menu.
     */
    static final class CapsuleView extends View {
        static final int HEIGHT_DP = 36;
        private static final int PAD_H_DP = 12;
        private static final int DOT_DP = 7;
        /** Minimum gap between the dot and the phrase. */
        private static final int DOT_GAP_DP = 7;
        /** Left inset of the phrase from the grid divider. */
        private static final int GRID_GAP_DP = 9;
        private static final int GRID_DP = 16;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final GradientDrawable body;
        private final Path grid = new Path();
        private final RectF cell = new RectF();
        private final int padH;
        private final int dotGap;
        private final int gridGap;
        private final int gridSize;
        private final int dividerHeight;
        private final float stroke;

        private CharSequence phrase = "";
        private int stateColor = STATE_AMBER;

        CapsuleView(Context context) {
            super(context);
            padH = dp(context, PAD_H_DP);
            dotGap = dp(context, DOT_GAP_DP);
            gridGap = dp(context, GRID_GAP_DP);
            gridSize = dp(context, GRID_DP);
            dividerHeight = dp(context, 16);
            stroke = Math.max(dp(context, 1.3f), 2f);
            body = round(context, (HEIGHT_DP + 2) / 2f, 0xE6111A2C, BORDER, STROKE);
            paint.setTextSize(dp(context, 13));
            paint.setTypeface(Typeface.SANS_SERIF);
            setClickable(true);
            setFocusable(false);
        }

        /** Copy is set once per state change; the view re-measures itself if it grew. */
        void setPhrase(CharSequence text) {
            String next = text == null ? "" : text.toString();
            String current = phrase.toString();
            if (next.equals(current)) return;
            phrase = next;
            requestLayout();
            invalidate();
        }

        void setStateColor(int color) {
            if (stateColor == color) return;
            stateColor = color;
            invalidate();
        }

        private float dotRadius() { return dp(getContext(), DOT_DP) / 2f; }

        private float startX() {
            return padH + dotRadius() + dotGap;
        }

        private float phraseWidth() {
            return paint.measureText(phrase, 0, phrase.length());
        }

        /** Left edge of the grid divider, i.e. the end of the phrase block. */
        private float dividerX() {
            return startX() + phraseWidth() + gridGap;
        }

        private float contentWidth() {
            return dividerX() + dp(getContext(), 1) + gridGap + gridSize + padH;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = (int) Math.ceil(contentWidth());
            int height = dp(getContext(), HEIGHT_DP);
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            body.setBounds(0, 0, (int) w, (int) h);
            body.draw(canvas);

            float centerY = h / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(stateColor);
            canvas.drawCircle(padH + dotRadius(), centerY, dotRadius(), paint);

            // The short phrase is drawn from its own left edge, never trimmed.
            paint.setColor(INK);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float baseline = centerY - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(phrase, 0, phrase.length(), startX(), baseline, paint);

            // Vertical hairline, then the four-icon grid; both are the same gold.
            float dx = dividerX();
            paint.setColor(withAlpha(BORDER, .75f));
            paint.setStrokeWidth(dp(getContext(), 1));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(dx, centerY - dividerHeight / 2f, dx, centerY + dividerHeight / 2f, paint);

            float gridLeft = dx + dp(getContext(), GRID_GAP_DP * 1f);
            float gridTop = centerY - gridSize / 2f;
            float gap = gridSize * .18f;
            float size = (gridSize - gap) / 2f;
            float radius = size * .24f;
            paint.setColor(ICON_GOLD);
            paint.setStrokeWidth(stroke);
            grid.reset();
            cell.set(gridLeft, gridTop, gridLeft + size, gridTop + size);
            grid.addRoundRect(cell, radius, radius, Path.Direction.CW);
            cell.offset(size + gap, 0);
            grid.addRoundRect(cell, radius, radius, Path.Direction.CW);
            cell.offset(0, size + gap);
            grid.addRoundRect(cell, radius, radius, Path.Direction.CW);
            cell.offset(-(size + gap), 0);
            grid.addRoundRect(cell, radius, radius, Path.Direction.CW);
            canvas.drawPath(grid, paint);
        }

        @Override public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            setAlpha(pressed ? .72f : 1f);
        }
    }

    /* ----------------------------------------------------------------- toggle */

    /**
     * Two-state switch drawn by hand: a rounded track plus a knob. The track is 40x24dp
     * (a little larger than before, so it is easy to hit) and the knob animates on the
     * view's own queue, so the owner never has to cancel an animator.
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
            height = dp(context, 24);
            width = dp(context, 40);
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
            // The track is painted a little wider than its layout box and overlaps the
            // card padding to the right; that strip is never hit-tested, so the card's
            // own tap area owns everything left of the track.
            float right = Math.min(getWidth() + overlap, getParent() == null
                ? getWidth() + overlap : ((View) getParent()).getWidth() - getLeft());
            oval.set(pad, pad, right, getHeight() - pad);
            float radius = oval.height() / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on ? TRACK_ON : TRACK_OFF);
            canvas.drawRoundRect(oval, radius, radius, paint);

            float knobR = radius - dp(getContext(), 2.5f);
            float cx = oval.left + radius + (oval.width() - 2 * radius) * position;
            paint.setColor(KNOB);
            paint.setShadowLayer(dp(getContext(), 1), 0, dp(getContext(), .5f), 0x33000000);
            canvas.drawCircle(cx, oval.centerY(), knobR, paint);
            paint.clearShadowLayer();
            if (busy) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(getContext(), 1.4f));
                paint.setColor(withAlpha(GOLD, .55f));
                canvas.drawRoundRect(oval, radius, radius, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }
}
