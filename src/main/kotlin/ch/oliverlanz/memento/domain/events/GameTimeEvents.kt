package ch.oliverlanz.memento.domain.events

/**
 * Canonical semantic time event emitted when a Memento day boundary is crossed.
 *
 * A "Memento day" flips at the configured renewal checkpoint (03:00 by convention),
 * not at Minecraft midnight.
 *
 * This event is intentionally sparse and domain-relevant.
 * Server ticks are treated as transport and must not leak into domain logic.
 */
data class GameDayAdvanced(
    /** Number of whole Memento days advanced since the last observed boundary. */
    val deltaDays: Int,
    /** The new absolute Memento day index after applying [deltaDays]. */
    val mementoDayIndex: Long,
)

/**
 * Domain-visible event bus for semantic time events.
 *
 * Published by the application-layer GameTimeTracker.
 */
object GameTimeDomainEvents {

    private val dayAdvancedListeners = linkedSetOf<(GameDayAdvanced) -> Unit>()

    fun subscribeToDayAdvanced(listener: (GameDayAdvanced) -> Unit) {
        dayAdvancedListeners.add(listener)
    }

    fun unsubscribeFromDayAdvanced(listener: (GameDayAdvanced) -> Unit) {
        dayAdvancedListeners.remove(listener)
    }

    internal fun publish(event: GameDayAdvanced) {
        val snapshot = dayAdvancedListeners.toList()
        for (l in snapshot) l(event)
    }
}
