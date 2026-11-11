package android.graphics;

/** Stub Canvas: drawing calls are accepted and discarded. */
public class Canvas {
    public int save() { return 0; }
    public void restore() {}
    public void restoreToCount(int count) {}
    public void translate(float dx, float dy) {}
    public void scale(float sx, float sy) {}
    public void rotate(float degrees) {}
    public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {}
    public void drawRoundRect(float l, float t, float r, float b, float rx, float ry, Paint paint) {}
    public void drawRect(RectF rect, Paint paint) {}
    public void drawRect(float l, float t, float r, float b, Paint paint) {}
    public void drawCircle(float cx, float cy, float radius, Paint paint) {}
    public void drawLine(float sx, float sy, float ex, float ey, Paint paint) {}
    public void drawPath(Path path, Paint paint) {}
    public void drawText(String text, float x, float y, Paint paint) {}
    public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {}
    public void clipRect(float l, float t, float r, float b) {}
    public void drawColor(int color) {}
}
