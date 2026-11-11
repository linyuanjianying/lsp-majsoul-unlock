package android.view.animation;

/** Translation-only animation: the offsets are what the probe asserts. */
public class TranslateAnimation extends Animation {
    public final float fromX;
    public final float toX;
    public final float fromY;
    public final float toY;

    public TranslateAnimation(float fromXDelta, float toXDelta, float fromYDelta, float toYDelta) {
        this.fromX = fromXDelta;
        this.toX = toXDelta;
        this.fromY = fromYDelta;
        this.toY = toYDelta;
    }
}
