package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.domain.harness.DomainTestHarness
import ch.oliverlanz.memento.domain.harness.TestWorldModel
import ch.oliverlanz.memento.domain.harness.fixtures.WorldFixtureBuilder
import ch.oliverlanz.memento.domain.worldmap.DominantStoneEffectSignal
import ch.oliverlanz.memento.domain.worldmap.DominantStoneSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RenewalProjectionStoneEffectFactPropagationTest {

    @Test
    fun lore_protect_fact_is_propagated_and_marks_chunk_memorable() {
        val world = WorldFixtureBuilder.overworld()
        val model = TestWorldModel.build {
            chunk(
                world = world,
                chunkX = 0,
                chunkZ = 0,
                inhabitedTimeTicks = 0L,
                dominantStone = DominantStoneSignal.LORE,
                dominantStoneEffect = DominantStoneEffectSignal.LORE_PROTECT,
            )
        }

        val harness = DomainTestHarness()
        harness.ingest(model)
        harness.runUntilIdle()

        val committed = harness.committedView()
        assertNotNull(committed)

        val key = model.chunks.single().key
        val derivation = committed.chunkDerivationByChunk[key]
        assertNotNull(derivation)
        assertEquals(true, derivation.memorable)
        assertEquals(false, derivation.explicitRenewalIntent)
    }

    @Test
    fun wither_forget_fact_is_propagated_and_sets_explicit_intent() {
        val world = WorldFixtureBuilder.overworld()
        val model = TestWorldModel.build {
            chunk(
                world = world,
                chunkX = 1,
                chunkZ = 1,
                inhabitedTimeTicks = 999_999L,
                dominantStone = DominantStoneSignal.WITHER,
                dominantStoneEffect = DominantStoneEffectSignal.WITHER_FORGET,
            )
        }

        val harness = DomainTestHarness()
        harness.ingest(model)
        harness.runUntilIdle()

        val committed = harness.committedView()
        assertNotNull(committed)

        val key = model.chunks.single().key
        val derivation = committed.chunkDerivationByChunk[key]
        assertNotNull(derivation)
        assertEquals(false, derivation.memorable)
        assertEquals(true, derivation.explicitRenewalIntent)
    }
}

