package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.application.time.GameClock
import net.minecraft.server.world.ServerWorld
import org.slf4j.LoggerFactory

/**
 * Shared base for stone creation visuals.
 * Owns lifecycle and one-time emission mechanics.
 */
abstract class StonePlacementEffect(
    stone: StoneView
) : VisualAreaEffect(stone) {

    protected val log = LoggerFactory.getLogger(javaClass)
    private var emitted = false

    init {
        // ~10 seconds at 20 TPS
        withLifetime(200)
    }

    final override fun tick(world: ServerWorld, clock: GameClock): Boolean {
        if (!advanceLifetime(clock.deltaTicks)) return false

        if (!emitted) {
            emitted = true
            emitOnce(world)
        }
        return true
    }

    protected abstract fun emitOnce(world: ServerWorld)
}