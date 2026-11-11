package android.app;

import android.content.Context;
import android.content.Intent;
import android.view.MotionEvent;
import android.view.Window;

/** Stub Activity: a context that owns a window whose decor view is a FrameLayout. */
public class Activity extends Context {
    public static class FakeWindow extends Window {
        private final android.widget.FrameLayout decor;
        public FakeWindow(Context context) {
            super(context);
            decor = new android.widget.FrameLayout(context);
        }
        @Override public android.view.View peekDecorView() { return decor; }
        @Override public android.view.View getDecorView() { return decor; }
        public android.widget.FrameLayout decor() { return decor; }
    }

    private FakeWindow window;
    private Intent intent = new Intent();

    @Override public Context getApplicationContext() { return this; }

    /**
     * The window and its callback. The platform installs the Activity as the window's
     * callback, so the stub does too: a probe can then wrap it exactly like the control
     * does on a device ({@code Window.setCallback}) and assert the chain.
     */
    public Window getWindow() {
        if (window == null) {
            window = new FakeWindow(this);
            if (window.getCallback() == null) window.setCallback(callback);
        }
        return window;
    }

    /** Stand-in Activity window callback: every method is a no-op returning "not handled". */
    private final Window.Callback callback = new Window.Callback() {
        public boolean dispatchKeyEvent(android.view.KeyEvent event) { return false; }
        public boolean dispatchKeyShortcutEvent(android.view.KeyEvent event) { return false; }
        public boolean dispatchTouchEvent(MotionEvent event) { return false; }
        public boolean dispatchTrackballEvent(MotionEvent event) { return false; }
        public boolean dispatchGenericMotionEvent(MotionEvent event) { return false; }
        public boolean dispatchPopulateAccessibilityEvent(
                android.view.accessibility.AccessibilityEvent event) { return false; }
        public android.view.View onCreatePanelView(int featureId) { return null; }
        public boolean onCreatePanelMenu(int featureId, android.view.Menu menu) { return false; }
        public boolean onPreparePanel(int featureId, android.view.View view, android.view.Menu menu) {
            return false;
        }
        public boolean onMenuOpened(int featureId, android.view.Menu menu) { return false; }
        public boolean onMenuItemSelected(int featureId, android.view.MenuItem item) { return false; }
        public void onWindowAttributesChanged(android.view.WindowManager.LayoutParams attrs) {}
        public void onContentChanged() {}
        public void onWindowFocusChanged(boolean hasFocus) {}
        public void onAttachedToWindow() {}
        public void onDetachedFromWindow() {}
        public void onPanelClosed(int featureId, android.view.Menu menu) {}
        public boolean onSearchRequested() { return false; }
        public boolean onSearchRequested(android.view.SearchEvent event) { return false; }
        public android.view.ActionMode onWindowStartingActionMode(
                android.view.ActionMode.Callback c) { return null; }
        public android.view.ActionMode onWindowStartingActionMode(
                android.view.ActionMode.Callback c, int type) { return null; }
        public void onActionModeStarted(android.view.ActionMode mode) {}
        public void onActionModeFinished(android.view.ActionMode mode) {}
    };

    /** The window callback this Activity installs, for probes that need the original. */
    public Window.Callback windowCallback() { return callback; }

    public Intent getIntent() { return intent; }
    public void runOnUiThread(Runnable action) { action.run(); }
    public boolean isFinishing() { return false; }
    public boolean isDestroyed() { return false; }
    protected void onCreate(android.os.Bundle state) {}
    protected void onResume() {}
    protected void onStart() {}
    protected void onPause() {}
    protected void onStop() {}
    protected void onDestroy() {}
    public void finish() {}
    public boolean moveTaskToBack(boolean nonRoot) { return true; }
    public void setTitle(CharSequence title) {}
    public android.content.pm.PackageManager getPackageManager() { return new android.content.pm.PackageManager(); }
    public android.net.Uri getReferrer() { return null; }
    public void startActivity(android.content.Intent intent) {}
    public void startActivityForResult(android.content.Intent intent, int code) {}
    public void setRequestedOrientation(int value) {}
    public int getRequestedOrientation() { return 0; }
    public Object getWindowManager() { return null; }
}
