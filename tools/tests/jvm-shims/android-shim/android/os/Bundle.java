package android.os;

import java.util.HashMap;
import java.util.Map;

public class Bundle {
    private final Map<String, Object> values = new HashMap<>();
    public void putBoolean(String key, boolean value) { values.put(key, value); }
    public void putString(String key, String value) { values.put(key, value); }
    public void putInt(String key, int value) { values.put(key, value); }
    public boolean getBoolean(String key) { return getBoolean(key, false); }
    public int getInt(String key) { return getInt(key, 0); }
    public long getLong(String key, long def) { return def; }
    public boolean getBoolean(String key, boolean def) {
        Object v = values.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }
    public String getString(String key) { return (String) values.get(key); }
    public String getString(String key, String def) { Object v = values.get(key); return v instanceof String ? (String) v : def; }
    public void putStringArray(String key, String[] value) { values.put(key, value); }
    public int getInt(String key, int def) { Object v = values.get(key); return v instanceof Integer ? (Integer) v : def; }
    public boolean containsKey(String key) { return values.containsKey(key); }
    public Object get(String key) { return values.get(key); }
    public void remove(String key) { values.remove(key); }
}
