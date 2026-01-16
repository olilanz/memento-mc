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

/**
 * Explicit game-time value types for transport and visualization.
 *
 * These types are intentionally lightweight and colocated with GameClock to keep
 * time semantics close to where clock signals are produced.
 */

/** Raw game ticks as reported by the server clock signals. */
@JvmInline
value class GameTicks(val value: Long) {
    init {
        require(value >= 0L) { "GameTicks must be >= 0 (was $value)" }
    }
}

/** Game-time duration expressed in Minecraft "game hours" (1 hour = 1000 ticks). */
@JvmInline
value class GameHours(val value: Double) {
    init {
        require(!value.isNaN()) { "GameHours must not be NaN" }
        require(value >= 0.0) { "GameHours must be >= 0 (was $value)" }
    }
}

/** Game-time duration expressed in real seconds (20 ticks = 1 second). */
@JvmInline
value class GameSeconds(val value: Double) {
    init {
        require(!value.isNaN()) { "GameSeconds must not be NaN" }
        require(value >= 0.0) { "GameSeconds must be >= 0 (was $value)" }
    }
}

object GameTimeUnits {
    /** Vanilla: 20 game ticks per real second. */
    const val TICKS_PER_SECOND: Long = 20L

    /** Vanilla: 1000 game ticks per "game hour" (1/24 of a day). */
    const val TICKS_PER_GAME_HOUR: Long = 1000L
}

fun GameTicks.toGameHours(): GameHours = GameHours(value.toDouble() / GameTimeUnits.TICKS_PER_GAME_HOUR)

fun GameTicks.toGameSeconds(): GameSeconds =
    GameSeconds(value.toDouble() / GameTimeUnits.TICKS_PER_SECOND.toDouble())

fun Long.asGameTicks(): GameTicks = GameTicks(this)

