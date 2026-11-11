package kotlinx.coroutines.flow;

public class StateFlowKt {
    public static <T> StateFlow<T> asStateFlow(MutableStateFlow<T> flow) { return flow; }
}
