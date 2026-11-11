package android.graphics;

public class Rect {
    public int left, top, right, bottom;
    public Rect() {}
    public Rect(int l, int t, int r, int b) { left = l; top = t; right = r; bottom = b; }
    public void set(int l, int t, int r, int b) { left = l; top = t; right = r; bottom = b; }
    public void set(Rect other) { left = other.left; top = other.top; right = other.right; bottom = other.bottom; }
    public int width() { return right - left; }
    public int height() { return bottom - top; }
    public void offset(int dx, int dy) { left += dx; right += dx; top += dy; bottom += dy; }
    public boolean isEmpty() { return right <= left || bottom <= top; }
    /** Platform semantics: the right edge and the bottom edge are exclusive. */
    public boolean contains(int x, int y) { return x >= left && x < right && y >= top && y < bottom; }
    public boolean contains(float x, float y) { return x >= left && x < right && y >= top && y < bottom; }
}
