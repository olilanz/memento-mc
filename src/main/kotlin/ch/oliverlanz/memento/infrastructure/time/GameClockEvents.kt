package ch.oliverlanz.memento.infrastructure.time

/**
 * Application-level event bus for high-frequency clock updates.
 *
 * These updates are transport signals for application components.
 * They MUST NOT be used by domain logic.
 */
object GameClockEvents {

    private val listeners = linkedSetOf<(GameClock) -> Unit>()

    fun subscribe(listener: (GameClock) -> Unit) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: (GameClock) -> Unit) {
        listeners.remove(listener)
    }

    internal fun publish(clock: GameClock) {
        val snapshot = listeners.toList()
        for (l in snapshot) l(clock)
    }
}
