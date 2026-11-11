package android.graphics.drawable;

import android.graphics.Canvas;
import android.graphics.Rect;

/** Stub Drawable: bounds are remembered, drawing is a no-op. */
public class Drawable {
    private final Rect bounds = new Rect();

    public void setBounds(int left, int top, int right, int bottom) {
        bounds.set(left, top, right, bottom);
    }
    public void setBounds(Rect rect) { bounds.set(rect); }
    public Rect getBounds() { return bounds; }
    public void setAlpha(int alpha) {}
    public void draw(Canvas canvas) {}
    public int getIntrinsicWidth() { return -1; }
    public int getIntrinsicHeight() { return -1; }
}
