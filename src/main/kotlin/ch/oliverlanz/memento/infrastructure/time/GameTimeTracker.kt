package ch.oliverlanz.memento.infrastructure.time

import ch.oliverlanz.memento.domain.events.GameDayAdvanced
import ch.oliverlanz.memento.domain.events.GameTimeDomainEvents
import ch.oliverlanz.memento.MementoConstants
import net.minecraft.server.MinecraftServer
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import kotlin.math.max

/**
 * Tracks game time for Memento.
 *
 * Responsibilities (locked):
 * - Publish high-frequency transport clock updates via [GameClockEvents] (visualization, UI).
 * - Publish low-frequency semantic day events via [GameTimeDomainEvents] (domain maturity).
 *
 * Semantics (locked):
 * - A "Memento day" flips at [MementoConstants.RENEWAL_CHECKPOINT_TICK] (03:00 by convention).
 * - Time may jump forward multiple days (e.g. /time add). We MUST emit one day event per crossed day.
 */
class GameTimeTracker {

    private var server: MinecraftServer? = null

    /** Last observed absolute overworld time-of-day (monotonic tick counter). */
    private var lastTimeOfDay: Long? = null

    /** Last observed absolute Memento day index (03:00 boundary). */
    private var lastMementoDayIndex: Long? = null

    /** Pulse counter used to throttle high-frequency clock transport publication. */
    private var transportPulseCounter: Long = 0L

    fun attach(server: MinecraftServer) {
        this.server = server

        val overworld = server.overworld ?: return
        val timeOfDay = overworld.timeOfDay
        val mementoDayIndex = computeMementoDayIndex(timeOfDay)

        lastTimeOfDay = timeOfDay
        lastMementoDayIndex = mementoDayIndex
        transportPulseCounter = 0L

        MementoLog.debug(
            MementoConcept.WORLD,
            "attached worldTicks={} dayTicks={} checkpointTick={} mementoDayIndex={}",
            timeOfDay,
            timeOfDay % MementoConstants.OVERWORLD_DAY_TICKS,
            MementoConstants.RENEWAL_CHECKPOINT_TICK,
            mementoDayIndex
        )
    }

    fun detach() {
        server = null
        lastTimeOfDay = null
        lastMementoDayIndex = null
        transportPulseCounter = 0L
    }

    /**
     * Transport tick only (NO domain logic here).
     *
     * - Publishes [GameClock] at a bounded transport cadence.
     * - Publishes [GameDayAdvanced] only when Memento-day boundaries are crossed.
     */
    fun tick() {
        val s = server ?: return
        val overworld = s.overworld ?: return

        transportPulseCounter += 1L

        val timeOfDay = overworld.timeOfDay
        val dayTime = timeOfDay % MementoConstants.OVERWORLD_DAY_TICKS

        val mementoDayIndex = computeMementoDayIndex(timeOfDay)

        val publishPeriod = MementoConstants.GAME_CLOCK_PUBLISH_EVERY_HIGH_PULSES
        val shouldPublishClock =
            (publishPeriod <= 1L) || ((transportPulseCounter % publishPeriod) == 0L)

        if (shouldPublishClock) {
            val prevTimeOfDay = lastTimeOfDay
            val deltaTicks = if (prevTimeOfDay == null) 0L else max(0L, timeOfDay - prevTimeOfDay)

            GameClockEvents.publish(
                GameClock(
                    dayTime = dayTime,
                    timeOfDay = timeOfDay,
                    deltaTicks = deltaTicks,
                    mementoDayIndex = mementoDayIndex
                )
            )
            lastTimeOfDay = timeOfDay
        }

        // Low-frequency semantic day advancement.
        val lastDay = lastMementoDayIndex
        if (lastDay == null) {
            lastMementoDayIndex = mementoDayIndex
            return
        }

        val deltaDays = (mementoDayIndex - lastDay).toInt()
        if (deltaDays > 0) {
            MementoLog.debug(
                MementoConcept.WORLD,
                "day advanced previousDay={} currentDay={} deltaDays={} worldTicks={} dayTicks={} checkpointTick={}",
                lastDay,
                mementoDayIndex,
                deltaDays,
                timeOfDay,
                dayTime,
                MementoConstants.RENEWAL_CHECKPOINT_TICK
            )

            // Emit one semantic day event per crossed day to preserve domain step semantics.
            for (i in 1..deltaDays) {
                val emittedDayIndex = lastDay + i
                GameTimeDomainEvents.publishDayAdvanced(
                    GameDayAdvanced(
                        deltaDays = 1,
                        mementoDayIndex = emittedDayIndex
                    )
                )
            }

            lastMementoDayIndex = mementoDayIndex
        }
    }

    private fun computeMementoDayIndex(timeOfDay: Long): Long {
        // Shift time so integer day boundaries align at the renewal checkpoint tick.
        val shift = MementoConstants.OVERWORLD_DAY_TICKS - MementoConstants.RENEWAL_CHECKPOINT_TICK
        return (timeOfDay + shift) / MementoConstants.OVERWORLD_DAY_TICKS
    }
}
