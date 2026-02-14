package ch.oliverlanz.memento.infrastructure.pulse

/**
 * Infrastructure cadence tiers for pulse transport.
 *
 * This enum is transport-only and does not encode semantic priorities.
 */
enum class PulseCadence {
    REALTIME,
    HIGH,
    MEDIUM,
    LOW,
    VERY_LOW,
    ULTRA_LOW,
}
