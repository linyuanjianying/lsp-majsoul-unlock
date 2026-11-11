package kotlinx.coroutines.flow;

/** Marker interface the Kotlin `StateFlow` surface maps onto in this shim. */
public interface StateFlow<T> {
    T getValue();
}
