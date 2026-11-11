package android.view;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Stub ViewGroup: a real child list, real params, real add/remove, and a real touch
 * dispatch (topmost first, like the platform: children are walked in reverse z-order
 * and the first one that accepts the DOWN owns the rest of the gesture).
 */
public abstract class ViewGroup extends View {
    public static final int FOCUS_BEFORE_DESCENDANTS = 0x20000;

    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;
        public static final int FILL_PARENT = -1;
        public int width;
        public int height;
        public LayoutParams(int width, int height) { this.width = width; this.height = height; }
        public LayoutParams(LayoutParams source) { this(source.width, source.height); }
    }

    public static class MarginLayoutParams extends LayoutParams {
        public int leftMargin, topMargin, rightMargin, bottomMargin;
        public MarginLayoutParams(int width, int height) { super(width, height); }
        public MarginLayoutParams(LayoutParams source) { super(source); }
        public void setMargins(int l, int t, int r, int b) {
            leftMargin = l; topMargin = t; rightMargin = r; bottomMargin = b;
        }
    }

    private final List<View> children = new ArrayList<>();
    /** The child that owns the gesture in flight, or null. */
    private View touchTarget;
    /** Bumped on every accepted DOWN; a listener can tell one gesture from the next. */
    private int gesture;

    public ViewGroup(Context context) { super(context); }

    public void addView(View child) { addView(child, child.getLayoutParams()); }

    public void addView(View child, LayoutParams params) {
        if (child.getParent() != null && child.getParent() != this) {
            child.getParent().removeView(child);
        }
        if (params != null) child.setLayoutParams(params);
        if (!children.contains(child)) children.add(child);
        child.setParentInternal(this);
    }

    public void addView(View child, int index, LayoutParams params) { addView(child, params); }

    public void removeView(View child) {
        children.remove(child);
        if (child.getParent() == this) child.setParentInternal(null);
    }

    public void removeAllViews() {
        for (View child : new ArrayList<>(children)) removeView(child);
    }

    public int getChildCount() { return children.size(); }
    public View getChildAt(int index) { return children.get(index); }

    public int indexOfChild(View child) { return children.indexOf(child); }

    public void setClipChildren(boolean value) {}

    public void setDescendantFocusability(int value) {}

    public interface OnHierarchyChangeListener {
        void onChildViewAdded(View parent, View child);
        void onChildViewRemoved(View parent, View child);
    }

    /* ----------------------------------------------------------------- touch */

    /** Current gesture id, so a probe can assert that one DOWN owned the whole gesture. */
    public int touchGesture() { return gesture; }

    /** The child that owns the gesture in flight, or null. */
    public View touchTarget() { return touchTarget; }

    /**
     * Platform-shaped dispatch: the DOWN picks the topmost child that accepts it, every
     * later event of the same gesture goes to that child, and a view with no accepting
     * child handles the gesture itself (our root does its "tap on blank space" there).
     */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchTarget = null;
            gesture++;
        }
        boolean handled = false;
        if (touchTarget != null) {
            handled = touchTarget.dispatchTouchEvent(event);
            if (!handled || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchTarget = null;
            }
            return handled;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            View child = children.get(i);
            if (child.getVisibility() != VISIBLE || !child.contains(event.getX(), event.getY())) continue;
            if (child.dispatchTouchEvent(event)) {
                if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) touchTarget = child;
                handled = true;
                break;
            }
        }
        if (handled) return true;
        return super.dispatchTouchEvent(event);
    }
}
