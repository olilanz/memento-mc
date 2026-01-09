package ch.oliverlanz.memento.application.visualization

import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.application.time.GameClock
import net.minecraft.server.world.ServerWorld
import org.slf4j.LoggerFactory

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

    private val log = LoggerFactory.getLogger(StoneCreatedAreaEffect::class.java)

    private var emitted: Boolean = false

    override fun tick(world: ServerWorld, clock: GameClock): Boolean {
        if (emitted) return false
        emitted = true

        log.debug(
            "[viz] Emitting one-shot StoneCreated visuals for stone='{}' dim='{}' pos={}",
            stone.name,
            stone.dimension.value,
            stone.position
        )

        StoneParticleEmitters.emitStoneCreated(world, stone)
        return false
    }
}
