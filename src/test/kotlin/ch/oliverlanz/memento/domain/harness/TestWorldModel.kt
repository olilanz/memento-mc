package ch.oliverlanz.memento.domain.harness

import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.DominantStoneEffectSignal
import ch.oliverlanz.memento.domain.worldmap.DominantStoneSignal
import ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance
import ch.oliverlanz.memento.domain.worldmap.ChunkScanUnresolvedReason
import ch.oliverlanz.memento.domain.worldmap.ChunkSignals
import net.minecraft.registry.RegistryKey
import net.minecraft.world.World

/**
 * Canonical scenario host for deterministic domain-harness tests.
 *
 * Source-of-truth constraints:
 * - Stores only synthetic world input facts.
 * - Never stores derived projection/election outputs as source truth.
 * - Keeps world/region/chunk identity stable for deterministic replay.
 */
data class TestWorldModel(
    val chunks: List<TestChunkFact>,
) {
    data class TestChunkFact(
        val key: ChunkKey,
        val scanTick: Long,
        val source: ChunkScanProvenance,
        val unresolvedReason: ChunkScanUnresolvedReason? = null,
        val signals: ChunkSignals? = null,
        val dominantStone: DominantStoneSignal? = null,
        val dominantStoneEffect: DominantStoneEffectSignal? = null,
    )

    companion object {
        fun build(block: Builder.() -> Unit): TestWorldModel = Builder().apply(block).build()
    }

    class Builder {
        private val chunks = linkedMapOf<ChunkKey, TestChunkFact>()

        fun chunk(
            world: RegistryKey<World>,
            chunkX: Int,
            chunkZ: Int,
            inhabitedTimeTicks: Long? = null,
            isSpawnChunk: Boolean = false,
            scanTick: Long = 1L,
            source: ChunkScanProvenance = ChunkScanProvenance.FILE_PRIMARY,
            unresolvedReason: ChunkScanUnresolvedReason? = null,
            lastUpdateTicks: Long? = null,
            surfaceY: Int? = null,
            biomeId: String? = null,
            dominantStone: DominantStoneSignal? = null,
            dominantStoneEffect: DominantStoneEffectSignal? = null,
        ) {
            val key = ChunkKey(
                world = world,
                regionX = Math.floorDiv(chunkX, 32),
                regionZ = Math.floorDiv(chunkZ, 32),
                chunkX = chunkX,
                chunkZ = chunkZ,
            )

            chunks[key] = TestChunkFact(
                key = key,
                scanTick = scanTick,
                source = source,
                unresolvedReason = unresolvedReason,
                signals = inhabitedTimeTicks?.let {
                    ChunkSignals(
                        inhabitedTimeTicks = it,
                        lastUpdateTicks = lastUpdateTicks,
                        surfaceY = surfaceY,
                        biomeId = biomeId,
                        isSpawnChunk = isSpawnChunk,
                    )
                },
                dominantStone = dominantStone,
                dominantStoneEffect = dominantStoneEffect,
            )
        }

        fun build(): TestWorldModel {
            val ordered = chunks.values.sortedWith(
                compareBy<TestChunkFact> { it.key.world.value.toString() }
                    .thenBy { it.key.regionX }
                    .thenBy { it.key.regionZ }
                    .thenBy { it.key.chunkX }
                    .thenBy { it.key.chunkZ }
            )
            return TestWorldModel(chunks = ordered)
        }
    }
}
