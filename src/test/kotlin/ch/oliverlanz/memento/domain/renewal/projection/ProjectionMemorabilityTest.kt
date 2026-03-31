package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World
import kotlin.math.abs
import kotlin.math.max
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
            inhabitedTicksByChunk = mapOf(target to 60L),
            loreProtectedChunks = emptySet(),
        ).getValue(target)

        val clustered = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(target to 60L, a to 60L, b to 60L, c to 60L),
            loreProtectedChunks = emptySet(),
        ).getValue(target)

        assertTrue(clustered.memorabilityIndex > isolated.memorabilityIndex)
    }

    @Test
    fun pass1_center_contribution_uses_quadratic_base_signal() {
        val center = key(0, 0)
        val ticks = MementoConstants.MEMENTO_RENEWAL_MEMORABILITY_MEDIUM_TICKS

        val out = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(center to ticks),
            loreProtectedChunks = emptySet(),
        )

        val base = legacyBaseSignal(ticks)
        val expectedQuadratic = base * base
        val actual = out.getValue(center).memorabilityIndex

        assertEquals(expectedQuadratic, actual)
    }

    @Test
    fun pass1_direct_function_produces_signal_map_without_decision_coupling() {
        val center = key(0, 0)
        val neighbor = key(1, 0)

        val index = ProjectionMemorability.computeMemorabilityIndex(
            inhabitedTicksByChunk = mapOf(center to MementoConstants.MEMENTO_RENEWAL_MEMORABILITY_HIGH_TICKS, neighbor to 0L),
        )

        assertTrue(index.getValue(center) > index.getValue(neighbor))
    }

    @Test
    fun pass1_radius_three_influence_reaches_distance_three_chunk() {
        val source = key(0, 0)
        val atDistanceThree = key(3, 0)

        val out = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(source to MementoConstants.MEMENTO_RENEWAL_MEMORABILITY_HIGH_TICKS, atDistanceThree to 0L),
            loreProtectedChunks = emptySet(),
        )

        assertTrue(out.getValue(atDistanceThree).memorabilityIndex > 0.0)
    }

    @Test
    fun pass2_strong_center_creates_one_hop_halo_memorable_neighbor() {
        val center = key(0, 0)
        val neighbor = key(1, 0)

        val out = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(center to MementoConstants.MEMENTO_RENEWAL_MEMORABILITY_HIGH_TICKS, neighbor to 0L),
            loreProtectedChunks = emptySet(),
        )

        assertEquals(true, out.getValue(neighbor).memorable)
    }

    @Test
    fun pass2_direct_function_reads_index_only_and_applies_halo_rule() {
        val center = key(0, 0)
        val neighbor = key(1, 0)
        val far = key(2, 0)

        val decision = ProjectionMemorability.deriveChunkMemorable(
            memorabilityIndexByChunk = mapOf(
                center to MementoConstants.MEMENTO_RENEWAL_MEMORABLE_THRESHOLD_STRONG,
                neighbor to 0.0,
                far to 0.0,
            ),
            loreProtectedChunks = emptySet(),
        )

        assertEquals(true, decision.getValue(neighbor))
        assertEquals(false, decision.getValue(far))
    }

    @Test
    fun pass2_halo_does_not_chain_beyond_one_hop() {
        val center = key(0, 0)
        val hop1 = key(1, 0)
        val hop2 = key(2, 0)

        val out = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(
                center to MementoConstants.MEMENTO_RENEWAL_MEMORABILITY_HIGH_TICKS,
                hop1 to 0L,
                hop2 to 0L,
            ),
            loreProtectedChunks = emptySet(),
        )

        assertEquals(true, out.getValue(hop1).memorable)
        assertEquals(false, out.getValue(hop2).memorable)
    }

    @Test
    fun pass2_weak_scattered_medium_signals_do_not_create_artificial_halo() {
        val a = key(0, 0)
        val b = key(2, 0)
        val c = key(0, 2)
        val target = key(1, 1)

        val out = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(
                a to MementoConstants.MEMENTO_RENEWAL_MEMORABILITY_MEDIUM_TICKS,
                b to MementoConstants.MEMENTO_RENEWAL_MEMORABILITY_MEDIUM_TICKS,
                c to MementoConstants.MEMENTO_RENEWAL_MEMORABILITY_MEDIUM_TICKS,
                target to 0L,
            ),
            loreProtectedChunks = emptySet(),
        )

        assertEquals(false, out.getValue(target).memorable)
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

        for ((key, signal) in out) {
            assertTrue(signal.memorabilityIndex.isFinite())
            assertTrue(signal.memorabilityIndex >= 0.0)

            val maxNeighborIndexWithinHalo = out
                .filterKeys { neighbor ->
                    val distance = max(abs(neighbor.chunkX - key.chunkX), abs(neighbor.chunkZ - key.chunkZ))
                    distance <= MementoConstants.MEMENTO_RENEWAL_MEMORABLE_HALO_RADIUS_CHUNKS
                }
                .maxOf { (_, neighborSignal) -> neighborSignal.memorabilityIndex }

            assertEquals(
                signal.memorabilityIndex >= ProjectionMemorability.MEMORABLE_THRESHOLD ||
                    maxNeighborIndexWithinHalo >= MementoConstants.MEMENTO_RENEWAL_MEMORABLE_THRESHOLD_STRONG,
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

    // Semantics-preserving optimization locks ------------------------------------------------

    @Test
    fun optimized_local_kernel_matches_legacy_all_pairs_exactly() {
        val input = linkedMapOf(
            key(-7, -7) to 0L,
            key(-3, 4) to 19L,
            key(-2, -1) to 20L,
            key(-1, 0) to 120L,
            key(0, 0) to 600L,
            key(1, 0) to 601L,
            key(2, 2) to 2400L,
            key(3, 3) to 12L,
            key(8, -2) to 99L,
            absoluteKeyFromRegionLocal(regionX = -1, regionZ = 0, localChunkX = 31, localChunkZ = 0) to 300L,
            absoluteKeyFromRegionLocal(regionX = 0, regionZ = 0, localChunkX = 0, localChunkZ = 0) to 301L,
            absoluteKeyFromRegionLocal(regionX = 5, regionZ = -4, localChunkX = 10, localChunkZ = 12) to 700L,
        )
        val loreProtected = setOf(
            key(3, 3),
            absoluteKeyFromRegionLocal(regionX = 5, regionZ = -4, localChunkX = 10, localChunkZ = 12),
        )

        val optimized = ProjectionMemorability.computeForChunks(input, loreProtectedChunks = loreProtected)
        val legacy = legacyAllPairsComputeForChunks(input, loreProtectedChunks = loreProtected)

        assertEquals(legacy, optimized)
    }

    @Test
    fun far_outside_radius_two_contributors_do_not_change_target_signal() {
        val target = key(0, 0)
        val near = key(2, 1)
        val far = key(20, 20)

        val baseline = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(target to 120L, near to 600L),
            loreProtectedChunks = emptySet(),
        ).getValue(target)

        val withFar = ProjectionMemorability.computeForChunks(
            inhabitedTicksByChunk = mapOf(target to 120L, near to 600L, far to 20_000L),
            loreProtectedChunks = emptySet(),
        ).getValue(target)

        assertEquals(baseline, withFar)
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

    private fun legacyAllPairsComputeForChunks(
        inhabitedTicksByChunk: Map<ChunkKey, Long>,
        loreProtectedChunks: Set<ChunkKey>,
    ): Map<ChunkKey, MemorabilitySignal> {
        val baseByChunk = inhabitedTicksByChunk.mapValues { (_, ticks) -> legacyBaseSignal(ticks) }
        return inhabitedTicksByChunk.keys.associateWith { target ->
            var index = 0.0
            baseByChunk.forEach { (source, base) ->
                if (source.world != target.world) return@forEach
                val distance = max(abs(target.chunkX - source.chunkX), abs(target.chunkZ - source.chunkZ))
                val weight = legacyWeightForChebyshevDistance(distance)
                if (weight > 0.0) {
                    index += (base * base) * weight
                }
            }
            MemorabilitySignal(
                memorabilityIndex = index,
                memorable = loreProtectedChunks.contains(target) || index >= ProjectionMemorability.MEMORABLE_THRESHOLD,
            )
        }
    }

    private fun legacyWeightForChebyshevDistance(distance: Int): Double =
        if (distance > MementoConstants.MEMENTO_RENEWAL_MEMORABLE_EXPANSION_RADIUS_CHUNKS) {
            0.0
        } else {
            1.0 / (1.0 + (distance * distance).toDouble())
        }

    private fun legacyBaseSignal(ticks: Long): Double {
        val t = ticks.toDouble()
        val low = MementoConstants.MEMENTO_RENEWAL_MEMORABILITY_LOW_TICKS.toDouble()
        val medium = MementoConstants.MEMENTO_RENEWAL_MEMORABILITY_MEDIUM_TICKS.toDouble()
        val high = MementoConstants.MEMENTO_RENEWAL_MEMORABILITY_HIGH_TICKS.toDouble()
        return when {
            t < low -> 0.0
            t < medium -> 0.20 * clamp01((t - low) / (medium - low))
            t < high -> 0.20 + 0.80 * clamp01((t - medium) / (high - medium))
            else -> 1.0
        }
    }

    private fun clamp01(v: Double): Double =
        when {
            v < 0.0 -> 0.0
            v > 1.0 -> 1.0
            else -> v
        }
}
