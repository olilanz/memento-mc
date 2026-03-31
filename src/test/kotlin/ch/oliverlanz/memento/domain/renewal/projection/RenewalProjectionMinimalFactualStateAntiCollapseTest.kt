package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.domain.harness.DomainTestHarness
import ch.oliverlanz.memento.domain.harness.TestWorldModel
import ch.oliverlanz.memento.domain.harness.assertWorldviewConsistency
import ch.oliverlanz.memento.domain.harness.fixtures.WorldFixtureBuilder
import ch.oliverlanz.memento.infrastructure.worldscan.MementoCsvWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Locks anti-collapse invariants for minimal factual world states.
 *
 * Invariants under test:
 * - Scan-state semantics remain distinct (`nonzero`, `zero`, `unresolved`, `missing`).
 * - CSV row universe equals discovered factual chunk universe.
 * - Projection/election/CSV remain deterministic for equivalent fact sets.
 *
 * Boundary under test:
 * - Domain projection + CSV mapping behavior only.
 *
 * Non-goals:
 * - Application command behavior and Minecraft integration runtime are not covered.
 */
class RenewalProjectionMinimalFactualStateAntiCollapseTest {

    @Test
    fun minimal_world_preserves_nonzero_zero_unresolved_and_missing_distinctions() {
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

        val harness = DomainTestHarness()
        harness.ingest(model)
        harness.runUntilIdle()

        val committed = harness.committedView()
        assertNotNull(committed)

        val discoveredSnapshot = model.chunks.map { fact ->
            ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry(
                key = fact.key,
                signals = fact.signals,
                dominantStone = fact.dominantStone ?: ch.oliverlanz.memento.domain.worldmap.DominantStoneSignal.NONE,
                dominantStoneEffect = fact.dominantStoneEffect ?: ch.oliverlanz.memento.domain.worldmap.DominantStoneEffectSignal.NONE,
                scanTick = fact.scanTick,
                provenance = fact.source,
                unresolvedReason = fact.unresolvedReason,
            )
        }

        val csv = MementoCsvWriter.renderOperatorWorldviewCsv(discoveredSnapshot, committed)
        val rows = rowsByChunk(csv)

        val csvRowCount = csv.trim().split('\n').drop(1).size
        assertEquals(discoveredSnapshot.size, csvRowCount)

        val rowNonZero = rows["0,0"]
        val rowZero = rows["1,0"]
        val rowUnresolved = rows["2,0"]
        val rowMissing = rows["3,0"]

        assertNotNull(rowNonZero)
        assertEquals("20", rowNonZero.getValue("inhabitedTicks"))

        assertNotNull(rowZero)
        assertEquals("0", rowZero.getValue("inhabitedTicks"))

        assertNotNull(rowUnresolved)
        assertEquals("", rowUnresolved.getValue("inhabitedTicks"))
        assertEquals("FILE_IO_ERROR", rowUnresolved.getValue("status"))

        assertNull(rowMissing)

        assertWorldviewConsistency(
            discoveredChunkKeys = discoveredSnapshot.map { it.key }.toSet(),
            projectionSnapshot = committed,
            csv = csv,
        )
    }

    @Test
    fun shuffled_fact_ingestion_keeps_projection_and_csv_identical_for_minimal_world() {
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

        val harnessA = DomainTestHarness()
        harnessA.ingest(model)
        harnessA.runUntilIdle()

        val harnessB = DomainTestHarness()
        harnessB.ingest(model.copy(chunks = model.chunks.shuffled(java.util.Random(42))))
        harnessB.runUntilIdle()

        val committedA = harnessA.committedView()
        val committedB = harnessB.committedView()
        assertNotNull(committedA)
        assertNotNull(committedB)

        val snapshotA = model.chunks.map { fact ->
            ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry(
                key = fact.key,
                signals = fact.signals,
                dominantStone = fact.dominantStone ?: ch.oliverlanz.memento.domain.worldmap.DominantStoneSignal.NONE,
                dominantStoneEffect = fact.dominantStoneEffect ?: ch.oliverlanz.memento.domain.worldmap.DominantStoneEffectSignal.NONE,
                scanTick = fact.scanTick,
                provenance = fact.source,
                unresolvedReason = fact.unresolvedReason,
            )
        }

        val csvA = MementoCsvWriter.renderOperatorWorldviewCsv(snapshotA, committedA)
        val csvB = MementoCsvWriter.renderOperatorWorldviewCsv(snapshotA, committedB)

        assertEquals(committedA.chunkDerivationByChunk, committedB.chunkDerivationByChunk)
        assertEquals(committedA.regionForgettableByRegion, committedB.regionForgettableByRegion)
        assertEquals(committedA.rankedCandidates, committedB.rankedCandidates)
        assertEquals(csvA, csvB)
    }

    private fun rowsByChunk(csv: String): Map<String, Map<String, String>> {
        val lines = csv.trim().split('\n')
        val header = lines.first().split(',')
        return lines.drop(1).associate { line ->
            val values = line.split(',')
            val row = header.zip(values).toMap()
            val key = row.getValue("chunkX") + "," + row.getValue("chunkZ")
            key to row
        }
    }
}
