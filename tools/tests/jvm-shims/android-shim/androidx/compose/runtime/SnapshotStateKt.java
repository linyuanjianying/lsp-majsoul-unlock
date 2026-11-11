package androidx.compose.runtime;

/** Kotlin `mutableStateOf(value)` maps onto this synthetic helper. */
public class SnapshotStateKt {
    public static <T> MutableState<T> mutableStateOf(T value) {
        return new MutableState<T>() {
            private T current = value;
            public T getValue() { return current; }
            public void setValue(T next) { current = next; }
        };
    }
}
