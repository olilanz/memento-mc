package ch.oliverlanz.memento.domain.events

import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.domain.stones.WitherstoneState
import ch.oliverlanz.memento.domain.stones.WitherstoneView

/**
 * Canonical structured event emitted when a stone is created (first time a name appears).
 */
data class StoneCreated(
    val stone: StoneView,
)

/**
 * Canonical structured event emitted by the shadow StoneTopology.
 *
 * Single event type only: lifecycle state transitions.
 * Domain code does not log; logging is attached by subscribers.
 */
data class WitherstoneStateTransition(
    val stone: WitherstoneView,
    val from: WitherstoneState,
    val to: WitherstoneState,
    val trigger: WitherstoneTransitionTrigger,
)

enum class WitherstoneTransitionTrigger {
    SERVER_START,
    NIGHTLY_TICK,
    OP_COMMAND,
    RENEWAL_COMPLETED,
}

object StoneDomainEvents {

    private val witherstoneTransitionListeners = linkedSetOf<(WitherstoneStateTransition) -> Unit>()
    private val stoneCreatedListeners = linkedSetOf<(StoneCreated) -> Unit>()

    // ---------------------------------------------------------------------
    // Witherstone lifecycle transitions
    // ---------------------------------------------------------------------

    fun subscribe(listener: (WitherstoneStateTransition) -> Unit) {
        witherstoneTransitionListeners.add(listener)
    }

    fun unsubscribe(listener: (WitherstoneStateTransition) -> Unit) {
        witherstoneTransitionListeners.remove(listener)
    }

    // Wrappers used by StoneTopologyHooks (kept for readability)
    fun subscribeToWitherstoneTransitions(listener: (WitherstoneStateTransition) -> Unit) = subscribe(listener)
    fun unsubscribeFromWitherstoneTransitions(listener: (WitherstoneStateTransition) -> Unit) = unsubscribe(listener)

    internal fun publish(event: WitherstoneStateTransition) {
        val snapshot = witherstoneTransitionListeners.toList()
        for (l in snapshot) l(event)
    }

    // ---------------------------------------------------------------------
    // Stone creation
    // ---------------------------------------------------------------------

    fun subscribeToStoneCreated(listener: (StoneCreated) -> Unit) {
        stoneCreatedListeners.add(listener)
    }

    fun unsubscribeFromStoneCreated(listener: (StoneCreated) -> Unit) {
        stoneCreatedListeners.remove(listener)
    }

    internal fun publish(event: StoneCreated) {
        val snapshot = stoneCreatedListeners.toList()
        for (l in snapshot) l(event)
    }
}
