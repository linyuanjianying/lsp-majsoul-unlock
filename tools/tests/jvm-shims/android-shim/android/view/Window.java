package android.view;

import android.content.Context;

/** Stub Window: one replaceable callback, which is what the outside-tap observer wraps. */
public abstract class Window {
    private Callback callback;

    protected Window(Context context) {}

    /** The window callback contract, cut down to what the control forwards. */
    public interface Callback {
        boolean dispatchKeyEvent(KeyEvent event);
        boolean dispatchKeyShortcutEvent(KeyEvent event);
        boolean dispatchTouchEvent(MotionEvent event);
        boolean dispatchTrackballEvent(MotionEvent event);
        boolean dispatchGenericMotionEvent(MotionEvent event);
        boolean dispatchPopulateAccessibilityEvent(android.view.accessibility.AccessibilityEvent event);
        View onCreatePanelView(int featureId);
        boolean onCreatePanelMenu(int featureId, Menu menu);
        boolean onPreparePanel(int featureId, View view, Menu menu);
        boolean onMenuOpened(int featureId, Menu menu);
        boolean onMenuItemSelected(int featureId, MenuItem item);
        void onWindowAttributesChanged(WindowManager.LayoutParams attrs);
        void onContentChanged();
        void onWindowFocusChanged(boolean hasFocus);
        void onAttachedToWindow();
        void onDetachedFromWindow();
        void onPanelClosed(int featureId, Menu menu);
        boolean onSearchRequested();
        boolean onSearchRequested(SearchEvent event);
        ActionMode onWindowStartingActionMode(ActionMode.Callback callback);
        ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type);
        void onActionModeStarted(ActionMode mode);
        void onActionModeFinished(ActionMode mode);
    }

    public Callback getCallback() { return callback; }
    public void setCallback(Callback cb) { callback = cb; }

    public abstract View peekDecorView();
    public abstract View getDecorView();
    public View findViewById(int id) { return null; }
    public void addFlags(int flags) {}
    public void clearFlags(int flags) {}
}
