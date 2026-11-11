package android.os;

/** Runs nothing: the probe only needs Handler construction and post/remove semantics. */
public class Handler {
    public Handler() {}
    public Handler(Looper looper) {}
    public boolean post(Runnable action) { return true; }
    public boolean postDelayed(Runnable action, long delay) { return true; }
    public void removeCallbacks(Runnable action) {}
    public void removeCallbacksAndMessages(Object token) {}
    public void sendEmptyMessage(int what) {}
}
