package ch.oliverlanz.memento.application.visualization

import ch.oliverlanz.memento.domain.stones.StoneView
import net.minecraft.server.world.ServerWorld

/**
 * One-shot area effect used to preserve current visualization behavior while the engine
 * is refactored toward a tick-driven lifecycle model.
 *
 * Emits the same particles that were previously spawned directly on the StoneCreated event,
 * then terminates immediately.
 */
class StoneCreatedAreaEffect(
    stone: StoneView,
) : VisualAreaEffect(stone) {

    private var emitted: Boolean = false

    override fun tick(world: ServerWorld, worldTime: Long): Boolean {
        if (emitted) return false
        emitted = true

        StoneParticleEmitters.emitStoneCreated(world, stone)
        return false
    }
}
