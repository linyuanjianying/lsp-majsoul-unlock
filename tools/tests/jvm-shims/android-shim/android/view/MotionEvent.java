package android.view;

/**
 * Synthetic motion event for the JVM touch regression.
 *
 * <p>The real class is native; this one keeps only what the view code reads
 * ({@link #getActionMasked()}, {@link #getX()}/{@link #getY()}, the pointer count and
 * {@link #getEventTime()}) plus plain factories, because the probe builds gestures itself
 * instead of feeding them through an input device.
 */
public class MotionEvent {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_POINTER_DOWN = 5;
    public static final int ACTION_POINTER_UP = 6;
    public static final int ACTION_MASK = 0xff;

    private final int action;
    private final float x;
    private final float y;
    private final long time;
    private int pointerCount = 1;

    public MotionEvent(int action, float x, float y, long time) {
        this.action = action;
        this.x = x;
        this.y = y;
        this.time = time;
    }

    /** The platform's copy factory: the probe only needs the numbers. */
    public static MotionEvent obtain(long downTime, long eventTime, int action,
                                     float x, float y, int metaState) {
        return new MotionEvent(action, x, y, eventTime);
    }

    /** One pointer at (x, y). */
    public static MotionEvent obtain(int action, float x, float y, long time) {
        return new MotionEvent(action, x, y, time);
    }

    /** The same box, carried by [pointers] fingers (the gesture itself is unchanged). */
    public static MotionEvent multi(int action, float x, float y, long time, int pointers) {
        MotionEvent event = new MotionEvent(action, x, y, time);
        event.pointerCount = Math.max(1, pointers);
        return event;
    }

    public int getActionMasked() { return action & ACTION_MASK; }
    public int getAction() { return action; }
    public float getX() { return x; }
    public float getY() { return y; }
    public float getRawX() { return x; }
    public float getRawY() { return y; }
    public long getEventTime() { return time; }
    public long getDownTime() { return time; }
    public int getMetaState() { return 0; }
    public int getPointerCount() { return pointerCount; }

    @Override public String toString() {
        return "MotionEvent(action=" + getActionMasked() + ", x=" + x + ", y=" + y
            + ", pointers=" + pointerCount + ")";
    }
}
