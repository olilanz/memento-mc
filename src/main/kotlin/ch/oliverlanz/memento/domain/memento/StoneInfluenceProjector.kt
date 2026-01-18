package ch.oliverlanz.memento.domain.memento

import ch.oliverlanz.memento.domain.stones.StoneTopology
import ch.oliverlanz.memento.domain.stones.Stone
import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import kotlin.reflect.KClass

/**
 * Superimposes stone influence onto a substrate.
 *
 * Slice 2 semantics:
 * - Uses [StoneTopology] as the sole authority for influence dominance.
 * - Projects the dominant stone kind per chunk (as a Kotlin class) onto the substrate.
 * - No duplicated dominance logic exists outside [StoneTopology].
 */
object StoneInfluenceProjector {

    fun project(substrate: WorldMementoSubstrate): WorldMementoTopology {
        // Cache per-dimension dominance maps to avoid repeated lookups per row.
        val dominantByChunkByWorld = linkedMapOf<RegistryKey<World>, Map<ChunkPos, KClass<out Stone>>>()

        val entries = substrate.snapshot().map { (key, signals) ->
            val dominantByChunk = dominantByChunkByWorld.getOrPut(key.world) {
                StoneTopology.getInfluencedChunkSet(key.world)
            }

            val dominant = dominantByChunk[ChunkPos(key.chunkX, key.chunkZ)]

            ChunkMementoView(
                key = key,
                signals = signals,
                dominantStoneKind = dominant,
            )
        }

        return WorldMementoTopology(entries)
    }
}
