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
 * Projection-owned memorability policy for chunk-level signal and decision derivation.
 *
 * Coordinate invariant lock:
 * - [ChunkKey.chunkX] and [ChunkKey.chunkZ] are absolute world chunk coordinates.
 * - Distance calculations in this class MUST use those fields directly.
 * - No coordinate normalization or coordinate-form inference is performed here.
 *
 * Pipeline contract lock:
 * - Pass 1 derives [MemorabilitySignal.memorabilityIndex] from inhabited-time base signals.
 * - Pass 2 derives [MemorabilitySignal.memorable] from memorability index plus lore override policy.
 * - Dependency direction is strictly one-way: inhabitedTicks -> memorabilityIndex -> memorable.
 * - Pass 2 must never feed back into Pass 1 inputs.
 *
 * Implementation status:
 * - Pass 1 is implemented by [computeMemorabilityIndex].
 * - Pass 2 is implemented by [deriveChunkMemorable].
 * - [computeForChunks] is a thin composition wrapper preserving existing call sites.
 *
 * Pure function contract:
 * - output keys are exactly the input chunk universe
 * - no synthetic chunks are materialized
 * - no iterative/feedback propagation
 */
object ProjectionMemorability {
    const val MEMORABLE_THRESHOLD: Double = MementoConstants.MEMENTO_RENEWAL_MEMORABLE_THRESHOLD_CORE
    private const val STRONG_MEMORABLE_THRESHOLD: Double = MementoConstants.MEMENTO_RENEWAL_MEMORABLE_THRESHOLD_STRONG
    private const val MEMORABLE_RADIUS_CHUNKS: Int = MementoConstants.MEMENTO_RENEWAL_MEMORABLE_EXPANSION_RADIUS_CHUNKS
    private const val MEMORABLE_HALO_RADIUS_CHUNKS: Int = MementoConstants.MEMENTO_RENEWAL_MEMORABLE_HALO_RADIUS_CHUNKS

    fun computeMemorabilityIndex(
        inhabitedTicksByChunk: Map<ChunkKey, Long>,
    ): Map<ChunkKey, Double> {
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

                    index += (base * base) * weight
                }
            }
            index
        }
    }

    fun deriveChunkMemorable(
        memorabilityIndexByChunk: Map<ChunkKey, Double>,
        loreProtectedChunks: Set<ChunkKey>,
    ): Map<ChunkKey, Boolean> {
        val indexByWorldChunk = memorabilityIndexByChunk.entries.associateBy(
            keySelector = { (key, _) -> WorldChunkRef(key.world, key.chunkX, key.chunkZ) },
            valueTransform = { (_, index) -> index },
        )

        return memorabilityIndexByChunk.mapValues { (key, index) ->
            val coreMemorable = index >= MEMORABLE_THRESHOLD
            val haloStrong = maxIndexWithinHalo(
                target = key,
                indexByWorldChunk = indexByWorldChunk,
                radius = MEMORABLE_HALO_RADIUS_CHUNKS,
            ) >= STRONG_MEMORABLE_THRESHOLD

            loreProtectedChunks.contains(key) || coreMemorable || haloStrong
        }
    }

    fun computeForChunks(
        inhabitedTicksByChunk: Map<ChunkKey, Long>,
        loreProtectedChunks: Set<ChunkKey>,
    ): Map<ChunkKey, MemorabilitySignal> {
        val memorabilityIndexByChunk = computeMemorabilityIndex(inhabitedTicksByChunk)
        val chunkMemorableByChunk = deriveChunkMemorable(
            memorabilityIndexByChunk = memorabilityIndexByChunk,
            loreProtectedChunks = loreProtectedChunks,
        )

        return inhabitedTicksByChunk.keys.associateWith { target ->
            MemorabilitySignal(
                memorabilityIndex = memorabilityIndexByChunk[target] ?: 0.0,
                memorable = chunkMemorableByChunk[target] == true,
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

    private fun weightForChebyshevDistance(distance: Int): Double {
        if (distance > MEMORABLE_RADIUS_CHUNKS) return 0.0
        return 1.0 / (1.0 + (distance * distance).toDouble())
    }

    private fun maxIndexWithinHalo(
        target: ChunkKey,
        indexByWorldChunk: Map<WorldChunkRef, Double>,
        radius: Int,
    ): Double {
        var maxIndex = Double.NEGATIVE_INFINITY
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                val distance = max(abs(dx), abs(dz))
                if (distance > radius) continue

                val candidate = indexByWorldChunk[
                    WorldChunkRef(
                        world = target.world,
                        chunkX = target.chunkX + dx,
                        chunkZ = target.chunkZ + dz,
                    ),
                ] ?: continue

                if (candidate > maxIndex) {
                    maxIndex = candidate
                }
            }
        }
        return maxIndex
    }

    private data class WorldChunkRef(
        val world: RegistryKey<World>,
        val chunkX: Int,
        val chunkZ: Int,
    )

    private fun clamp01(v: Double): Double = min(1.0, max(0.0, v))
}
