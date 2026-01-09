package ch.oliverlanz.memento.application.time

/**
 * Snapshot of game time, derived from the overworld.
 *
 * This is a transport-friendly clock signal for application components
 * (e.g. visualization) that need smooth progression.
 *
 * It is intentionally NOT a domain event.
 */
data class GameClock(
    /** Absolute overworld day time (0..23999), as of this update. */
    val dayTime: Long,
    /** Absolute overworld time-of-day (monotonic in vanilla), in ticks. */
    val timeOfDay: Long,
    /** Delta in overworld time-of-day ticks since the previous update (clamped to >= 0). */
    val deltaTicks: Long,
    /** Current absolute Memento day index (03:00 boundary). */
    val mementoDayIndex: Long,
)
