package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.domain.worldmap.ChunkMementoView
import ch.oliverlanz.memento.domain.worldmap.WorldMementoMap
import ch.oliverlanz.memento.domain.worldmap.WorldMementoTopology
import ch.oliverlanz.memento.domain.stones.Lorestone
import ch.oliverlanz.memento.domain.stones.Stone
import ch.oliverlanz.memento.domain.stones.StoneTopology
import ch.oliverlanz.memento.domain.stones.Witherstone
import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import kotlin.reflect.KClass

/**
 * Application-layer pipeline stage.
 *
 * Applies stone influence onto an already discovered substrate.
 *
 * Slice 0.9.5 semantics:
 * - Discovery is completed before this stage runs.
 * - Dominance rules are owned by [StoneTopology].
 * - The output is annotated with the convenience flags
 *   [ChunkMementoView.hasLorestoneInfluence] and [ChunkMementoView.hasWitherstoneInfluence].
 */
object StoneInfluenceSuperposition {

    fun apply(substrate: WorldMementoMap): WorldMementoTopology {
        // Cache per-dimension dominance maps to avoid repeated lookups per row.
        val dominantByChunkByWorld = linkedMapOf<RegistryKey<World>, Map<ChunkPos, KClass<out Stone>>>()

        val entries = substrate.snapshot().map { (key, signals) ->
            val dominantByChunk = dominantByChunkByWorld.getOrPut(key.world) {
                StoneTopology.getInfluencedChunkSet(key.world)
            }

            val dominant = dominantByChunk[ChunkPos(key.chunkX, key.chunkZ)]

            val hasLore = dominant == Lorestone::class
            val hasWither = dominant == Witherstone::class

            ChunkMementoView(
                key = key,
                signals = signals,
                dominantStoneKind = dominant,
                hasLorestoneInfluence = hasLore,
                hasWitherstoneInfluence = hasWither,
            )
        }

        return WorldMementoTopology(entries)
    }
}
