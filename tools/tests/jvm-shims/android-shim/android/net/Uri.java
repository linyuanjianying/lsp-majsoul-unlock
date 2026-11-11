package android.net;

public class Uri {
    public static Uri parse(String text) { return new Uri(); }
    public String getAuthority() { return null; }
    public String getScheme() { return null; }
    public String getLastPathSegment() { return null; }
    @Override public String toString() { return "content://probe"; }
}
