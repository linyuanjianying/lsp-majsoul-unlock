package android.view;

import android.content.Context;
import android.graphics.Canvas;
import android.view.ViewGroup.LayoutParams;

/**
 * Stub View with a *working* measurement pass.
 *
 * <p>Tags, children, listeners and performClick() are faithful (the build regression needs
 * them); drawing is still a no-op. What changed for the layout regression is that
 * {@link #measure(int, int)} now stores the measured size, {@link #resolveSize} resolves
 * like the platform, {@link #setMinimumHeight}/{@link #setPadding} are remembered and
 * {@link #getSuggestedMinimum*} reports them, so {@link #onMeasure} of the real control
 * sources gets the same numbers a device would give them (minus the font metrics, which
 * TextView still does not model: a text row is measured as its explicit padding plus its
 * minimum height).
 *
 * <p>For the touch regression (TopControlTouchProbe) a synthetic {@link MotionEvent}
 * queue is dispatched through the real view tree exactly like ViewGroup does it: the
 * hit child that returns true from {@link #dispatchTouchEvent} gets the rest of the
 * gesture, and a DOWN/UP pair on a clickable view delivers exactly one click. A stub
 * view has no layout box, so the probe lays every view out first
 * ({@link #layout(int, int, int, int)}).
 */
public class View {
    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;

    public static final int NO_ID = -1;

    protected Context context;
    private ViewGroup parent;
    private LayoutParams layoutParams;
    private int visibility = VISIBLE;

    private int measuredWidth;
    private int measuredHeight;
    private int width;
    private int height;
    private int leftPos;
    private int topPos;

    private int paddingLeft;
    private int paddingTop;
    private int paddingRight;
    private int paddingBottom;

    private int minimumWidth;
    private int minimumHeight;

    private CharSequence contentDescription = "";
    private final android.util.SparseArray<Object> tags = new android.util.SparseArray<>();
    private Object tag;

    private View.OnClickListener clickListener;
    private boolean clickable;
    private boolean focusable;
    private boolean pressed;
    private boolean pressedByPlatform;
    private int clickCount;
    private float alpha = 1f;
    private OnTouchListener touchListener;

    public View(Context context) { this.context = context; }

    public Context getContext() { return context; }

    public void setOnClickListener(View.OnClickListener l) { clickListener = l; clickable = true; }
    public boolean hasOnClickListeners() { return clickListener != null; }
    public boolean performClick() {
        clickCount++;
        if (clickListener != null) {
            clickListener.onClick(this);
            return true;
        }
        return clickable;
    }
    /** How many clicks this view has delivered, for the touch regression. */
    public int clickCount() { return clickCount; }
    public void resetClickCount() { clickCount = 0; }
    public interface OnClickListener { void onClick(View v); }
    public interface OnLongClickListener { boolean onLongClick(View v); }
    public interface OnTouchListener { boolean onTouch(View v, MotionEvent e); }
    public void setClickable(boolean value) { clickable = value; }
    public boolean isClickable() { return clickable; }
    public void setFocusable(boolean value) { focusable = value; }
    public boolean isFocusable() { return focusable; }
    public void setEnabled(boolean value) {}
    public boolean isEnabled() { return true; }
    public void setAlpha(float value) { alpha = value; }
    public float getAlpha() { return alpha; }
    public void setContentDescription(CharSequence value) { contentDescription = value == null ? "" : value; }
    public CharSequence getContentDescription() { return contentDescription; }
    public void setBackground(android.graphics.drawable.Drawable value) { background = value; }
    public android.graphics.drawable.Drawable getBackground() { return background; }
    private android.graphics.drawable.Drawable background;
    public void setBackgroundColor(int color) {}
    public void setElevation(float value) {}
    public void setScaleX(float value) {}
    public void setScaleY(float value) {}
    public void setPivotX(float value) {}
    public void setPivotY(float value) {}
    public void setTranslationX(float value) {}
    public void setTranslationY(float value) {}
    public void setTag(Object tag) { this.tag = tag; }
    public Object getTag() { return tag; }
    public void setTag(int key, Object tag) { tags.put(key, tag); }
    public Object getTag(int key) { return tags.get(key); }
    public void setLayoutDirection(int value) {}
    public void setImportantForAccessibility(int value) {}
    public void setSoundEffectsEnabled(boolean value) {}
    public void setHapticFeedbackEnabled(boolean value) {}
    public void setWillNotDraw(boolean value) {}
    public void setWillNotCacheDrawing(boolean value) {}
    public void setOverScrollMode(int value) {}
    public void setScrollBarDefaultDelayBeforeFade(int value) {}
    public void setVerticalScrollBarEnabled(boolean value) {}
    public void setHorizontalScrollBarEnabled(boolean value) {}
    public void setClipChildren(boolean value) {}
    public void setClipToPadding(boolean value) {}
    public void setVisibility(int value) { visibility = value; }
    public int getVisibility() { return visibility; }

    /* --------------------------------------------------------------- spacing */

    public void setPadding(int l, int t, int r, int b) {
        paddingLeft = l; paddingTop = t; paddingRight = r; paddingBottom = b;
    }
    public int getPaddingLeft() { return paddingLeft; }
    public int getPaddingTop() { return paddingTop; }
    public int getPaddingRight() { return paddingRight; }
    public int getPaddingBottom() { return paddingBottom; }
    public void setMinimumHeight(int value) { minimumHeight = value; }
    public void setMinimumWidth(int value) { minimumWidth = value; }
    public int getMinimumHeight() { return minimumHeight; }
    public int getMinimumWidth() { return minimumWidth; }
    public int getMinimumHeightResolved() { return minimumHeight; }
    public int getSuggestedMinimumWidth() { return minimumWidth; }
    public int getSuggestedMinimumHeight() { return minimumHeight; }
    public int getPaddingStart() { return paddingLeft; }
    public int getPaddingEnd() { return paddingRight; }

    public boolean hasWindowFocus() { return true; }
    public void announceForAccessibility(CharSequence text) {}
    public void setOnLongClickListener(OnLongClickListener l) {}
    public void setOnTouchListener(OnTouchListener l) { touchListener = l; }
    public void setOnFocusChangeListener(OnFocusChangeListener l) {}
    public interface OnFocusChangeListener { void onFocusChange(View v, boolean hasFocus); }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getLeft() { return leftPos; }
    public float getX() { return leftPos; }
    public float getY() { return topPos; }
    public int getTop() { return topPos; }
    public int getRight() { return leftPos + width; }
    public int getBottom() { return topPos + height; }
    /** True when the point is inside this view's box (visibility is the parent's call). */
    public boolean contains(float x, float y) {
        return getVisibility() != GONE && x >= leftPos && x < leftPos + width
            && y >= topPos && y < topPos + height;
    }
    /**
     * Screen-space box of this view. The shim keeps every view at the window origin (no
     * real layout pass accumulates offsets in getLocationOnScreen), so the global rect is
     * this view's own box — enough for the outside-tap watcher, which only asks "is this
     * raw touch inside the panel or not".
     */
    public boolean getGlobalVisibleRect(android.graphics.Rect out) {
        if (out == null) return false;
        out.set(leftPos, topPos, leftPos + width, topPos + height);
        return getVisibility() == VISIBLE && width > 0 && height > 0;
    }
    public void setLayoutParams(LayoutParams params) { layoutParams = params; }
    public LayoutParams getLayoutParams() { return layoutParams; }
    public ViewGroup getParent() { return parent; }
    public void setParentInternal(ViewGroup value) { parent = value; }
    public boolean isAttachedToWindow() { return parent != null; }
    public void invalidate() {}
    public void requestLayout() {}
    public boolean post(Runnable action) { return true; }
    public boolean postDelayed(Runnable action, long delay) { return true; }
    public void removeCallbacks(Runnable action) {}
    public void addOnLayoutChangeListener(OnLayoutChangeListener listener) {}
    public void removeOnLayoutChangeListener(OnLayoutChangeListener listener) {}
    public int getId() { return NO_ID; }
    public void setId(int id) {}
    public void setSaveEnabled(boolean value) {}
    public void setSystemUiVisibility(int value) {}
    public boolean isLaidOut() { return parent != null; }

    /**
     * Press state. The platform keeps the *programmatic* flag and the *pressed while a
     * finger is down* flag apart ({@code setPressed(false)} from a click handler must not
     * cancel a finger that is still on the view); the stub mirrors that so a touch
     * regression can assert which one moved.
     */
    public void setPressed(boolean value) {
        if (value == pressed) return;
        pressed = value;
        pressedByPlatform = false;
    }
    public boolean isPressed() { return pressed; }
    /** True while the press came from a touch sequence rather than code. */
    public boolean isPressedByTouch() { return pressedByPlatform; }
    public void setTouchPressed(boolean value) { pressedByPlatform = value; pressed = value; }

    /* ----------------------------------------------------------- measurement */

    protected void onDraw(Canvas canvas) {}
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthSpec),
            getDefaultSize(getSuggestedMinimumHeight(), heightSpec));
    }
    protected void onLayout(boolean changed, int l, int t, int r, int b) {}

    protected final void setMeasuredDimension(int width, int height) {
        measuredWidth = Math.max(0, width);
        measuredHeight = Math.max(0, height);
    }

    public final int resolveSize(int size, int measureSpec) {
        int mode = MeasureSpec.getMode(measureSpec);
        int specSize = MeasureSpec.getSize(measureSpec);
        switch (mode) {
            case MeasureSpec.AT_MOST: return Math.min(size, specSize);
            case MeasureSpec.EXACTLY: return specSize;
            default: return size;
        }
    }

    public static int getDefaultSize(int size, int measureSpec) {
        int mode = MeasureSpec.getMode(measureSpec);
        int specSize = MeasureSpec.getSize(measureSpec);
        switch (mode) {
            case MeasureSpec.UNSPECIFIED: return size;
            case MeasureSpec.AT_MOST:
            case MeasureSpec.EXACTLY: return specSize;
            default: return size;
        }
    }

    public static class MeasureSpec {
        public static final int UNSPECIFIED = 0;
        public static final int EXACTLY = 1 << 30;
        public static final int AT_MOST = 2 << 30;
        public static final int MODE_MASK = 3 << 30;
        public static int makeMeasureSpec(int size, int mode) { return (size & ~MODE_MASK) | (mode & MODE_MASK); }
        public static int getMode(int spec) { return spec & MODE_MASK; }
        public static int getSize(int spec) { return spec & ~MODE_MASK; }
    }

    /**
     * Not final on purpose: the probe needs the contract, and keeping one virtual entry
     * point means {@link #onMeasure} of the real sources stays the single place that
     * produces every measured number.
     */
    public void measure(int widthSpec, int heightSpec) {
        onMeasure(widthSpec, heightSpec);
    }

    public final int getMeasuredWidth() { return measuredWidth; }
    public final int getMeasuredHeight() { return measuredHeight; }
    public final int getMeasuredState() { return 0; }

    /** Lays the view out and remembers the size the parent gave it. */
    public void layout(int l, int t, int r, int b) {
        leftPos = l;
        topPos = t;
        width = Math.max(0, r - l);
        height = Math.max(0, b - t);
        onLayout(false, l, t, r, b);
    }

    public android.content.res.Resources getResources() {
        return context == null ? new android.content.res.Resources() : context.getResources();
    }

    /* ----------------------------------------------------------------- touch */

    /**
     * A default view takes a gesture only when it is clickable, exactly like the platform:
     * DOWN presses, UP on a still-pressed view clicks, CANCEL and MOVE-away release. A
     * press that a drag cancelled never produces a click.
     */
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (touchListener != null && touchListener.onTouch(this, event)) return true;
        return onTouchEvent(event);
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (!clickable) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                setTouchPressed(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                return true;
            case MotionEvent.ACTION_UP:
                if (!pressedByPlatform) return true;
                setTouchPressed(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                setTouchPressed(false);
                return true;
            default:
                return true;
        }
    }

    public interface OnLayoutChangeListener { void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob); }
}
