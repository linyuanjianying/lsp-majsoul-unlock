package android.graphics;

public class Typeface {
    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static final Typeface DEFAULT = new Typeface();
    public static final Typeface SANS_SERIF = new Typeface();
    public static Typeface create(String familyName, int style) { return new Typeface(); }
    public static Typeface create(Typeface family, int style) { return new Typeface(); }
    public static Typeface defaultFromStyle(int style) { return new Typeface(); }
}
