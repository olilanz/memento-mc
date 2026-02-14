package ch.oliverlanz.memento.infrastructure.pulse

/**
 * Infrastructure pulse event bus.
 *
 * Cadence events are transport signals only. Subscribers own semantic decisions.
 */
object PulseEvents {

    private val listenersByCadence = linkedMapOf<PulseCadence, LinkedHashSet<(PulseClock) -> Unit>>(
        PulseCadence.REALTIME to linkedSetOf(),
        PulseCadence.HIGH to linkedSetOf(),
        PulseCadence.MEDIUM to linkedSetOf(),
        PulseCadence.LOW to linkedSetOf(),
        PulseCadence.VERY_LOW to linkedSetOf(),
        PulseCadence.ULTRA_LOW to linkedSetOf(),
    )

    fun subscribe(cadence: PulseCadence, listener: (PulseClock) -> Unit) {
        listenersByCadence.getValue(cadence).add(listener)
    }

    fun unsubscribe(cadence: PulseCadence, listener: (PulseClock) -> Unit) {
        listenersByCadence.getValue(cadence).remove(listener)
    }

    internal fun publish(clock: PulseClock) {
        val snapshot = listenersByCadence.getValue(clock.cadence).toList()
        for (listener in snapshot) listener(clock)
    }

    internal fun listenerCount(cadence: PulseCadence): Int =
        listenersByCadence.getValue(cadence).size
}
