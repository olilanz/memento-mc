package ch.oliverlanz.memento.domain.events

import ch.oliverlanz.memento.domain.stones.WitherstoneState
import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

/**
 * Canonical structured event emitted by the shadow StoneRegister.
 *
 * Single event type only: lifecycle state transitions.
 * Domain code does not log; logging is attached by subscribers.
 */
data class WitherstoneStateTransition(
    val stoneName: String,
    val dimension: RegistryKey<World>,
    val position: BlockPos,
    val from: WitherstoneState,
    val to: WitherstoneState,
    val trigger: WitherstoneTransitionTrigger,
)

enum class WitherstoneTransitionTrigger {
    OPERATOR,
    NIGHTLY_CHECKPOINT,
    ADMIN_TIME_ADJUSTMENT,
    SERVER_STARTUP,
    RENEWAL_COMPLETED,
}

object StoneDomainEvents {
    private val listeners = linkedSetOf<(WitherstoneStateTransition) -> Unit>()

    fun subscribe(listener: (WitherstoneStateTransition) -> Unit) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: (WitherstoneStateTransition) -> Unit) {
        listeners.remove(listener)
    }

    // Wrappers used by StoneRegisterHooks (kept for readability)
    fun subscribeToWitherstoneTransitions(listener: (WitherstoneStateTransition) -> Unit) = subscribe(listener)
    fun unsubscribeFromWitherstoneTransitions(listener: (WitherstoneStateTransition) -> Unit) = unsubscribe(listener)

    internal fun publish(event: WitherstoneStateTransition) {
        val snapshot = listeners.toList()
        for (l in snapshot) l(event)
    }
}
