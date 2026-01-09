package ch.oliverlanz.memento.application.time

import ch.oliverlanz.memento.domain.events.GameDayAdvanced
import ch.oliverlanz.memento.domain.events.GameTimeDomainEvents
import ch.oliverlanz.memento.infrastructure.MementoConstants
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

/**
 * Interprets overworld time and emits:
 *
 * 1) Sparse semantic time events (domain-visible), e.g. day boundary advancements
 * 2) A medium-frequency clock signal (application-only) for smooth progression
 *
 * Server ticks are used only as transport; consumers should subscribe to outputs.
 */
class GameTimeTracker(
    /** Emit [GameClock] updates every N server ticks. Default: 10 (~2/s). */
    private val clockEmitEveryTicks: Int = 10,
) {

    private val log = LoggerFactory.getLogger(GameTimeTracker::class.java)

    private var server: MinecraftServer? = null

    private var tickCounter: Int = 0
    private var lastObservedTimeOfDay: Long? = null
    private var lastObservedMementoDay: Long? = null

    fun attach(server: MinecraftServer) {
        this.server = server
        this.tickCounter = 0
        this.lastObservedTimeOfDay = null
        this.lastObservedMementoDay = null
    }

    fun detach() {
        this.server = null
        this.tickCounter = 0
        this.lastObservedTimeOfDay = null
        this.lastObservedMementoDay = null
    }

    fun tick() {
        val server = this.server ?: return
        val overworld = server.overworld ?: return

        val timeOfDay = overworld.timeOfDay
        val dayTime = timeOfDay % MementoConstants.OVERWORLD_DAY_TICKS

        val mementoDayIndex = computeMementoDayIndex(timeOfDay)

        // -----------------------------------------------------------------
        // Semantic day boundary events (domain-visible)
        // -----------------------------------------------------------------
        val lastMementoDay = lastObservedMementoDay
        if (lastMementoDay == null) {
            lastObservedMementoDay = mementoDayIndex
        } else {
            val deltaDays = (mementoDayIndex - lastMementoDay).toInt()
            if (deltaDays > 0) {

                log.info("[time] Game day advanced: mementoDayIndex={} (deltaDays={})", mementoDayIndex, deltaDays)

                lastObservedMementoDay = mementoDayIndex
                GameTimeDomainEvents.publish(GameDayAdvanced(deltaDays = deltaDays, mementoDayIndex = mementoDayIndex))
            }
        }

        // -----------------------------------------------------------------
        // Medium-frequency clock signal (application-only)
        // -----------------------------------------------------------------
        val lastTime = lastObservedTimeOfDay
        val deltaTicks = if (lastTime == null) 0L else (timeOfDay - lastTime).coerceAtLeast(0L)
        lastObservedTimeOfDay = timeOfDay

        tickCounter++
        if (tickCounter >= clockEmitEveryTicks) {
            tickCounter = 0
            GameClockEvents.publish(
                GameClock(
                    dayTime = dayTime,
                    timeOfDay = timeOfDay,
                    deltaTicks = deltaTicks,
                    mementoDayIndex = mementoDayIndex,
                )
            )
        }
    }

    private fun computeMementoDayIndex(timeOfDay: Long): Long {
        // Shift time so integer day boundaries align at the renewal checkpoint tick.
        val shift = MementoConstants.OVERWORLD_DAY_TICKS - MementoConstants.RENEWAL_CHECKPOINT_TICK
        return (timeOfDay + shift) / MementoConstants.OVERWORLD_DAY_TICKS
    }
}
