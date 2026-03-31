package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.harness.DomainTestHarness
import ch.oliverlanz.memento.domain.harness.fixtures.WorldFixtureBuilder
import net.minecraft.registry.RegistryKey
import net.minecraft.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Projection overflow-window behavior and convergence tests.
 *
 * Invariants under test:
 * - A single projection cycle respects affected-region window caps.
 * - Overflow remainder is retained and converges over subsequent cycles.
 * - Equivalent fact sets converge to equivalent committed worldview under order variation.
 *
 * Non-goals:
 * - Runtime orchestration beyond projection-cycle boundaries.
 */
class RenewalProjectionOverflowWindowTest {

    // -------------------------------------------------------------------------
    // Shared convergence helpers
    // -------------------------------------------------------------------------

    private data class CommitConvergenceMetric(
        val overflowRemainderSize: Int,
        val dirtySetSize: Int,
        val requiredUniverseSize: Int,
        val coveredUniverseSize: Int,
    )

    private fun collectCommitConvergenceMetrics(
        harness: DomainTestHarness,
        maxCycles: Int,
    ): List<CommitConvergenceMetric> {
        val out = mutableListOf<CommitConvergenceMetric>()
        var seenCommits = harness.commits().size

        repeat(maxCycles) {
            harness.runMediumCycle()

            val commits = harness.commits()
            if (commits.size > seenCommits) {
                val status = harness.projection().statusView()
                val latest = commits.last()
                out += CommitConvergenceMetric(
                    overflowRemainderSize = latest.overflowRemainder.size,
                    dirtySetSize = status.pendingWorkSetSize,
                    requiredUniverseSize = status.projectionRequiredUniverseCount,
                    coveredUniverseSize = status.projectionCoveredUniverseCount,
                )
                seenCommits = commits.size
            }

            if (!harness.projection().hasPendingChanges()) return out
        }

        return out
    }

    private fun assertMonotonicDecreaseToZero(label: String, values: List<Int>) {
        assertTrue(values.isNotEmpty(), "$label sequence must not be empty")
        assertEquals(0, values.last(), "$label sequence must converge to zero")

        for (i in 1 until values.size) {
            val previous = values[i - 1]
            val current = values[i]
            if (previous > 0) {
                assertTrue(
                    current < previous,
                    "$label must strictly decrease while positive (index=$i previous=$previous current=$current values=$values)",
                )
            }
        }
    }

    private fun largeWorldFixture(world: RegistryKey<World>) = WorldFixtureBuilder()
        // Dense cluster in negative quadrant
        .singleChunk(world = world, chunkX = -96, chunkZ = -96, inhabitedTimeTicks = 24000L, scanTick = 1)
        .singleChunk(world = world, chunkX = -80, chunkZ = -96, inhabitedTimeTicks = 22000L, scanTick = 2)
        .singleChunk(world = world, chunkX = -96, chunkZ = -80, inhabitedTimeTicks = 21000L, scanTick = 3)
        // Medium scattered region crossings (positive/negative and boundary-adjacent)
        .singleChunk(world = world, chunkX = -1, chunkZ = -33, inhabitedTimeTicks = 8000L, scanTick = 4)
        .singleChunk(world = world, chunkX = 0, chunkZ = -32, inhabitedTimeTicks = 7000L, scanTick = 5)
        .singleChunk(world = world, chunkX = 31, chunkZ = 31, inhabitedTimeTicks = 6000L, scanTick = 6)
        .singleChunk(world = world, chunkX = 32, chunkZ = 32, inhabitedTimeTicks = 6500L, scanTick = 7)
        .singleChunk(world = world, chunkX = 95, chunkZ = 64, inhabitedTimeTicks = 9000L, scanTick = 8)
        // Empty/low-signal scattered far regions to force multi-window span
        .singleChunk(world = world, chunkX = -192, chunkZ = 64, inhabitedTimeTicks = 0L, scanTick = 9)
        .singleChunk(world = world, chunkX = 160, chunkZ = -128, inhabitedTimeTicks = 0L, scanTick = 10)
        .singleChunk(world = world, chunkX = -224, chunkZ = -160, inhabitedTimeTicks = 0L, scanTick = 11)
        .singleChunk(world = world, chunkX = 192, chunkZ = 192, inhabitedTimeTicks = 0L, scanTick = 12)
        // Deterministic overflow tail to force multi-window dispatch beyond a single affected window.
        .linearRegions(
            world = world,
            startRegionX = -500,
            count = MementoConstants.MEMENTO_RENEWAL_MAX_AFFECTED_REGIONS_PER_DISPATCH + 200,
            regionZ = 5,
            inhabitedTimeTicks = 0L,
            scanTickStart = 13L,
        )
        .build()

    // -------------------------------------------------------------------------
    // Diagnostic/regression signature tests
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Completeness + convergence + denominator stability locks (T1/T5/T7)
    // -------------------------------------------------------------------------

    @Test
    fun t1_t5_t7_largeWorldFixture_converges_with_stable_required_universe_and_full_coverage() {
        // T1/T5/T7 — Completeness + denominator stability under realistic large-world shape.
        // Protects invariants: multi-window convergence, exact coverage contract, required-universe stability.
        val harness = DomainTestHarness()
        val world = WorldFixtureBuilder.overworld()
        val model = largeWorldFixture(world)

        harness.ingest(model)

        val metrics = collectCommitConvergenceMetrics(harness, maxCycles = 128)
        val firstRequired = metrics.first().requiredUniverseSize

        val finalCommit = harness.commits().last()
        val finalStatus = harness.projection().statusView()
        val committed = harness.committedView()

        assertTrue(firstRequired > 0, "required universe must be non-zero for large-world fixture")
        assertEquals(firstRequired, finalStatus.projectionRequiredUniverseCount, "required universe must remain stable without new facts")

        assertTrue(harness.commits().size > 1, "fixture must require more than one commit window to converge")
        assertTrue(finalCommit.overflowRemainder.isEmpty(), "overflow must drain to empty at convergence")

        assertMonotonicDecreaseToZero(
            label = "overflow remainder",
            values = metrics.map { it.overflowRemainderSize },
        )
        assertMonotonicDecreaseToZero(
            label = "dirty set size",
            values = metrics.map { it.dirtySetSize },
        )

        assertEquals(0, finalStatus.pendingWorkSetSize, "dirty work set must be empty after convergence")
        assertTrue(finalStatus.projectionComplete, "projection completeness must be true at convergence")

        assertEquals(
            finalStatus.projectionRequiredUniverseCount,
            finalStatus.projectionCoveredUniverseCount,
            "committed coverage must equal required universe",
        )
        assertEquals(
            finalStatus.projectionRequiredUniverseCount,
            committed?.chunkDerivationByChunk?.size,
            "committed derivation map size must match required universe",
        )

        // No-new-facts extra cycle must keep denominator stable.
        harness.runMediumCycle()
        val afterExtra = harness.projection().statusView()
        assertEquals(finalStatus.projectionRequiredUniverseCount, afterExtra.projectionRequiredUniverseCount)
    }

    // -------------------------------------------------------------------------
    // No-new-facts stability/idempotence locks (T2/T6)
    // -------------------------------------------------------------------------

    @Test
    fun t2_noNewFacts_afterDrain_doesNotCreateNewDirtyWork_and_keepsCommittedStable() {
        // T2 — No-self-invalidating projection
        // Protects invariant: bookkeeping must not create new semantic invalidation without new facts.
        val harness = DomainTestHarness()
        val world = WorldFixtureBuilder.overworld()
        val model = largeWorldFixture(world)

        harness.ingest(model)
        harness.runUntilIdle(maxCycles = 64)

        val committedStable = harness.committedView()
        val statusStable = harness.projection().statusView()

        // One additional cycle is allowed for bookkeeping drain; subsequent cycles must remain stable.
        harness.runMediumCycle()
        repeat(3) { harness.runMediumCycle() }

        val committedAfter = harness.committedView()
        val statusAfter = harness.projection().statusView()

        assertEquals(0, statusAfter.pendingWorkSetSize, "no new dirty work should be created without new facts")
        assertEquals(statusStable.projectionRequiredUniverseCount, statusAfter.projectionRequiredUniverseCount)
        assertEquals(statusStable.projectionCoveredUniverseCount, statusAfter.projectionCoveredUniverseCount)
        assertEquals(committedStable, committedAfter, "committed snapshot must remain unchanged in steady no-new-facts cycles")
    }

    // -------------------------------------------------------------------------
    // Finite-burst supersession stabilization lock (T3)
    // -------------------------------------------------------------------------

    @Test
    fun t3_finiteFactArrival_thenStop_eventuallyReachesIdle_and_staysStable() {
        // T3 — Supersession stability
        // Protects invariant: after finite factual input stops, projection must converge to idle.
        val harness = DomainTestHarness()
        val world = WorldFixtureBuilder.overworld()

        val burstA = WorldFixtureBuilder()
            .linearRegions(
                world = world,
                startRegionX = -256,
                count = MementoConstants.MEMENTO_RENEWAL_MAX_AFFECTED_REGIONS_PER_DISPATCH + 48,
                regionZ = -3,
                inhabitedTimeTicks = 0L,
                scanTickStart = 1L,
            )
            .build()

        val burstB = WorldFixtureBuilder()
            .linearRegions(
                world = world,
                startRegionX = 64,
                count = MementoConstants.MEMENTO_RENEWAL_MAX_AFFECTED_REGIONS_PER_DISPATCH + 48,
                regionZ = 4,
                inhabitedTimeTicks = 0L,
                scanTickStart = 10_000L,
            )
            .build()

        harness.ingest(burstA)
        harness.runMediumCycle()
        harness.ingest(burstB)

        val metrics = collectCommitConvergenceMetrics(harness, maxCycles = 512)
        val settled = harness.projection().statusView()
        assertEquals(0, settled.pendingWorkSetSize, "projection must become idle after finite fact bursts stop")

        assertMonotonicDecreaseToZero(
            label = "overflow remainder after finite bursts",
            values = metrics.map { it.overflowRemainderSize },
        )
        assertMonotonicDecreaseToZero(
            label = "dirty set size after finite bursts",
            values = metrics.map { it.dirtySetSize },
        )

        val commitsAtSettle = harness.commits().size
        repeat(3) { harness.runMediumCycle() }
        assertEquals(commitsAtSettle, harness.commits().size, "no additional commits should appear after idle without new facts")
    }

    @Test
    fun t6_steadyStateIdempotence_noNewFacts_doesNotChangeCoverage_orReadinessTruthInputs() {
        // T6 — Steady-state idempotence
        // Protects invariant: complete steady state is idempotent across additional cycles.
        val harness = DomainTestHarness()
        val world = WorldFixtureBuilder.overworld()
        val model = largeWorldFixture(world)

        harness.ingest(model)
        harness.runUntilIdle(maxCycles = 64)

        val before = harness.projection().statusView()
        val committedBefore = harness.committedView()

        repeat(5) { harness.runMediumCycle() }

        val after = harness.projection().statusView()
        val committedAfter = harness.committedView()

        assertEquals(before.projectionRequiredUniverseCount, after.projectionRequiredUniverseCount)
        assertEquals(before.projectionCoveredUniverseCount, after.projectionCoveredUniverseCount)
        assertEquals(before.projectionComplete, after.projectionComplete)
        assertEquals(0, after.pendingWorkSetSize)
        assertEquals(committedBefore, committedAfter, "steady-state cycle must not mutate committed snapshot")
    }

    // -------------------------------------------------------------------------
    // Ordering invariance lock
    // -------------------------------------------------------------------------

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
