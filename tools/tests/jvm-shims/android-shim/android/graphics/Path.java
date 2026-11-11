package android.graphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Stub Path that remembers the vertices it was given.
 *
 * <p>Nothing is rasterized, but {@link #points()} lets a regression assert the *shape* a
 * view draws (a chevron, a grid of rounded cells) instead of only that onDraw ran.
 */
public class Path {
    private final List<float[]> points = new ArrayList<>();
    private final List<float[]> rects = new ArrayList<>();

    public void reset() { points.clear(); rects.clear(); }
    public void moveTo(float x, float y) { points.add(new float[] {x, y, 0f}); }
    public void lineTo(float x, float y) { points.add(new float[] {x, y, 1f}); }
    public void close() { points.add(new float[] {0f, 0f, 2f}); }
    public void addRoundRect(RectF rect, float rx, float ry, Direction direction) {
        rects.add(new float[] {rect.left, rect.top, rect.right, rect.bottom});
    }
    public void addRoundRect(float l, float t, float r, float b, float rx, float ry, Direction d) {
        rects.add(new float[] {l, t, r, b});
    }
    public List<float[]> points() { return points; }
    public List<float[]> rects() { return rects; }
    public boolean isEmpty() { return points.isEmpty() && rects.isEmpty(); }

    public enum Direction { CW, CCW }
}
