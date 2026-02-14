package ch.oliverlanz.memento.domain.stones

import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import kotlin.reflect.KClass

/**
 * Read-only projection surface for stone influence at chunk granularity.
 *
 * Authority boundary:
 * - Influence semantics and dominance rules remain owned by [StoneTopology].
 * - This service does not mutate stone state and does not combine with factual world-map data.
 */
object StoneMapService {

    /**
     * Returns the influenced chunks for a specific stone identity in its own dimension.
     *
     * This is a read-only projection of [StoneTopology.influenceSnapshot].
     */
    fun influencedChunks(stone: StoneView): Set<ChunkPos> {
        return StoneTopology
            .influenceSnapshot()
            .dimensions[stone.dimension]
            ?.byStone
            ?.get(stone.name)
            ?: emptySet()
    }

    /**
     * Returns the dominant stone kind per influenced chunk for the given dimension.
     *
     * This is a read-only projection of [StoneTopology.influenceSnapshot].
     */
    fun dominantByChunk(
        dimension: RegistryKey<World>
    ): Map<ChunkPos, KClass<out Stone>> {
        return StoneTopology
            .influenceSnapshot()
            .dimensions[dimension]
            ?.dominantByChunk
            ?: emptyMap()
    }
}
