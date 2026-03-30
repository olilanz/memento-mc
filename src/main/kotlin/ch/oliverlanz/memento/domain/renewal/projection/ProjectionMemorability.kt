package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import net.minecraft.registry.RegistryKey
import net.minecraft.world.World
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class MemorabilitySignal(
    val memorabilityIndex: Double,
    val memorable: Boolean,
)

/**
 * Projection-owned memorability policy.
 *
 * Coordinate invariant lock:
 * - [ChunkKey.chunkX] and [ChunkKey.chunkZ] are absolute world chunk coordinates.
 * - Distance calculations in this class MUST use those fields directly.
 * - No coordinate normalization or coordinate-form inference is performed here.
 *
 * Kernel and implementation lock:
 * - Effective influence kernel is Chebyshev radius 2 (distance weights outside radius are 0).
 * - Implementation uses ephemeral per-computation world+chunk lookup for local neighborhood reads.
 * - This is a semantics-preserving optimization of the legacy all-pairs formulation
 *   (guarded by exact-equivalence tests).
 *
 * Pure function contract:
 * - output keys are exactly the input chunk universe
 * - no synthetic chunks are materialized
 * - no iterative/feedback passes (single-pass over base signals only)
 */
object ProjectionMemorability {
    const val MEMORABLE_THRESHOLD: Double = MementoConstants.MEMENTO_RENEWAL_MEMORABLE_THRESHOLD
    private const val MEMORABLE_RADIUS_CHUNKS: Int = MementoConstants.MEMENTO_RENEWAL_MEMORABLE_EXPANSION_RADIUS_CHUNKS

    fun computeForChunks(
        inhabitedTicksByChunk: Map<ChunkKey, Long>,
        loreProtectedChunks: Set<ChunkKey>,
    ): Map<ChunkKey, MemorabilitySignal> {
        val baseByChunk = inhabitedTicksByChunk.mapValues { (_, ticks) -> baseSignal(ticks) }
        val baseByWorldChunk = baseByChunk.entries.associateBy(
            keySelector = { (key, _) -> WorldChunkRef(key.world, key.chunkX, key.chunkZ) },
            valueTransform = { (_, base) -> base },
        )

        return inhabitedTicksByChunk.keys.associateWith { target ->
            var index = 0.0
            for (dx in -MEMORABLE_RADIUS_CHUNKS..MEMORABLE_RADIUS_CHUNKS) {
                for (dz in -MEMORABLE_RADIUS_CHUNKS..MEMORABLE_RADIUS_CHUNKS) {
                    val distance = max(abs(dx), abs(dz))
                    val weight = weightForChebyshevDistance(distance)
                    if (weight <= 0.0) continue

                    val base = baseByWorldChunk[
                        WorldChunkRef(
                            world = target.world,
                            chunkX = target.chunkX + dx,
                            chunkZ = target.chunkZ + dz,
                        ),
                    ] ?: continue

                    index += base * weight
                }
            }

            MemorabilitySignal(
                memorabilityIndex = index,
                memorable = loreProtectedChunks.contains(target) || index >= MEMORABLE_THRESHOLD,
            )
        }
    }

    private fun baseSignal(ticks: Long): Double {
        val low = MementoConstants.MEMENTO_RENEWAL_MEMORABILITY_LOW_TICKS.toDouble()
        val medium = MementoConstants.MEMENTO_RENEWAL_MEMORABILITY_MEDIUM_TICKS.toDouble()
        val high = MementoConstants.MEMENTO_RENEWAL_MEMORABILITY_HIGH_TICKS.toDouble()
        val t = ticks.toDouble()

        return when {
            t < low -> 0.0
            t < medium -> {
                0.20 * clamp01((t - low) / (medium - low))
            }

            t < high -> {
                0.20 + 0.80 * clamp01((t - medium) / (high - medium))
            }

            else -> 1.0
        }
    }

    private fun weightForChebyshevDistance(distance: Int): Double =
        when (distance) {
            0 -> 1.00
            1 -> 0.35
            2 -> 0.12
            else -> 0.0
        }

    private data class WorldChunkRef(
        val world: RegistryKey<World>,
        val chunkX: Int,
        val chunkZ: Int,
    )

    private fun clamp01(v: Double): Double = min(1.0, max(0.0, v))
}
