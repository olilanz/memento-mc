package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Invariant lock suite for projection memorability policy.
 *
 * Why this test class exists:
 * - Memorability must be computed in one globally consistent world-chunk coordinate space.
 * - Distance is Chebyshev in world space, not mixed local/absolute heuristics.
 * - Output universe must equal input universe (no synthetic chunk keys).
 * - Signal (`memorabilityIndex`) and decision (`memorable`) stay separable, including lore override.
 */
class ProjectionMemorabilityTest {

    private val world: RegistryKey<World> =
        RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft:overworld"))

    // Signal behavior invariants -------------------------------------------------------------

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

    // Geometry invariants -------------------------------------------------------------------

    @Test
    fun cross_region_adjacency_receives_influence_across_boundary() {
        // Invariant: world-space neighbors across region boundaries must influence each other.
        val a = absoluteKeyFromRegionLocal(regionX = 0, regionZ = 0, localChunkX = 31, localChunkZ = 31)
        val b = absoluteKeyFromRegionLocal(regionX = 1, regionZ = 1, localChunkX = 0, localChunkZ = 0)

        val out = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(a to 600L, b to 0L),
            loreProtectedChunks = emptySet(),
        )

        assertTrue(out.getValue(b).memorabilityIndex > 0.0)
    }

    @Test
    fun same_local_coords_in_distant_regions_do_not_create_false_adjacency() {
        // Invariant: identical local chunk coords in different regions are NOT spatially adjacent.
        val a = absoluteKeyFromRegionLocal(regionX = 0, regionZ = 0, localChunkX = 10, localChunkZ = 10)
        val b = absoluteKeyFromRegionLocal(regionX = 5, regionZ = 5, localChunkX = 10, localChunkZ = 10)

        val out = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(a to 600L, b to 0L),
            loreProtectedChunks = emptySet(),
        )

        assertEquals(0.0, out.getValue(b).memorabilityIndex)
    }

    @Test
    fun origin_crossing_negative_to_zero_regions_is_adjacent() {
        // Invariant: crossing region sign boundaries must preserve geometric adjacency semantics.
        val a = absoluteKeyFromRegionLocal(regionX = -1, regionZ = -1, localChunkX = 31, localChunkZ = 31)
        val b = absoluteKeyFromRegionLocal(regionX = 0, regionZ = 0, localChunkX = 0, localChunkZ = 0)

        val out = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(a to 600L, b to 0L),
            loreProtectedChunks = emptySet(),
        )

        assertTrue(out.getValue(b).memorabilityIndex > 0.0)
    }

    @Test
    fun single_strong_source_stays_spatially_centered_in_world_space() {
        // Invariant: strongest signal remains centered at source/immediate neighborhood, not offset elsewhere.
        val source = absoluteKeyFromRegionLocal(regionX = 2, regionZ = 3, localChunkX = 31, localChunkZ = 31)
        val neighbor = absoluteKeyFromRegionLocal(regionX = 3, regionZ = 3, localChunkX = 0, localChunkZ = 31)
        val distantSameLocal = absoluteKeyFromRegionLocal(regionX = 7, regionZ = 8, localChunkX = 31, localChunkZ = 31)

        val out = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(source to 600L, neighbor to 0L, distantSameLocal to 0L),
            loreProtectedChunks = emptySet(),
        )

        val max = out.maxByOrNull { it.value.memorabilityIndex }!!.key
        assertTrue(max == source || max == neighbor)
        assertEquals(0.0, out.getValue(distantSameLocal).memorabilityIndex)
    }

    @Test
    fun mixed_coordinate_invariant_region_times_32_plus_chunk_is_unconditional() {
        // Invariant anchor: global coordinate mapping is unconditional
        // (global = region * 32 + localChunk), with no coordinate-form inference.
        // Invariant anchor: region=-5, chunk=10 => world chunk coordinate must be -150.
        val source = absoluteKeyFromRegionLocal(regionX = -5, regionZ = 0, localChunkX = 10, localChunkZ = 10)
        val adjacentByWorld = absoluteKeyFromRegionLocal(regionX = -5, regionZ = 0, localChunkX = 11, localChunkZ = 10)
        val farByWorld = absoluteKeyFromRegionLocal(regionX = 0, regionZ = 0, localChunkX = 10, localChunkZ = 10)

        val out = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(source to 600L, adjacentByWorld to 0L, farByWorld to 0L),
            loreProtectedChunks = emptySet(),
        )

        assertTrue(out.getValue(adjacentByWorld).memorabilityIndex > 0.0)
        assertEquals(0.0, out.getValue(farByWorld).memorabilityIndex)
    }

    // Determinism / universe invariants -----------------------------------------------------

    private fun key(chunkX: Int, chunkZ: Int): ChunkKey =
        ChunkKey(
            world = world,
            regionX = Math.floorDiv(chunkX, 32),
            regionZ = Math.floorDiv(chunkZ, 32),
            chunkX = chunkX,
            chunkZ = chunkZ,
        )

    private fun absoluteKeyFromRegionLocal(
        regionX: Int,
        regionZ: Int,
        localChunkX: Int,
        localChunkZ: Int,
    ): ChunkKey =
        ChunkKey(
            world = world,
            regionX = regionX,
            regionZ = regionZ,
            chunkX = regionX * 32 + localChunkX,
            chunkZ = regionZ * 32 + localChunkZ,
        )
}
