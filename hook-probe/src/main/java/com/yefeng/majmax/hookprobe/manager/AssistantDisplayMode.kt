package com.yefeng.majmax.hookprobe.manager

import android.content.Context
import android.provider.Settings

/**
 * Which single presentation surface the assistant uses for this run.
 *
 * The two surfaces — the system overlays ([AiFloatingWindow] + [TileBadgeOverlay] +
 * [RecommendationPanelOverlay]) and the in-game controls ([GameTopControl] + [GameHintLayer],
 * which render as children of the game's own decor view and therefore never need the
 * overlay permission) — are mutually exclusive. They are chosen **once**, and then exactly
 * one set is ever constructed:
 *
 *  - The settings read/save is a *selection for the next start* ([AssistantDisplayModeSettings.read]
 *    / [save]). Changing it while the service runs does not rebuild anything; the UI says so
 *    and disables the buttons while the assistant is running.
 *  - The service snapshots the selection in `onCreate` ([AssistantDisplayRun]). That snapshot is
 *    the `runMode` of the whole service instance and of every status reply it serves
 *    ([DisplayModeSnapshotStore]).
 *  - The game process asks the provider which mode is running and only constructs
 *    [GameTopControl] / [GameHintLayer] when the running mode is [AssistantDisplayMode.HOOK].
 *
 * There is deliberately no hand-off: no acknowledgement, no owner, no lease, no automatic
 * fallback and no cross-process broadcast. A selected overlay whose windows cannot be built
 * keeps the overlay selection and reports the error; it never turns into the Hook surface.
 * The game is only told about a different mode by a game restart (new process) or by the next
 * service start (new snapshot).
 */
internal enum class AssistantDisplayMode(val wire: String) {
    /** In-game decor-view controls and hints; no overlay permission needed (default). */
    HOOK("hook"),

    /** System floating window plus the system tile-badge / recommendation overlays. */
    OVERLAY("overlay");

    companion object {
        fun fromWire(value: String?): AssistantDisplayMode = when (value) {
            OVERLAY.wire -> OVERLAY
            else -> HOOK
        }
    }
}

/**
 * The persisted selection, written by the settings UI only. It is *not* what a running
 * service uses: see [AssistantDisplayRun.mode].
 *
 * The preference lives in its own file so it never collides with the unrelated
 * `unattended` opt-in, model or delay settings.
 */
internal object AssistantDisplayModeSettings {
    private const val PREFS = "assistant-display"
    private const val KEY_MODE = "mode"

    /** The mode the *next* service start will use. */
    fun read(context: Context): AssistantDisplayMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, AssistantDisplayMode.HOOK.wire)
        return AssistantDisplayMode.fromWire(raw)
    }

    /**
     * Stores the selection for the next start. Nothing is rebuilt and nothing is broadcast:
     * the running service keeps its `onCreate` snapshot until it is restarted, and the game
     * keeps the mode it was told at its own start. [hasOverlayPermission] is only used for the
     * diagnostics line, which is what makes a later "overlay window unavailable" report
     * explainable.
     */
    fun save(context: Context, mode: AssistantDisplayMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.wire).commit()
        DiagnosticsStore.appendAssistant(context, "INFO", "assistant.display", "MODE_SELECTED",
            org.json.JSONObject().put("mode", mode.wire)
                .put("overlayPermission", Settings.canDrawOverlays(context))
                .put("appliesTo", "next-start"))
    }

    /**
     * True while [mode] is the selection stored for the next start. Read by the in-game
     * control's status line and by the settings UI.
     */
    fun selectedIs(context: Context, mode: AssistantDisplayMode): Boolean = read(context) == mode
}

/**
 * The one mode a service instance decided on in `onCreate`.
 *
 * [mode] is the persisted selection as it was at that moment. [overlayError] records why the
 * overlay surface could not be built (null while it is fine); it is *not* a switch: when it is
 * set the service draws no in-game substitute either, because the selection stays OVERLAY.
 * [startedAt] and [nonce] identify the run for diagnostics and for the status reply that
 * carries the mode to the game process.
 *
 * This object is process-local data, not a protocol: the only thing that ever leaves the
 * manager process is [DisplayModeSnapshotStore.snapshot] (or the selection when no service is
 * running). There is no ack, no owner and no lease.
 */
internal data class DisplayRunSnapshot(
    val mode: AssistantDisplayMode,
    val overlayError: String? = null,
    val startedAt: Long = 0L,
    val nonce: String = "",
) {
    /** True while the overlay mode is selected and its windows are believed to be on screen. */
    val overlayReady: Boolean get() = mode == AssistantDisplayMode.OVERLAY && overlayError == null
}

/**
 * The single writer of [DisplayRunSnapshot] for the manager process.
 *
 * [start] is called exactly once per service instance (from `onCreate`); [noteOverlayError]
 * and [clearOverlayError] only annotate that same snapshot. A stale service instance must not
 * clobber the snapshot of the one that replaced it, so [finish] clears only its own snapshot.
 */
internal object AssistantDisplayRun {
    @Volatile private var value: DisplayRunSnapshot? = null

    /** The current run, or null while no service instance holds one. */
    val current: DisplayRunSnapshot? get() = value

    /** Mode of the current run; null while no service instance is running. */
    val mode: AssistantDisplayMode? get() = value?.mode

    /** Starts a new run from the stored selection. One call per service instance. */
    fun start(selection: AssistantDisplayMode, startedAt: Long, nonce: String): DisplayRunSnapshot {
        val snapshot = DisplayRunSnapshot(selection, null, startedAt, nonce)
        value = snapshot
        return snapshot
    }

    /** Records why the overlay surface is unavailable for this run; idempotent per reason. */
    fun noteOverlayError(error: String): DisplayRunSnapshot? {
        val current = value ?: return null
        if (current.overlayError == error) return current
        return current.copy(overlayError = error).also { value = it }
    }

    /** Clears the error once the overlay windows are actually attached again. */
    fun clearOverlayError(): DisplayRunSnapshot? {
        val current = value ?: return null
        if (current.overlayError == null) return current
        return current.copy(overlayError = null).also { value = it }
    }

    /** Ends this run. A later instance's snapshot survives: only the caller's is dropped. */
    fun finish(startedAt: Long) {
        if (value?.startedAt == startedAt) value = null
    }

    /** Test seam: forget everything (a process restart). */
    internal fun reset() {
        value = null
    }
}

/**
 * The provider-visible state. It is either the running snapshot or — with no service at all —
 * the stored selection, so a game process that starts before the assistant still learns what
 * the user selected. Nothing else is exported, and nothing is inferred from a failed call.
 */
internal object DisplayModeSnapshotStore {
    /**
     * - [MODE_SOURCE_SERVICE]: [DisplayModeSnapshotStore.snapshot] is the running instance;
     * - [MODE_SOURCE_SELECTION]: no service, the value is the stored selection;
     * - [MODE_SOURCE_UNKNOWN]: nothing could be read (the caller must keep its own state).
     */
    const val MODE_SOURCE_SERVICE = 1
    const val MODE_SOURCE_SELECTION = 2
    const val MODE_SOURCE_UNKNOWN = 0

    /**
     * Exactly one mode. `overlayReady` is false whenever the selected overlay's window is
     * missing, and it never turns the mode into HOOK.
     */
    data class Snapshot(
        val mode: AssistantDisplayMode,
        val source: Int,
        val overlayReady: Boolean,
        val overlayError: String?,
        val nonce: String,
    )

    /** The snapshot of the current service instance, or null while none holds one. */
    fun ofService(): Snapshot? {
        val run = AssistantDisplayRun.current ?: return null
        return Snapshot(run.mode, MODE_SOURCE_SERVICE, run.overlayReady, run.overlayError, run.nonce)
    }

    /** What a status reply carries: the service snapshot, else the stored selection. */
    fun snapshot(context: Context): Snapshot {
        ofService()?.let { return it }
        return runCatching { AssistantDisplayModeSettings.read(context) }
            .map { Snapshot(it, MODE_SOURCE_SELECTION, false, null, "") }
            .getOrElse { Snapshot(AssistantDisplayMode.HOOK, MODE_SOURCE_UNKNOWN, false, null, "") }
    }
}
