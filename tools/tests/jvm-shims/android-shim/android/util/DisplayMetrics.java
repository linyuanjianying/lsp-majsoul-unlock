package android.util;

public class DisplayMetrics {
    public float density = 2.75f;
    public int densityDpi = 440;
    public int widthPixels = 1080;
    public int heightPixels = 2400;
    public float scaledDensity = 2.75f;
    public int widthPixelsDp() { return (int) (widthPixels / density); }
    public int heightPixelsDp() { return (int) (heightPixels / density); }
}
