package ch.oliverlanz.memento.application.visualization.effects
import ch.oliverlanz.memento.application.visualization.emitters.StoneParticleEmitters
import ch.oliverlanz.memento.application.visualization.emitters.SurfaceParticleEmitter
import ch.oliverlanz.memento.application.visualization.emitters.PositionParticleEmitter

import ch.oliverlanz.memento.domain.stones.WitherstoneView
import ch.oliverlanz.memento.application.time.GameClock
import net.minecraft.server.world.ServerWorld
import net.minecraft.particle.ParticleTypes
import kotlin.random.Random

class WitherstonePlacementEffect(
    stone: WitherstoneView
) : EffectBase(stone) {

    private val anchorEmissionChance = 0.15
    private val surfaceEmissionChance = 0.05


    init {
        // ~1 in-game hour (1000 ticks).
        withLifetime(1000)
    }

    override fun tick(world: ServerWorld, clock: GameClock): Boolean {
        if (!advanceLifetime(clock.deltaTicks)) return false

        // Anchor presence
        if (Random.nextDouble() < anchorEmissionChance) {
            StoneParticleEmitters.emitStoneCreated(world, stone)
        }

        // Surface presence (single chunk)
        if (Random.nextDouble() < surfaceEmissionChance) {
            val chunkX = stone.position.x shr 4
            val chunkZ = stone.position.z shr 4

            SurfaceParticleEmitter.emitRandomSurfacePosition(
                world,
                chunkX,
                chunkZ
            ) { surfacePos ->
                PositionParticleEmitter.emit(
                    world,
                    surfacePos,
                    ParticleTypes.ASH
                )
            }
        }

        return true
    }
}
