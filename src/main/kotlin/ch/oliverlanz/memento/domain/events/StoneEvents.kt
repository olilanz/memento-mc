package ch.oliverlanz.memento.domain.events

import ch.oliverlanz.memento.domain.stones.StoneView

/**
 * Canonical lifecycle state shared across all stones.
 *
 * This is intentionally domain-near: stones are not CRUD entities.
 * They move through a lifecycle from being placed into the world to being consumed.
 */
enum class StoneLifecycleState {
    PLACED,
    MATURING,
    MATURED,
    CONSUMED,
}

/**
 * Canonical triggers for lifecycle transitions.
 *
 * Triggers are observational metadata, not branching semantics.
 */
enum class StoneLifecycleTrigger {
    SERVER_START,
    NIGHTLY_TICK,
    OP_COMMAND,
    RENEWAL_COMPLETED,
    MANUAL_REMOVE,
}

/**
 * Canonical structured event emitted by StoneAuthority.
 *
 * Single event type only: lifecycle state transitions.
 * Domain code does not log; logging is attached by subscribers.
 */
data class StoneLifecycleTransition(
    val stone: StoneView,
    val from: StoneLifecycleState?,
    val to: StoneLifecycleState,
    val trigger: StoneLifecycleTrigger,
)

object StoneDomainEvents {

    private val lifecycleListeners = linkedSetOf<(StoneLifecycleTransition) -> Unit>()

    fun subscribeToLifecycleTransitions(listener: (StoneLifecycleTransition) -> Unit) {
        lifecycleListeners.add(listener)
    }

    fun unsubscribeFromLifecycleTransitions(listener: (StoneLifecycleTransition) -> Unit) {
        lifecycleListeners.remove(listener)
    }

    internal fun publish(event: StoneLifecycleTransition) {
        val snapshot = lifecycleListeners.toList()
        for (l in snapshot) l(event)
    }
}
