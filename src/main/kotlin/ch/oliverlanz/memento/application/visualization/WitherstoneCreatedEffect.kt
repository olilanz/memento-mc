package ch.oliverlanz.memento.application.visualization

import ch.oliverlanz.memento.domain.stones.WitherstoneView
import ch.oliverlanz.memento.application.time.GameClock
import net.minecraft.server.world.ServerWorld
import org.slf4j.LoggerFactory

class WitherstoneCreatedEffect(
    stone: WitherstoneView
) : VisualAreaEffect(stone) {

    private val log = LoggerFactory.getLogger(javaClass)
    private var emitted = false

    override fun tick(world: ServerWorld, clock: GameClock): Boolean {
        if (!advanceLifetime()) return false

        if (!emitted) {
            emitted = true
            log.debug(
                "[viz] Emitting WitherstoneCreated visuals for stone='{}' dim='{}' pos={}",
                stone.name,
                stone.dimension.value,
                stone.position
            )
            StoneParticleEmitters.emitStoneCreated(world, stone)
        }
        return true
    }
}