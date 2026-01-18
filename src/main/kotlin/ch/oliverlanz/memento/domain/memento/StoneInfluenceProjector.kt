package ch.oliverlanz.memento.domain.memento

import ch.oliverlanz.memento.domain.stones.StoneTopology

/**
 * Superimposes stone influence onto a substrate.
 *
 * Slice 1 semantics:
 * - Produces two booleans per chunk.
 * - Implementation is a tracer-bullet placeholder (deterministic but not yet spatially correct).
 */
object StoneInfluenceProjector {

    fun project(substrate: WorldMementoSubstrate): WorldMementoTopology {
        // Touching StoneTopology here is important: this stage is domain-owned.
        // Real spatial projection will replace the placeholder logic in later slices.
        val stonesCount = StoneTopology.list().size

        val entries = substrate.snapshot().map { (key, signals) ->
            val seed = (key.chunkX * 31 + key.chunkZ * 17 + key.regionX * 13 + key.regionZ) xor stonesCount
            val hasLore = seed % 11 == 0
            val hasWither = seed % 7 == 0
            ChunkMementoView(
                key = key,
                signals = signals,
                influence = StoneInfluenceFlags(
                    hasWitherstoneInfluence = hasWither,
                    hasLorestoneInfluence = hasLore,
                )
            )
        }

        return WorldMementoTopology(entries)
    }
}
