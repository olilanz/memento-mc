package ch.oliverlanz.memento.application.stone

import ch.oliverlanz.memento.domain.stones.StoneTopologyHooks
import ch.oliverlanz.memento.infrastructure.MementoConstants
import net.minecraft.server.MinecraftServer

/**
 * Observes overworld time and emits "Memento day" transitions.
 *
 * A Memento day flips at [MementoConstants.RENEWAL_CHECKPOINT_TICK] (03:00 by convention).
 *
 * This observer:
 * - keeps only in-memory state (resets on restart)
 * - emits relative day deltas
 * - does NOT access persistence
 * - does NOT leak time into the domain (only emits "days passed")
 */
class OverworldDayObserver {

    private var server: MinecraftServer? = null

    /** Last observed Memento day index (03:00 boundary). In-memory only. */
    private var lastObservedMementoDay: Long? = null

    fun attach(server: MinecraftServer) {
        this.server = server
        this.lastObservedMementoDay = null
    }

    fun detach() {
        this.server = null
        this.lastObservedMementoDay = null
    }

    fun tick() {
        val server = this.server ?: return
        val overworld = server.overworld ?: return

        val timeOfDay = overworld.timeOfDay

        // Shift time so integer day boundaries align at the renewal checkpoint tick.
        val shift = MementoConstants.OVERWORLD_DAY_TICKS - MementoConstants.RENEWAL_CHECKPOINT_TICK
        val mementoDay = (timeOfDay + shift) / MementoConstants.OVERWORLD_DAY_TICKS

        val last = lastObservedMementoDay
        if (last == null) {
            lastObservedMementoDay = mementoDay
            return
        }

        val deltaDays = (mementoDay - last).toInt()
        if (deltaDays > 0) {
            lastObservedMementoDay = mementoDay
            StoneTopologyHooks.onNightlyCheckpoint(deltaDays)
        }
    }
}
