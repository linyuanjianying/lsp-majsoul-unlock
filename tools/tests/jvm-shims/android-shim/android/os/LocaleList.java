package android.os;

import java.util.Locale;

public class LocaleList {
    private final Locale[] values;
    public LocaleList(Locale... locales) { values = locales == null ? new Locale[0] : locales; }
    public int size() { return values.length; }
    public Locale get(int index) { return index >= 0 && index < values.length ? values[index] : null; }
    public Locale getFirstMatch(String[] supported) { return values.length > 0 ? values[0] : null; }
}
