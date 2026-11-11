package android.text;

public class TextUtils {
    public enum TruncateAt { START, MIDDLE, END, MARQUEE }
    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.toString().contentEquals(b);
    }
    public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; }
}
