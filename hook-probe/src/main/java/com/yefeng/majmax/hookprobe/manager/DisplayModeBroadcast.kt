package com.yefeng.majmax.hookprobe.manager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The presentation lease as the manager publishes it to the game process.
 *
 * [overlaySelected] is what the *game* needs to act on: true only while the system overlay
 * is the intended surface, which on the way back to hook mode means "true until my windows
 * are actually gone" — the game keeps hiding itself in that window instead of painting next
 * to a window that is still attached for one tick.
 *
 * [owner] is [DisplayModeOwner.OVERLAY] only after the game acknowledged that it hid its own
 * surfaces for [generation]; before that it is NONE and the game hides-and-acknowledges.
 */
internal data class DisplayModeBroadcast(
    val overlaySelected: Boolean = false,
    val owner: Int = DisplayModeOwner.NONE,
    val generation: Int = 0,
    val overlayFailed: Boolean = false,
)

internal object DisplayModeBroadcastState {
    /** Last published lease; read by the provider on every status reply. */
    internal val mutable = MutableStateFlow(DisplayModeBroadcast())

    val state = mutable.asStateFlow()

    /** The lease a fresh status reply should carry. */
    internal fun current(): DisplayModeBroadcast = mutable.value
}

/**
 * Manager-side state machine of the single surface lease.
 *
 * It exists to answer two questions with data instead of timers:
 *
 *  - **may the overlay be shown?** Only after the game acknowledged
 *    ([acknowledge]) that it has hidden everything it draws for the current generation.
 *    Showing earlier is exactly the "floating window and in-game drawing both active"
 *    report. There is deliberately no timeout: a late acknowledgement shows a slightly
 *    emptier screen, an early one shows two surfaces.
 *  - **may the game paint again?** Only once this process no longer intends to show a
 *    window ([setOverlaySelected] false, which is published *after* the windows are
 *    removed) — see [DisplayModeBroadcast.overlaySelected].
 */
internal object DisplayModeLeaseLocal {
    private val lock = Any()
    private var owner = DisplayModeOwner.NONE
    private var overlaySelected = false
    /** Generation the game acknowledged by hiding its surfaces, or -1. */
    private var ackedGeneration = -1
    /** True once the game spoke at least once (it can only speak after seeing a switch). */
    private var gameAcknowledged = false

    /** Publishes the current state. [generation] is the mode generation it belongs to. */
    fun publish(generation: Int) = synchronized(lock) {
        val failed = AssistantDisplayModeSettings.overlayUnavailableFor(generation)
        DisplayModeBroadcastState.mutable.value = DisplayModeBroadcast(
            overlaySelected = overlaySelected && !failed,
            owner = if (failed) DisplayModeOwner.NONE else owner,
            generation = generation,
            overlayFailed = failed,
        )
    }

    /**
     * The game hid its surfaces for [generation] and says so. Only a value the game itself
     * reported counts ([displayGeneration] extra of its status call).
     */
    fun acknowledge(generation: Int) = synchronized(lock) {
        if (generation <= 0) return
        if (generation > ackedGeneration) ackedGeneration = generation
        gameAcknowledged = true
    }

    /** True once the game acknowledged anything, i.e. an in-game control exists. */
    fun gameSeen(): Boolean = synchronized(lock) { gameAcknowledged }

    /**
     * The selection changed. Selecting overlay starts the "wait for the game to hide"
     * phase; deselecting hands the surface back immediately (the caller publishes this only
     * after its windows are gone).
     */
    fun setOverlaySelected(selected: Boolean, generation: Int) = synchronized(lock) {
        overlaySelected = selected
        if (!selected) owner = DisplayModeOwner.NONE
        publish(generation)
    }

    /**
     * Whether the overlay may be shown for [generation]: overlay selected, and the game
     * acknowledged *this* generation or a newer one.
     */
    fun mayShowOverlay(generation: Int): Boolean = synchronized(lock) {
        if (!overlaySelected) return false
        if (AssistantDisplayModeSettings.overlayUnavailableFor(generation)) return false
        ackedGeneration >= generation
    }

    /** Marks the surface as owned by the overlay; called once its windows are on screen. */
    fun claim(generation: Int) = synchronized(lock) {
        owner = DisplayModeOwner.OVERLAY
        publish(generation)
    }

    /** Drops the claim without changing the selection (teardown, failure). */
    fun drop(generation: Int) = synchronized(lock) {
        owner = DisplayModeOwner.NONE
        publish(generation)
    }
}
