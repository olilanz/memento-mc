package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectionMemorabilityTest {

    private val world: RegistryKey<World> =
        RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft:overworld"))

    @Test
    fun low_ticks_are_filtered_below_threshold() {
        val center = key(0, 0)

        val out = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(center to 19L),
            loreProtectedChunks = emptySet(),
        )

        val signal = out.getValue(center)
        assertEquals(0.0, signal.memorabilityIndex)
        assertEquals(false, signal.memorable)
    }

    @Test
    fun medium_ticks_contribute_more_than_low_ticks() {
        val center = key(0, 0)

        val low = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(center to 20L),
            loreProtectedChunks = emptySet(),
        ).getValue(center)

        val medium = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(center to 120L),
            loreProtectedChunks = emptySet(),
        ).getValue(center)

        assertTrue(medium.memorabilityIndex > low.memorabilityIndex)
    }

    @Test
    fun high_center_radiates_more_to_neighbor_than_medium_center() {
        val center = key(0, 0)
        val neighbor = key(1, 0)

        val medium = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(center to 120L, neighbor to 0L),
            loreProtectedChunks = emptySet(),
        ).getValue(neighbor)

        val high = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(center to 600L, neighbor to 0L),
            loreProtectedChunks = emptySet(),
        ).getValue(neighbor)

        assertTrue(high.memorabilityIndex > medium.memorabilityIndex)
    }

    @Test
    fun nearby_weak_signals_accumulate_locally() {
        val target = key(0, 0)
        val a = key(1, 0)
        val b = key(0, 1)
        val c = key(-1, 0)

        val isolated = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(target to 21L),
            loreProtectedChunks = emptySet(),
        ).getValue(target)

        val clustered = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(target to 21L, a to 21L, b to 21L, c to 21L),
            loreProtectedChunks = emptySet(),
        ).getValue(target)

        assertTrue(clustered.memorabilityIndex > isolated.memorabilityIndex)
    }

    @Test
    fun computation_is_order_independent_and_no_synthetic_outputs_are_created() {
        val keys = listOf(key(0, 0), key(1, 0), key(0, 1), key(2, 2))
        val baselineMap = linkedMapOf(
            keys[0] to 600L,
            keys[1] to 0L,
            keys[2] to 120L,
            keys[3] to 19L,
        )
        val reversedMap = linkedMapOf(
            keys[3] to 19L,
            keys[2] to 120L,
            keys[1] to 0L,
            keys[0] to 600L,
        )

        val a = ProjectionMemorability.computeForChunks(baselineMap, loreProtectedChunks = emptySet())
        val b = ProjectionMemorability.computeForChunks(reversedMap, loreProtectedChunks = emptySet())

        assertEquals(a, b)
        assertEquals(baselineMap.keys, a.keys)
    }

    @Test
    fun outputs_are_finite_non_negative_and_boolean_coherent_with_threshold() {
        val input = mapOf(
            key(0, 0) to 0L,
            key(1, 0) to 120L,
            key(2, 0) to 600L,
            key(3, 0) to 10_000L,
        )

        val out = ProjectionMemorability.computeForChunks(input, loreProtectedChunks = emptySet())

        out.values.forEach { signal ->
            assertTrue(signal.memorabilityIndex.isFinite())
            assertTrue(signal.memorabilityIndex >= 0.0)
            assertEquals(
                signal.memorabilityIndex >= ProjectionMemorability.MEMORABLE_THRESHOLD,
                signal.memorable,
            )
        }
    }

    @Test
    fun lore_override_forces_memorable_without_changing_index_signal() {
        val center = key(0, 0)
        val input = mapOf(center to 0L)

        val baseline = ProjectionMemorability.computeForChunks(input, loreProtectedChunks = emptySet()).getValue(center)
        val withLore = ProjectionMemorability.computeForChunks(input, loreProtectedChunks = setOf(center)).getValue(center)

        assertEquals(baseline.memorabilityIndex, withLore.memorabilityIndex)
        assertEquals(false, baseline.memorable)
        assertEquals(true, withLore.memorable)
    }

    private fun key(chunkX: Int, chunkZ: Int): ChunkKey =
        ChunkKey(
            world = world,
            regionX = Math.floorDiv(chunkX, 32),
            regionZ = Math.floorDiv(chunkZ, 32),
            chunkX = chunkX,
            chunkZ = chunkZ,
        )
}
