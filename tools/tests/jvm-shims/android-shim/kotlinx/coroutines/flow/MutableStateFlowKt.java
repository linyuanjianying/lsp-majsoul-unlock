package kotlinx.coroutines.flow;

public class MutableStateFlowKt {
    public static <T> MutableStateFlow<T> MutableStateFlow(final T initial) {
        return new MutableStateFlow<T>() {
            private volatile T current = initial;
            public T getValue() { return current; }
            public void setValue(T next) { current = next; }
        };
    }
}
