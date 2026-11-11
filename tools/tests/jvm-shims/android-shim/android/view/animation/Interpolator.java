package android.view.animation;

/** Interpolator contract: input 0..1 to output 0..1. */
public interface Interpolator {
    float getInterpolation(float input);
}
