package ch.oliverlanz.memento.application.visualization

import net.minecraft.particle.ParticleEffect
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos

/**
 * Emits particles at explicit world positions.
 *
 * This mirrors the parameters used by StoneParticleEmitters
 * surface emission to avoid introducing new visual semantics.
 */
object PositionParticleEmitter {

    fun emit(
        world: ServerWorld,
        pos: BlockPos,
        particle: ParticleEffect
    ) {
        world.spawnParticles(
            particle,
            pos.x + 0.5,
            pos.y + 1.0,
            pos.z + 0.5,
            6,        // count (visible)
            0.15,     // spread X
            0.20,     // spread Y
            0.15,     // spread Z
            0.01      // speed
        )
    }
}