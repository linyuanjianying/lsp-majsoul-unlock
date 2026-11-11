package android.view;

import android.content.Context;

/** Touch slop of the JVM regression: the platform value at density 2.75 (~8dp). */
public class ViewConfiguration {
    /** Touch slop, in px. A drag is anything beyond this; a tap stays below it. */
    public static final int TOUCH_SLOP_PX = 22;

    public static ViewConfiguration get(Context context) { return new ViewConfiguration(); }
    public int getScaledTouchSlop() { return TOUCH_SLOP_PX; }
    public static int getLongPressTimeout() { return 500; }
    public int getScaledMinimumScalingSpan() { return 0; }
}
