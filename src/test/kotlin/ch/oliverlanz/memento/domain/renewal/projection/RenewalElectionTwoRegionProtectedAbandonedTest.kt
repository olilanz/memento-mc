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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Locks minimal two-region election semantics at the projection/election/CSV boundary.
 *
 * Invariants under test:
 * - Protected regions are not elected for `REGION_PURGE`.
 * - Abandoned regions are elected deterministically.
 * - Region rank is replicated across emitted rows of the elected region only.
 * - Same facts and ingestion order yield stable ranked candidates and CSV output.
 *
 * Non-goals:
 * - Runtime command execution and Minecraft engine lifecycle are outside this scope.
 */
class RenewalElectionTwoRegionProtectedAbandonedTest {

    @Test
    fun protected_region_is_not_elected_and_abandoned_region_is_elected() {
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

        val harness = DomainTestHarness()
        harness.ingest(model)
        harness.runUntilIdle()

        val committed = harness.committedView()
        assertNotNull(committed)

        val protectedRegion = RegionKey(worldId = world.value.toString(), regionX = 0, regionZ = 0)
        val abandonedRegion = RegionKey(worldId = world.value.toString(), regionX = 2, regionZ = 0)

        assertEquals(false, committed.regionForgettableByRegion[protectedRegion])
        assertEquals(true, committed.regionForgettableByRegion[abandonedRegion])

        val regionCandidates = committed.rankedCandidates.filter { it.id.action == RenewalCandidateAction.REGION_PRUNE }
        assertEquals(1, regionCandidates.size)
        assertEquals(2, regionCandidates.single().id.regionX)
        assertEquals(0, regionCandidates.single().id.regionZ)

        val csv = MementoCsvWriter.renderOperatorWorldviewCsv(toSnapshot(model), committed)
        val rows = rowsByChunk(csv)

        // Protected region rows never purge
        assertEquals("NONE", rows.getValue("0,0").getValue("renewalAction"))
        assertEquals("", rows.getValue("0,0").getValue("renewalRank"))
        assertEquals("NONE", rows.getValue("1,0").getValue("renewalAction"))
        assertEquals("", rows.getValue("1,0").getValue("renewalRank"))

        // Abandoned region rows purge with same replicated rank
        val rank64 = rows.getValue("0,0@r2").getValue("renewalRank")
        val rank65 = rows.getValue("1,0@r2").getValue("renewalRank")
        assertEquals("REGION_PURGE", rows.getValue("0,0@r2").getValue("renewalAction"))
        assertEquals("REGION_PURGE", rows.getValue("1,0@r2").getValue("renewalAction"))
        assertTrue(rank64.isNotEmpty())
        assertEquals(rank64, rank65)

        assertWorldviewConsistency(
            discoveredChunkKeys = model.chunks.map { it.key }.toSet(),
            projectionSnapshot = committed,
            csv = csv,
        )
    }

    @Test
    fun same_facts_same_order_produce_identical_election_and_csv() {
        val world = WorldFixtureBuilder.overworld()
        val model = TestWorldModel.build {
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
            chunk(world = world, chunkX = 64, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 3L)
            chunk(world = world, chunkX = 65, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 4L)
        }

        val harnessA = DomainTestHarness()
        harnessA.ingest(model)
        harnessA.runUntilIdle()

        val harnessB = DomainTestHarness()
        harnessB.ingest(model)
        harnessB.runUntilIdle()

        val committedA = harnessA.committedView()
        val committedB = harnessB.committedView()
        assertNotNull(committedA)
        assertNotNull(committedB)

        val csvA = MementoCsvWriter.renderOperatorWorldviewCsv(toSnapshot(model), committedA)
        val csvB = MementoCsvWriter.renderOperatorWorldviewCsv(toSnapshot(model), committedB)

        assertEquals(committedA.regionForgettableByRegion, committedB.regionForgettableByRegion)
        assertEquals(committedA.rankedCandidates, committedB.rankedCandidates)
        assertEquals(csvA, csvB)

        // Explicit protected-region guard
        val rows = rowsByChunk(csvA)
        assertFalse(rows.getValue("0,0").getValue("renewalAction") == "REGION_PURGE")
        assertFalse(rows.getValue("1,0").getValue("renewalAction") == "REGION_PURGE")
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

    private fun rowsByChunk(csv: String): Map<String, Map<String, String>> {
        val lines = csv.trim().split('\n')
        val header = lines.first().split(',')
        return lines.drop(1).associate { line ->
            val values = line.split(',')
            val row = header.zip(values).toMap()
            val regionX = row.getValue("regionX")
            val key = if (regionX == "2") {
                row.getValue("chunkX") + "," + row.getValue("chunkZ") + "@r2"
            } else {
                row.getValue("chunkX") + "," + row.getValue("chunkZ")
            }
            key to row
        }
    }
}
