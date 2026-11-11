package android.content.pm;

import android.content.Intent;

public class PackageManager {
    public static final int GET_META_DATA = 0x00000080;
    public static class NameNotFoundException extends Exception {
        public NameNotFoundException() { super("probe: not installed"); }
        public NameNotFoundException(String message) { super(message); }
    }
    public Intent getLaunchIntentForPackage(String packageName) { return null; }
    public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
        throw new NameNotFoundException(packageName);
    }
    public static class PackageInfo { public String versionName; public long versionCode; }
}
