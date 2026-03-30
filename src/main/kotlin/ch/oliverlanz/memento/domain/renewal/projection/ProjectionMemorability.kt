package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
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
 * Pure function contract:
 * - output keys are exactly the input chunk universe
 * - no synthetic chunks are materialized
 * - no iterative/feedback passes (single-pass over base signals only)
 */
object ProjectionMemorability {
    const val MEMORABLE_THRESHOLD: Double = MementoConstants.MEMENTO_RENEWAL_MEMORABLE_THRESHOLD

    fun computeForChunks(
        inhabitedTicksByChunk: Map<ChunkKey, Long>,
        loreProtectedChunks: Set<ChunkKey>,
    ): Map<ChunkKey, MemorabilitySignal> {
        val baseByChunk = inhabitedTicksByChunk.mapValues { (_, ticks) -> baseSignal(ticks) }

        return inhabitedTicksByChunk.keys.associateWith { target ->
            var index = 0.0
            baseByChunk.forEach { (source, base) ->
                if (source.world != target.world) return@forEach
                val distance = chebyshevDistance(target, source)
                val weight = weightForChebyshevDistance(distance)
                if (weight > 0.0) {
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

    private fun chebyshevDistance(a: ChunkKey, b: ChunkKey): Int {
        val dx = abs(a.chunkX - b.chunkX)
        val dz = abs(a.chunkZ - b.chunkZ)
        return max(dx, dz)
    }

    private fun clamp01(v: Double): Double = min(1.0, max(0.0, v))
}

