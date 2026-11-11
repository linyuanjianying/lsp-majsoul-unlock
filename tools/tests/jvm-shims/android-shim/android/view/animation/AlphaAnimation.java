package android.view.animation;

/** Alpha-only animation. */
public class AlphaAnimation extends Animation {
    public final float fromAlpha;
    public final float toAlpha;
    public AlphaAnimation(float fromAlpha, float toAlpha) {
        this.fromAlpha = fromAlpha;
        this.toAlpha = toAlpha;
    }
}
