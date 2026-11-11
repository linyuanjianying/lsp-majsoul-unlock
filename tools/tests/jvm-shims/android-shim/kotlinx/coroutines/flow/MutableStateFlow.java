package kotlinx.coroutines.flow;

public interface MutableStateFlow<T> extends StateFlow<T> {
    T getValue();
    void setValue(T value);
}
