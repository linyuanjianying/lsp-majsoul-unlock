package android.util;

import java.util.ArrayList;

/** Stub SparseArray: enough for view tags (int key -> Object, null deletes). */
public class SparseArray<E> {
    private final ArrayList<Integer> keys = new ArrayList<>();
    private final ArrayList<E> values = new ArrayList<>();

    public void put(int key, E value) {
        int index = keys.indexOf(key);
        if (index >= 0) {
            if (value == null) { keys.remove(index); values.remove(index); }
            else values.set(index, value);
        } else if (value != null) {
            keys.add(key);
            values.add(value);
        }
    }

    public E get(int key) {
        int index = keys.indexOf(key);
        return index < 0 ? null : values.get(index);
    }

    public E get(int key, E fallback) {
        E value = get(key);
        return value == null ? fallback : value;
    }

    public int size() { return keys.size(); }
    public void remove(int key) {
        int index = keys.indexOf(key);
        if (index >= 0) { keys.remove(index); values.remove(index); }
    }
    public void clear() { keys.clear(); values.clear(); }
}
