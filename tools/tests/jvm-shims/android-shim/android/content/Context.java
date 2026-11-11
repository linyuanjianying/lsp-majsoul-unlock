package android.content;

import android.content.res.Resources;

public class Context {
    public static final int MODE_PRIVATE = 0;
    private final Resources resources = new Resources();
    public Resources getResources() { return resources; }
    public Context getApplicationContext() { return this; }
    public String getPackageName() { return "probe"; }
    public Object getSystemService(String name) { return null; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return null; }
    public java.io.File getFilesDir() { return new java.io.File(System.getProperty("java.io.tmpdir", "/tmp"), "probe-files"); }
    public java.io.File getCacheDir() { return new java.io.File(System.getProperty("java.io.tmpdir", "/tmp"), "probe-cache"); }
    public java.io.File getDir(String name, int mode) { return new java.io.File(System.getProperty("java.io.tmpdir", "/tmp"), name); }
    public android.content.pm.PackageManager getPackageManager() { return new android.content.pm.PackageManager(); }
    public ContentResolver getContentResolver() { return new ContentResolver(); }
}
