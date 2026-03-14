package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.harness.DomainTestHarness
import ch.oliverlanz.memento.domain.harness.fixtures.WorldFixtureBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenewalProjectionOverflowWindowTest {

    @Test
    fun diagnostic_cappedPrefix_defectSignature_isObservable() {
        val harness = DomainTestHarness()
        val world = WorldFixtureBuilder.overworld()

        val model = WorldFixtureBuilder()
            .linearRegions(
                world = world,
                startRegionX = -400,
                count = MementoConstants.MEMENTO_RENEWAL_MAX_AFFECTED_REGIONS_PER_DISPATCH * 2,
                regionZ = 0,
                inhabitedTimeTicks = 0L,
            )
            .build()

        harness.ingest(model)
        harness.runMediumCycle()

        val firstCommit = harness.commits().first()
        assertEquals(
            MementoConstants.MEMENTO_RENEWAL_MAX_AFFECTED_REGIONS_PER_DISPATCH,
            firstCommit.processedAffectedRegionWindow.size,
        )
        assertTrue(firstCommit.overflowRemainder.isNotEmpty())
    }

    @Test
    fun overflow_remainder_is_retained_and_eventually_converges() {
        val harness = DomainTestHarness()
        val world = WorldFixtureBuilder.overworld()

        val model = WorldFixtureBuilder()
            .linearRegions(
                world = world,
                startRegionX = -400,
                count = MementoConstants.MEMENTO_RENEWAL_MAX_AFFECTED_REGIONS_PER_DISPATCH + 200,
                regionZ = 0,
                inhabitedTimeTicks = 0L,
            )
            .build()

        harness.ingest(model)
        harness.runUntilIdle(maxCycles = 16)

        val final = harness.commits().last()
        assertTrue(final.overflowRemainder.isEmpty(), "overflow should converge to empty after subsequent cycles")
    }

    @Test
    fun shuffled_ingestion_orders_converge_to_equivalent_committed_worldview() {
        val world = WorldFixtureBuilder.overworld()
        val baseModel = WorldFixtureBuilder()
            .linearRegions(
                world = world,
                startRegionX = -150,
                count = 200,
                regionZ = 0,
                inhabitedTimeTicks = 0L,
            )
            .build()

        val harnessA = DomainTestHarness()
        harnessA.ingest(baseModel)
        harnessA.runUntilIdle(maxCycles = 12)

        val harnessB = DomainTestHarness()
        val reversed = baseModel.copy(chunks = baseModel.chunks.reversed())
        harnessB.ingest(reversed)
        harnessB.runUntilIdle(maxCycles = 12)

        val committedA = harnessA.committedView()
        val committedB = harnessB.committedView()

        assertEquals(committedA?.chunkDerivationByChunk, committedB?.chunkDerivationByChunk)
        assertEquals(committedA?.regionForgettableByRegion, committedB?.regionForgettableByRegion)
        assertEquals(committedA?.rankedCandidates, committedB?.rankedCandidates)
    }
}
