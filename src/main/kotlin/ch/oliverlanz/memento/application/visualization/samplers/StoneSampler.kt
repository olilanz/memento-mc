package ch.oliverlanz.memento.application.visualization.samplers

import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import java.util.Random

/**
 * Deterministic spatial selector rooted in a single stone.
 *
 * Locked semantics:
 * - Samplers never emit particles and have no time dimension.
 * - Samplers discover and retain the full set of eligible blocks (no reduction).
 * - The candidate set is immutable and ordered deterministically.
 * - Randomness is applied at accessor time, not at construction time.
 */
interface StoneSampler {

    /**
     * Returns the full set of eligible block positions, in deterministic order.
     *
     * Implementations may compute this lazily and cache the immutable list.
     */
    fun candidates(world: ServerWorld): List<BlockPos>

    /**
     * Returns a random block position from the eligible set, or null if empty.
     */
    fun randomCandidate(world: ServerWorld, random: Random): BlockPos? {
        val list = candidates(world)
        if (list.isEmpty()) return null
        return list[random.nextInt(list.size)]
    }

    /**
     * Returns an iterator over the eligible set, in deterministic order.
     */
    fun iterator(world: ServerWorld): Iterator<BlockPos> = candidates(world).iterator()
}
