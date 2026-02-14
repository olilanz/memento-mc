package ch.oliverlanz.memento.infrastructure.pulse

/**
 * Transport snapshot for a pulse emission.
 */
data class PulseClock(
    val tick: Long,
    val cadence: PulseCadence,
)

