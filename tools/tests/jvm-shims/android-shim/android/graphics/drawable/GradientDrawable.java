package android.graphics.drawable;

import android.graphics.drawable.Drawable;

/**
 * Stub GradientDrawable that remembers its colour, stroke and corner radius, so a
 * regression can assert how a view shapes its background (a wide pill, a round dot).
 */
public class GradientDrawable extends Drawable {
    public static final int OVAL = 0;
    public static final int RECTANGLE = 1;

    public int shape;
    public int color;
    public int strokeWidth;
    public int strokeColor;
    public float cornerRadius;

    public GradientDrawable() {}
    public void setShape(int shape) { this.shape = shape; }
    public void setColor(int argb) { this.color = argb; }
    public void setStroke(int width, int color) {
        this.strokeWidth = width;
        this.strokeColor = color;
    }
    public void setStroke(int width, int color, float dashWidth, float dashGap) {
        setStroke(width, color);
    }
    public void setCornerRadius(float radius) { this.cornerRadius = radius; }
    public void setCornerRadii(float[] radii) {}
    public void setGradientType(int type) {}
    public void setOrientation(Orientation orientation) {}
    public void setColors(int[] colors) {}
    public void setAlpha(int alpha) {}
    public void setUseLevel(boolean value) {}
    public enum Orientation { TOP_BOTTOM, TR_BL, RIGHT_LEFT, BR_TL, BOTTOM_TOP, BL_TR, LEFT_RIGHT, TL_BR }
}
