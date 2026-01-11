package ch.oliverlanz.memento.application.visualization.samplers

import ch.oliverlanz.memento.domain.stones.StoneView
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos

/**
 * Deterministic spatial selector rooted in a single stone.
 *
 * Samplers never emit particles and have no time dimension.
 * They only provide eligible block positions for an effect to project over time.
 */
interface StoneSampler {

    /**
     * Compute the eligible block positions for this sampler.
     *
     * The returned positions are base blocks (typically ground blocks).
     * Effects apply vertical projection separately.
     */
    fun sample(world: ServerWorld): Set<BlockPos>
}
