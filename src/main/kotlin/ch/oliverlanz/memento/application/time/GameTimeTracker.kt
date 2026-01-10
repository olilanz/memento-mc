package ch.oliverlanz.memento.application.time

import ch.oliverlanz.memento.domain.events.GameDayAdvanced
import ch.oliverlanz.memento.domain.events.GameTimeDomainEvents
import ch.oliverlanz.memento.infrastructure.MementoConstants
import net.minecraft.server.MinecraftServer

/**
 * Interprets overworld time and emits semantic day-advance events
 * whenever the renewal checkpoint is crossed.
 *
 * Restores original Memento semantics:
 * - one domain day == one checkpoint crossing
 * - robust to /time add and large jumps
 * - independent of server start time
 */
class GameTimeTracker {

    private var server: MinecraftServer? = null

    private var lastObservedTime: Long? = null
    private var lastObservedCheckpointIndex: Long? = null

    fun attach(server: MinecraftServer) {
        this.server = server
        this.lastObservedTime = null
        this.lastObservedCheckpointIndex = null
    }

    fun detach() {
        this.server = null
        this.lastObservedTime = null
        this.lastObservedCheckpointIndex = null
    }

    fun tick() {
        val server = server ?: return
        val world = server.overworld ?: return

        val time = world.time

        // --- semantic day advancement (checkpoint crossing) ---
        val checkpointIndex = computeCheckpointIndex(time)

        val lastIndex = lastObservedCheckpointIndex
        if (lastIndex != null) {
            val deltaDays = (checkpointIndex - lastIndex).toInt()
            if (deltaDays > 0) {
                // Emit one semantic day event per crossed checkpoint.
                // This keeps stone maturity semantics stable even when time jumps (e.g., /time add 48000).
                for (i in 1..deltaDays) {
                    GameTimeDomainEvents.publish(
                        GameDayAdvanced(
                            deltaDays = 1,
                            mementoDayIndex = lastIndex + i
                        )
                    )
                }
            }
        }

        // always advance baseline AFTER comparison
        lastObservedCheckpointIndex = checkpointIndex

        // --- application clock (continuous signal) ---
        val lastTime = lastObservedTime
        val deltaTicks = if (lastTime == null) 0L else (time - lastTime).coerceAtLeast(0L)
        lastObservedTime = time

        GameClockEvents.publish(
            GameClock(
                dayTime = time % MementoConstants.OVERWORLD_DAY_TICKS,
                timeOfDay = time,
                deltaTicks = deltaTicks,
                mementoDayIndex = checkpointIndex
            )
        )
    }

    private fun computeCheckpointIndex(time: Long): Long {
        val dayTicks = MementoConstants.OVERWORLD_DAY_TICKS
        val checkpoint = MementoConstants.RENEWAL_CHECKPOINT_TICK
        val shifted = time - checkpoint
        return if (shifted >= 0) shifted / dayTicks else -1
    }
}
