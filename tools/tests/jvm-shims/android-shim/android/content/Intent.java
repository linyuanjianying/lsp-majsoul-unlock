package android.content;

public class Intent {
    public static final int FLAG_ACTIVITY_NO_ANIMATION = 0x00010000;
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public Intent setClassName(String pkg, String cls) { return this; }
    public java.util.Set<String> getCategories() { return new java.util.HashSet<>(); }
    public Intent() {}
    public Intent(String action) {}
    public Intent setAction(String action) { return this; }
    public Intent addFlags(int flags) { return this; }
    public Intent putExtra(String key, String value) { return this; }
    public Intent putExtra(String key, boolean value) { return this; }
    public Intent putExtra(String key, int value) { return this; }
    public String getAction() { return null; }
    public String getStringExtra(String key) { return null; }
    public int getIntExtra(String key, int def) { return def; }
    public boolean getBooleanExtra(String key, boolean def) { return def; }
    public void removeExtra(String key) {}
}
