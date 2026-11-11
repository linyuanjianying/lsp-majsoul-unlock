package android.view.animation;

/** Base of the two view animations the page switch uses; duration only. */
public class Animation {
    public static final int INFINITE = -1;
    public static final int RESTART = 1;
    public static final int REVERSE = 2;
    public static final int START_ON_FIRST_FRAME = -1;
    public static final int ABSOLUTE = 0;
    public static final int RELATIVE_TO_SELF = 1;
    public static final int RELATIVE_TO_PARENT = 2;

    private long duration;
    private long startOffset;
    private Interpolator interpolator;
    private boolean fillAfter;

    public void setDuration(long value) { duration = value; }
    public long getDuration() { return duration; }
    public void setStartOffset(long value) { startOffset = value; }
    public long getStartOffset() { return startOffset; }
    public void setInterpolator(Interpolator value) { interpolator = value; }
    public Interpolator getInterpolator() { return interpolator; }
    public void setFillAfter(boolean value) { fillAfter = value; }
    public boolean getFillAfter() { return fillAfter; }
    public void reset() {}
}
