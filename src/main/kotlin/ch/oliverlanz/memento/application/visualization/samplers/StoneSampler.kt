package ch.oliverlanz.memento.application.visualization.samplers

import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import java.util.Random

/**
 * Deterministic spatial selector rooted in a single stone.
 *
 * Locked semantics:
 * - Samplers never emit particles and have no time dimension.
 * - Samplers discover full geometry and return ordered immutable candidates.
 * - Samplers are geometry-only and do not interpret dominance.
 * - Materialization can apply one-time probabilistic reduction at effect init.
 * - Materialized order is stable for the effect lifetime.
 */
interface StoneSampler {

    /**
     * Returns the full set of eligible block positions, in deterministic order.
     *
     * Implementations may compute this lazily and cache the immutable list.
     */
    fun candidates(world: ServerWorld): List<BlockPos>

    /**
     * One-time materialization hook used by effect initialization.
     *
     * Default behavior keeps source ordering and applies optional probabilistic reduction.
     */
    fun materialize(
        world: ServerWorld,
        random: Random,
        config: SamplerMaterializationConfig = SamplerMaterializationConfig(),
    ): List<BlockPos> {
        val source = candidates(world)
        if (source.isEmpty()) return emptyList()

        val detail = config.detail.coerceIn(0.0, 1.0)
        if (detail >= 1.0) return source
        if (detail <= 0.0) return emptyList()

        val out = ArrayList<BlockPos>(source.size)
        for (pos in source) {
            if (random.nextDouble() <= detail) {
                out.add(pos)
            }
        }
        return out.toList()
    }

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

data class SamplerMaterializationConfig(
    /** [0.0..1.0] where 1.0 keeps all candidates and 0.5 keeps ~50% on average. */
    val detail: Double = 1.0,
)
