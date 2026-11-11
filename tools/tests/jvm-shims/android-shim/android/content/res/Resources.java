package android.content.res;

import android.util.DisplayMetrics;

public class Resources {
    private final DisplayMetrics metrics = new DisplayMetrics();
    public DisplayMetrics getDisplayMetrics() { return metrics; }
    private static final Resources SYSTEM = new Resources();
    public static Resources getSystem() { return SYSTEM; }
    public android.content.res.Configuration getConfiguration() { return new android.content.res.Configuration(); }
    public String getString(int id) { return ""; }
    public int getColor(int id) { return 0; }
}
