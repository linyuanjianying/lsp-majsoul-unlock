package android.view.accessibility;

/** Opaque accessibility event: only forwarded to the original callback. */
public class AccessibilityEvent {
    private int type;
    public int getEventType() { return type; }
    public void setEventType(int value) { type = value; }
}
