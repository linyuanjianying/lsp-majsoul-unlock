package android.view;

/** Opaque key event: the control only forwards it to the original callback. */
public class KeyEvent {
    private final int action;
    public KeyEvent(int action) { this.action = action; }
    public int getAction() { return action; }
}
