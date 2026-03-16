package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.domain.harness.DomainTestHarness
import ch.oliverlanz.memento.domain.harness.TestWorldModel
import ch.oliverlanz.memento.domain.harness.assertWorldviewConsistency
import ch.oliverlanz.memento.domain.harness.fixtures.WorldFixtureBuilder
import ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry
import ch.oliverlanz.memento.domain.worldmap.DominantStoneEffectSignal
import ch.oliverlanz.memento.domain.worldmap.DominantStoneSignal
import ch.oliverlanz.memento.infrastructure.worldscan.MementoCsvWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RenewalProjectionShuffledIngestionEquivalenceTest {

    @Test
    fun minimal_factual_world_is_order_independent_across_projection_election_and_csv() {
        val world = WorldFixtureBuilder.overworld()
        val model = TestWorldModel.build {
            chunk(world = world, chunkX = 0, chunkZ = 0, inhabitedTimeTicks = 20L, scanTick = 10L)
            chunk(world = world, chunkX = 1, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 11L)
            chunk(
                world = world,
                chunkX = 2,
                chunkZ = 0,
                inhabitedTimeTicks = null,
                scanTick = 12L,
                unresolvedReason = ch.oliverlanz.memento.domain.worldmap.ChunkScanUnresolvedReason.FILE_IO_ERROR,
            )
        }

        assertOrderIndependence(model)
    }

    @Test
    fun two_region_protected_vs_abandoned_world_is_order_independent() {
        val world = WorldFixtureBuilder.overworld()
        val model = TestWorldModel.build {
            // Region A (x=0): inhabited + lore protected
            chunk(world = world, chunkX = 0, chunkZ = 0, inhabitedTimeTicks = 30L, scanTick = 1L)
            chunk(
                world = world,
                chunkX = 1,
                chunkZ = 0,
                inhabitedTimeTicks = 0L,
                scanTick = 2L,
                dominantStone = DominantStoneSignal.LORE,
                dominantStoneEffect = DominantStoneEffectSignal.LORE_PROTECT,
            )

            // Region B (x=2): abandoned
            chunk(world = world, chunkX = 64, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 3L)
            chunk(world = world, chunkX = 65, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 4L)
        }

        assertOrderIndependence(model)
    }

    @Test
    fun invariant_matrix_mixed_region_world_is_order_independent() {
        val world = WorldFixtureBuilder.overworld()
        val model = TestWorldModel.build {
            // Region (0,0): protected, must not purge
            chunk(
                world = world,
                chunkX = 0,
                chunkZ = 0,
                inhabitedTimeTicks = 1L,
                scanTick = 1L,
                dominantStone = DominantStoneSignal.LORE,
                dominantStoneEffect = DominantStoneEffectSignal.LORE_PROTECT,
            )
            chunk(world = world, chunkX = 1, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 2L)

            // Region (2,0): abandoned, should purge
            chunk(world = world, chunkX = 64, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 3L)
            chunk(world = world, chunkX = 65, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 4L)

            // Region (4,0): inhabited, must not purge
            chunk(world = world, chunkX = 128, chunkZ = 0, inhabitedTimeTicks = 50L, scanTick = 5L)
            chunk(world = world, chunkX = 129, chunkZ = 0, inhabitedTimeTicks = 10L, scanTick = 6L)
        }

        assertOrderIndependence(model)
    }

    private fun assertOrderIndependence(model: TestWorldModel) {
        val baseline = runScenario(model)
        val reversed = runScenario(model.copy(chunks = model.chunks.reversed()))
        val shuffled = runScenario(model.copy(chunks = model.chunks.shuffled(java.util.Random(42L))))

        assertEquals(baseline.chunkDerivationByChunk, reversed.chunkDerivationByChunk)
        assertEquals(baseline.chunkDerivationByChunk, shuffled.chunkDerivationByChunk)

        assertEquals(baseline.regionForgettableByRegion, reversed.regionForgettableByRegion)
        assertEquals(baseline.regionForgettableByRegion, shuffled.regionForgettableByRegion)

        assertEquals(baseline.rankedCandidates, reversed.rankedCandidates)
        assertEquals(baseline.rankedCandidates, shuffled.rankedCandidates)

        assertEquals(baseline.csv, reversed.csv)
        assertEquals(baseline.csv, shuffled.csv)
    }

    private fun runScenario(model: TestWorldModel): ScenarioResult {
        val harness = DomainTestHarness()
        harness.ingest(model)
        harness.runUntilIdle()

        val committed = harness.committedView()
        assertNotNull(committed)

        val snapshot = toSnapshot(model)
        val csv = MementoCsvWriter.renderOperatorWorldviewCsv(snapshot, committed)

        assertWorldviewConsistency(
            discoveredChunkKeys = snapshot.map { it.key }.toSet(),
            projectionSnapshot = committed,
            csv = csv,
        )

        return ScenarioResult(
            chunkDerivationByChunk = committed.chunkDerivationByChunk,
            regionForgettableByRegion = committed.regionForgettableByRegion,
            rankedCandidates = committed.rankedCandidates,
            csv = csv,
        )
    }

    private fun toSnapshot(model: TestWorldModel): List<ChunkScanSnapshotEntry> {
        return model.chunks.map { fact ->
            ChunkScanSnapshotEntry(
                key = fact.key,
                signals = fact.signals,
                dominantStone = fact.dominantStone ?: DominantStoneSignal.NONE,
                dominantStoneEffect = fact.dominantStoneEffect ?: DominantStoneEffectSignal.NONE,
                scanTick = fact.scanTick,
                provenance = fact.source,
                unresolvedReason = fact.unresolvedReason,
            )
        }
    }

    private data class ScenarioResult(
        val chunkDerivationByChunk: Map<ch.oliverlanz.memento.domain.worldmap.ChunkKey, RenewalChunkDerivation>,
        val regionForgettableByRegion: Map<RegionKey, Boolean>,
        val rankedCandidates: List<RenewalRankedCandidate>,
        val csv: String,
    )
}

