package android.view.animation;

/** Ease-out curve: starts fast, lands gently (the platform formula). */
public class DecelerateInterpolator implements Interpolator {
    private final float factor;
    public DecelerateInterpolator() { this(1f); }
    public DecelerateInterpolator(float factor) { this.factor = factor; }
    @Override public float getInterpolation(float input) {
        if (factor == 1f) return 1f - (1f - input) * (1f - input);
        return (float) (1f - Math.pow(1f - input, 2f * factor));
    }
}
