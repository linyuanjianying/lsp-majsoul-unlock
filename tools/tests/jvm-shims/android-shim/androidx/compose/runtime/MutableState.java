package androidx.compose.runtime;

public interface MutableState<T> {
    T getValue();
    void setValue(T value);
}
