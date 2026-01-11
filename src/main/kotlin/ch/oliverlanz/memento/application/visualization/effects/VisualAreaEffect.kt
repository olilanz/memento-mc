package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.application.time.GameClock
import net.minecraft.server.world.ServerWorld

/**
 * Base type for long-lived visualization projections.
 *
 * Owns optional lifecycle (finite or infinite).
 * Concrete effects decide behavior by implementing tick().
 */
abstract class VisualAreaEffect(
    val stone: StoneView,
) {

    /**
     * Remaining lifetime in ticks.
     *
     * null  -> infinite lifetime
     * >= 0  -> finite lifetime
     */
    protected var remainingTicks: Long? = null

    /**
     * Configure a finite lifetime for this effect.
     */
    protected fun withLifetime(ticks: Long) {
        remainingTicks = ticks
    }

    /**
     * Advances lifetime by one tick.
     *
     * @return true if the effect should continue, false if expired
     */
    protected fun advanceLifetime(deltaTicks: Long): Boolean {
        val rt = remainingTicks ?: return true
        remainingTicks = rt - deltaTicks
        return remainingTicks!! > 0
    }

    /**
     * Perform one clock-driven visual update.
     *
     * @return true to keep the effect alive, false to terminate and remove it
     */
    abstract fun tick(world: ServerWorld, clock: GameClock): Boolean
}
