package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.domain.harness.DomainTestHarness
import ch.oliverlanz.memento.domain.harness.TestWorldModel
import ch.oliverlanz.memento.domain.harness.assertWorldviewConsistency
import ch.oliverlanz.memento.domain.harness.fixtures.WorldFixtureBuilder
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
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
 * Invariant matrix for renewal projection/election behavior on small worlds.
 *
 * Invariants under test:
 * - Protection invariant: protected regions are never elected for region purge.
 * - Abandonment invariant: forgettable regions are elected and rank is replicated per region row.
 * - Mixed-region anti-purge invariant: purge remains isolated to forgettable regions.
 * - Deterministic ordering and ranked-key boundary containment.
 *
 * Non-goals:
 * - Application command flows and Minecraft runtime integration are excluded.
 */
class RenewalProjectionInvariantMatrixTest {

    @Test
    fun projection_results_are_subset_of_discovered_universe() {
        val world = WorldFixtureBuilder.overworld()
        val model = TestWorldModel.build {
            chunk(world = world, chunkX = 0, chunkZ = 0, inhabitedTimeTicks = 5L, scanTick = 1L)
            chunk(world = world, chunkX = 1, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 2L)
            chunk(world = world, chunkX = 64, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 3L)
        }

        val harness = DomainTestHarness()
        harness.ingest(model)
        harness.runUntilIdle()

        val committed = harness.committedView()
        assertNotNull(committed)

        val discoveredUniverse = model.chunks.map { it.key }.toSet()
        val projectionKeys = committed.chunkDerivationByChunk.keys
        val rankedChunkKeys = committed.rankedCandidates
            .filter { it.id.action == RenewalCandidateAction.CHUNK_RENEW }
            .mapNotNull { it.id.toChunkKeyOrNull() }
            .toSet()

        assertTrue(projectionKeys.all { it in discoveredUniverse })
        assertTrue(rankedChunkKeys.all { it in discoveredUniverse })
    }

    @Test
    fun protection_invariant_protected_region_is_not_elected_for_region_purge() {
        val world = WorldFixtureBuilder.overworld()

        val model = TestWorldModel.build {
            // Region (0,0): protected by lore effect
            chunk(world = world, chunkX = 0, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 1L)
            chunk(
                world = world,
                chunkX = 1,
                chunkZ = 0,
                inhabitedTimeTicks = 0L,
                scanTick = 2L,
                dominantStone = DominantStoneSignal.LORE,
                dominantStoneEffect = DominantStoneEffectSignal.LORE_PROTECT,
            )
        }

        val harness = DomainTestHarness()
        harness.ingest(model)
        harness.runUntilIdle()

        val committed = harness.committedView()
        assertNotNull(committed)

        val protectedRegion = RegionKey(worldId = world.value.toString(), regionX = 0, regionZ = 0)
        assertEquals(false, committed.regionForgettableByRegion[protectedRegion])
        assertTrue(committed.rankedCandidates.none { it.id.action == RenewalCandidateAction.REGION_PRUNE })

        val csv = MementoCsvWriter.renderOperatorWorldviewCsv(toSnapshot(model), committed)
        val rows = rowsByChunkAbsolute(csv)
        assertEquals("NONE", rows.getValue("0,0").getValue("renewalAction"))
        assertEquals("NONE", rows.getValue("1,0").getValue("renewalAction"))

        assertWorldviewConsistency(
            discoveredChunkKeys = model.chunks.map { it.key }.toSet(),
            projectionSnapshot = committed,
            csv = csv,
        )
    }

    @Test
    fun abandonment_invariant_single_forgettable_region_is_elected_with_rank_replication() {
        val world = WorldFixtureBuilder.overworld()

        val model = TestWorldModel.build {
            // Region (2,0): abandoned/forgettable
            chunk(world = world, chunkX = 64, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 1L)
            chunk(world = world, chunkX = 65, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 2L)
            chunk(world = world, chunkX = 66, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 3L)
        }

        val harness = DomainTestHarness()
        harness.ingest(model)
        harness.runUntilIdle()

        val committed = harness.committedView()
        assertNotNull(committed)

        val forgettableRegion = RegionKey(worldId = world.value.toString(), regionX = 2, regionZ = 0)
        assertEquals(true, committed.regionForgettableByRegion[forgettableRegion])

        val regionCandidates = committed.rankedCandidates.filter { it.id.action == RenewalCandidateAction.REGION_PRUNE }
        assertEquals(1, regionCandidates.size, "single-forgettable-region invariant")
        assertEquals(2, regionCandidates.single().id.regionX)
        assertEquals(0, regionCandidates.single().id.regionZ)

        val csv = MementoCsvWriter.renderOperatorWorldviewCsv(toSnapshot(model), committed)
        val rows = rowsByChunkAbsolute(csv)
        val purgeRanks = setOf(
            rows.getValue("64,0").getValue("renewalRank"),
            rows.getValue("65,0").getValue("renewalRank"),
            rows.getValue("66,0").getValue("renewalRank"),
        )

        assertEquals("REGION_PURGE", rows.getValue("64,0").getValue("renewalAction"))
        assertEquals("REGION_PURGE", rows.getValue("65,0").getValue("renewalAction"))
        assertEquals("REGION_PURGE", rows.getValue("66,0").getValue("renewalAction"))
        assertEquals(1, purgeRanks.size, "all rows in purged region must share one replicated rank")
        assertTrue(purgeRanks.single().isNotEmpty())

        assertWorldviewConsistency(
            discoveredChunkKeys = model.chunks.map { it.key }.toSet(),
            projectionSnapshot = committed,
            csv = csv,
        )
    }

    @Test
    fun mixed_region_invariant_purge_is_isolated_to_forgettable_region_only() {
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

        val harness = DomainTestHarness()
        harness.ingest(model)
        harness.runUntilIdle()

        val committed = harness.committedView()
        assertNotNull(committed)

        val regionCandidates = committed.rankedCandidates
            .filter { it.id.action == RenewalCandidateAction.REGION_PRUNE }
        assertEquals(1, regionCandidates.size)
        assertEquals(2, regionCandidates.single().id.regionX)
        assertEquals(0, regionCandidates.single().id.regionZ)

        val csv = MementoCsvWriter.renderOperatorWorldviewCsv(toSnapshot(model), committed)
        val rows = rowsByChunkAbsolute(csv)

        // Region-purge isolation guard
        assertEquals("NONE", rows.getValue("0,0").getValue("renewalAction"))
        assertEquals("NONE", rows.getValue("1,0").getValue("renewalAction"))
        assertEquals("REGION_PURGE", rows.getValue("64,0").getValue("renewalAction"))
        assertEquals("REGION_PURGE", rows.getValue("65,0").getValue("renewalAction"))
        assertEquals("NONE", rows.getValue("128,0").getValue("renewalAction"))
        assertEquals("NONE", rows.getValue("129,0").getValue("renewalAction"))

        assertWorldviewConsistency(
            discoveredChunkKeys = model.chunks.map { it.key }.toSet(),
            projectionSnapshot = committed,
            csv = csv,
        )
    }

    @Test
    fun no_chunk_ambient_backdoor_non_memorable_chunk_outside_forgettable_region_is_not_actionable() {
        val world = WorldFixtureBuilder.overworld()

        val model = TestWorldModel.build {
            // Region (0,0): memorable via lore-protect.
            chunk(
                world = world,
                chunkX = 0,
                chunkZ = 0,
                inhabitedTimeTicks = 0L,
                scanTick = 1L,
                dominantStone = DominantStoneSignal.LORE,
                dominantStoneEffect = DominantStoneEffectSignal.LORE_PROTECT,
            )

            // Region (1,0): non-memorable chunk adjacent to memorable region.
            // Must not become ambient-actionable by any chunk-level backdoor.
            chunk(world = world, chunkX = 32, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 2L)
        }

        val harness = DomainTestHarness()
        harness.ingest(model)
        harness.runUntilIdle()

        val committed = harness.committedView()
        assertNotNull(committed)

        val adjacentRegion = RegionKey(worldId = world.value.toString(), regionX = 1, regionZ = 0)
        assertEquals(false, committed.regionForgettableByRegion[adjacentRegion])

        val csv = MementoCsvWriter.renderOperatorWorldviewCsv(toSnapshot(model), committed)
        val rows = rowsByChunkAbsolute(csv)
        val adjacentChunk = rows.getValue("32,0")

        assertEquals("NONE", adjacentChunk.getValue("renewalAction"))
        assertEquals("0", adjacentChunk.getValue("chunkForgettable"))
        assertEquals("0", adjacentChunk.getValue("chunkMemorable"))

        assertWorldviewConsistency(
            discoveredChunkKeys = model.chunks.map { it.key }.toSet(),
            projectionSnapshot = committed,
            csv = csv,
        )
    }

    @Test
    fun deterministic_ordering_and_ranked_key_boundary_hold_for_equivalent_fact_sets() {
        val world = WorldFixtureBuilder.overworld()
        val model = TestWorldModel.build {
            // Three forgettable regions to lock tie-break ordering by (world, regionX, regionZ)
            chunk(world = world, chunkX = 128, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 1L) // region (4,0)
            chunk(world = world, chunkX = 0, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 2L) // region (0,0)
            chunk(world = world, chunkX = 64, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 3L) // region (2,0)
        }

        val harnessA = DomainTestHarness()
        harnessA.ingest(model)
        harnessA.runUntilIdle()

        val harnessB = DomainTestHarness()
        harnessB.ingest(model.copy(chunks = model.chunks.reversed()))
        harnessB.runUntilIdle()

        val committedA = harnessA.committedView()
        val committedB = harnessB.committedView()
        assertNotNull(committedA)
        assertNotNull(committedB)

        assertEquals(committedA.regionForgettableByRegion, committedB.regionForgettableByRegion)
        assertEquals(committedA.rankedCandidates, committedB.rankedCandidates)

        val regionCandidates = committedA.rankedCandidates.filter { it.id.action == RenewalCandidateAction.REGION_PRUNE }
        assertEquals(listOf(0, 2, 4), regionCandidates.map { it.id.regionX }, "tie-break determinism by regionX")
        assertEquals(listOf(1, 2, 3), regionCandidates.map { it.rank }, "region ranks must remain deterministic and dense")

        val discovered = model.chunks.map { it.key }.toSet()
        val rankedChunkKeys = committedA.rankedCandidates
            .filter { it.id.action == RenewalCandidateAction.CHUNK_RENEW }
            .mapNotNull { it.id.toChunkKeyOrNull() }
            .toSet()
        assertTrue(rankedChunkKeys.all { it in discovered }, "ranked chunk keys must stay within discovered/projection boundary")

        val csvA = MementoCsvWriter.renderOperatorWorldviewCsv(toSnapshot(model), committedA)
        val csvB = MementoCsvWriter.renderOperatorWorldviewCsv(toSnapshot(model), committedB)
        assertEquals(csvA, csvB)

        assertWorldviewConsistency(
            discoveredChunkKeys = discovered,
            projectionSnapshot = committedA,
            csv = csvA,
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

    private fun rowsByChunkAbsolute(csv: String): Map<String, Map<String, String>> {
        val lines = csv.trim().split('\n')
        val header = lines.first().split(',')
        return lines.drop(1).associate { line ->
            val values = line.split(',')
            val row = header.zip(values).toMap()
            val key = absoluteChunkKey(row)
            key to row
        }
    }

    private fun absoluteChunkKey(row: Map<String, String>): String {
        val regionX = row.getValue("regionX").toInt()
        val regionZ = row.getValue("regionZ").toInt()
        val localChunkX = row.getValue("chunkX").toInt()
        val localChunkZ = row.getValue("chunkZ").toInt()
        val absoluteX = regionX * 32 + localChunkX
        val absoluteZ = regionZ * 32 + localChunkZ
        return "$absoluteX,$absoluteZ"
    }
}
