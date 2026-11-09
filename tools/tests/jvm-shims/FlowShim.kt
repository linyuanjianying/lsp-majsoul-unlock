package kotlinx.coroutines.flow

/**
 * JVM stand-in for kotlinx-coroutines' `MutableStateFlow` factory function.
 *
 * AutoDiscardController publishes its status through `MutableStateFlow(...)` and
 * `AutoDiscardState` exposes it with `asStateFlow()`. The probe never collects the
 * flow; it only reads the published value (`AutoDiscardState.mutable.value`) and the
 * values the controller writes. Resolving the factory from the coroutines jar would
 * pull the whole coroutines runtime (and its dispatchers) into a plain `java`
 * launcher, so this file is compiled into the probe output and sorted first on the
 * runtime classpath. It never reaches the APK.
 */
fun <T> MutableStateFlow(value: T): MutableStateFlow<T> = SimpleMutableStateFlow(value)

internal class SimpleMutableStateFlow<T>(initial: T) : MutableStateFlow<T> {
    @Volatile private var current: T = initial
    override var value: T
        get() = current
        set(update) { current = update }
    override val replayCache: List<T> get() = listOf(current)
    override suspend fun collect(collector: FlowCollector<T>): Nothing = throw UnsupportedOperationException("probe")
    override suspend fun emit(value: T) { current = value }
    override fun tryEmit(value: T): Boolean { current = value; return true }
    override val subscriptionCount: StateFlow<Int> get() = throw UnsupportedOperationException("probe")
    override fun resetReplayCache() = throw UnsupportedOperationException("probe")
    override fun compareAndSet(expect: T, update: T): Boolean {
        if (current !== expect) return false
        current = update
        return true
    }
    override fun toString() = "SimpleStateFlow($current)"
}

/** Read-only view over the same value, mirroring `asStateFlow()`. */
private class ReadOnlyStateFlow<T>(private val source: MutableStateFlow<T>) : StateFlow<T> {
    override val value: T get() = source.value
    override val replayCache: List<T> get() = listOf(source.value)
    override suspend fun collect(collector: FlowCollector<T>): Nothing = throw UnsupportedOperationException("probe")
}

fun <T> MutableStateFlow<T>.asStateFlow(): StateFlow<T> = ReadOnlyStateFlow(this)
