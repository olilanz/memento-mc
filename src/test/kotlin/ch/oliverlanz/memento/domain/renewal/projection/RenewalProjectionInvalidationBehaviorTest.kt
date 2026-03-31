package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.harness.DomainTestHarness
import ch.oliverlanz.memento.domain.harness.TestWorldModel
import ch.oliverlanz.memento.domain.harness.fixtures.WorldFixtureBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RenewalProjectionInvalidationBehaviorTest {

    @Test
    fun source_change_within_dependency_radius_can_affect_target_signal() {
        val world = WorldFixtureBuilder.overworld()
        val harness = DomainTestHarness()

        val baseline = TestWorldModel.build {
            chunk(world = world, chunkX = 0, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 1L)
            // x=1 is intentionally near strong threshold without crossing it.
            // A source update at x=4 (distance R1+R2 from target x=0) adds +0.1 index
            // to x=1, which crosses strong threshold and turns target memorable via halo.
            chunk(world = world, chunkX = 1, chunkZ = 0, inhabitedTimeTicks = 1800L, scanTick = 2L)
            chunk(world = world, chunkX = 4, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 3L)
        }
        harness.ingest(baseline)
        harness.runUntilIdle()

        val targetKey = baseline.chunks.first { it.key.chunkX == 0 && it.key.chunkZ == 0 }.key
        val before = harness.committedView()?.chunkDerivationByChunk?.get(targetKey)?.memorable
        assertNotNull(before)
        assertEquals(false, before)

        val updated = TestWorldModel.build {
            chunk(world = world, chunkX = 0, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 3L)
            chunk(world = world, chunkX = 1, chunkZ = 0, inhabitedTimeTicks = 1800L, scanTick = 4L)
            chunk(world = world, chunkX = 4, chunkZ = 0, inhabitedTimeTicks = 2000L, scanTick = 5L)
        }
        harness.ingest(updated)
        // Post-bootstrap updates dispatch on debounce when below dirty threshold.
        Thread.sleep(MementoConstants.MEMENTO_RENEWAL_PROJECTION_DIRTY_REGION_DEBOUNCE_MS + 20L)
        harness.runUntilIdle()

        val after = harness.committedView()?.chunkDerivationByChunk?.get(targetKey)?.memorable
        assertNotNull(after)
        assertEquals(true, after)
    }

    @Test
    fun source_change_beyond_dependency_radius_does_not_affect_target_signal() {
        val world = WorldFixtureBuilder.overworld()
        val harness = DomainTestHarness()

        val baseline = TestWorldModel.build {
            chunk(world = world, chunkX = 0, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 1L)
            chunk(world = world, chunkX = 5, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 2L)
        }
        harness.ingest(baseline)
        harness.runUntilIdle()

        val targetKey = baseline.chunks.first { it.key.chunkX == 0 && it.key.chunkZ == 0 }.key
        val before = harness.committedView()?.chunkDerivationByChunk?.get(targetKey)?.memorabilityIndex
        assertNotNull(before)

        val updated = TestWorldModel.build {
            chunk(world = world, chunkX = 0, chunkZ = 0, inhabitedTimeTicks = 0L, scanTick = 3L)
            chunk(world = world, chunkX = 5, chunkZ = 0, inhabitedTimeTicks = 2000L, scanTick = 4L)
        }
        harness.ingest(updated)
        Thread.sleep(MementoConstants.MEMENTO_RENEWAL_PROJECTION_DIRTY_REGION_DEBOUNCE_MS + 20L)
        harness.runUntilIdle()

        val after = harness.committedView()?.chunkDerivationByChunk?.get(targetKey)?.memorabilityIndex
        assertNotNull(after)
        assertEquals(before, after)
    }
}
