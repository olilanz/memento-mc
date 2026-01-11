package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.time.GameClock
import ch.oliverlanz.memento.application.visualization.emitters.PositionParticleEmitter
import ch.oliverlanz.memento.application.visualization.emitters.StoneParticleEmitters
import ch.oliverlanz.memento.application.visualization.emitters.SurfaceParticleEmitter
import ch.oliverlanz.memento.domain.stones.LorestoneView
import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.world.ServerWorld
import kotlin.random.Random


/**
 * Visualization shown while a Witherstone is matured and waiting for renewal to progress.
 *
 * For this slice, we keep sampling identical to placement visuals:
 * - anchor above the stone
 * - surface sampling on the stone's chunk
 *
 * The visual language will be refined later; we only ensure wiring and lifecycle now.
 */
class WitherstoneWaitingEffect(
    stone: StoneView
) : EffectBase(stone) {

    private val anchorEmissionChance = 0.20
    private val surfaceEmissionChance = 0.08

    init {
        // ~1 in-game hour (1000 ticks).
        withLifetime(1_000)
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
                    ParticleTypes.ANGRY_VILLAGER
                )
            }
        }

        return true
    }
}