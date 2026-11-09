package com.yefeng.majmax.hookprobe.manager

/**
 * What the **game** side does with one status reply, and whether it must acknowledge.
 *
 * The two surfaces live in two processes. Exactly one may paint, and the hand-off is a
 * three-step protocol driven by data, not by timers:
 *
 *  1. the manager selects overlay and answers with `owner = NONE`, `overlaySelected = true`;
 *  2. the game sees "overlay wanted, nobody owns it yet" and **hides everything it draws**
 *     (in-game controls *and* hint layer), then acknowledges that generation;
 *  3. the manager, only after that real acknowledgement, shows its overlay and claims the
 *     surface; the game keeps itself hidden while the claim is renewed.
 *
 * Nothing here has a timeout that lets a surface paint over another one. When the manager
 * is unreachable the in-game control paints again — that is a *fallback for a phone where
 * the overlay cannot be shown*, not an overlap: the manager's overlay is torn down with its
 * process, and while the game cannot vouch for it the honest state is "show what I own".
 *
 * All decisions are pure functions of the reply plus a few flags, so two-state-machine
 * interleavings are reproducible on a plain JVM ([HandoffState]).
 */
internal class DisplayModeLease(private val clock: () -> Long) {

    /** Mode generation of the last applied reply. */
    var generation: Int = 0
        private set

    /** True while the manager reports overlay mode selected (and its window usable). */
    var overlaySelected: Boolean = false
        private set

    /** Owner of the lease as the manager reported it. */
    var owner: Int = DisplayModeOwner.NONE
        private set

    /** True after a status call failed: the manager's state is unknown. */
    var unreachable: Boolean = false
        private set

    private var grantedGeneration: Int = -1
    private var renewedAt: Long = 0L
    private var appliedAt: Long = 0L

    /**
     * Records one status reply. A reply stamped with an **older** generation than one
     * already applied is dropped: the answers of the two processes complete in whatever
     * order they finish, and a late answer for a superseded generation must not repaint the
     * surface that generation belonged to.
     */
    fun apply(reply: Reply) {
        unreachable = reply.unreachable
        if (reply.unreachable) {
            owner = DisplayModeOwner.NONE
            grantedGeneration = -1
            renewedAt = 0L
            return
        }
        val carried = reply.generation
        if (carried < generation) return
        generation = carried
        overlaySelected = reply.overlaySelected && !reply.overlayUnavailable
        owner = reply.owner
        appliedAt = clock()
        if (owner == DisplayModeOwner.OVERLAY) {
            // Every reply that still reports the overlay as owner renews the claim; a lapsed
            // renewal means the manager stopped answering and this side may paint again.
            grantedGeneration = carried
            renewedAt = appliedAt
        } else {
            grantedGeneration = -1
            renewedAt = 0L
        }
    }

    /**
     * Must this side hide everything it draws?
     *
     * - overlay selected and the overlay owns the surface → hidden (it is painting);
     * - overlay selected and nobody owns it yet → hidden **and** acknowledged: this is the
     *   switch window, and hiding first is what makes the hand-off safe in one direction;
     * - hook mode → paint;
     * - manager unreachable → paint (see the class note: fallback, not overlap).
     */
    fun mustHide(): Boolean {
        if (unreachable) return false
        if (!overlaySelected) return false
        return true
    }

    /**
     * Generation to acknowledge, or 0 when nothing has to be acknowledged.
     *
     * The game acknowledges the generation it has *hidden for*. It is deliberately sent
     * whether or not the overlay already holds the surface: the acknowledgement is also how
     * the manager learns that an in-game control exists at all, so the first switch to
     * overlay cannot paint both surfaces while it waits to "see" a game that never speaks.
     */
    fun generationToAcknowledge(): Int {
        if (unreachable) return 0
        if (!overlaySelected) return 0
        return generation
    }

    /** The overlay holds a renewed claim: it is on screen and this side must stay down. */
    fun overlayOwnsSurface(): Boolean =
        owner == DisplayModeOwner.OVERLAY && grantedGeneration == generation &&
            clock() - renewedAt <= AssistantDisplayModeSettings.LEASE_MS

    /** Diagnostics text; contains no game data. */
    fun describe(): String = buildString {
        append("generation=").append(generation)
        append(";owner=").append(when (owner) {
            DisplayModeOwner.OVERLAY -> "overlay"
            DisplayModeOwner.HOOK -> "hook"
            else -> "none"
        })
        append(";selected=").append(overlaySelected)
        append(";hide=").append(mustHide())
        append(";ack=").append(generationToAcknowledge())
        append(";unreachable=").append(unreachable)
    }

    /**
     * One status reply, reduced to the values the lease needs. [generation] is the
     * generation the manager stamped the decision with, never the time of the reply.
     */
    data class Reply(
        val overlaySelected: Boolean,
        val owner: Int,
        val generation: Int,
        val overlayUnavailable: Boolean,
        val unreachable: Boolean,
    )

    companion object {
        const val OWNER_NONE = DisplayModeOwner.NONE
        const val OWNER_OVERLAY = DisplayModeOwner.OVERLAY
        const val OWNER_HOOK = DisplayModeOwner.HOOK
        const val LEASE_MS = AssistantDisplayModeSettings.LEASE_MS
    }
}
