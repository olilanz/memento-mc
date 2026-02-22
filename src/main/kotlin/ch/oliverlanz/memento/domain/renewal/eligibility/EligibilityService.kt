package ch.oliverlanz.memento.domain.renewal.eligibility

import ch.oliverlanz.memento.domain.renewal.projection.RegionKey
import ch.oliverlanz.memento.domain.renewal.projection.RenewalStableSnapshot
import ch.oliverlanz.memento.domain.worldmap.ChunkKey

sealed interface EligibilityExecutionGrain {
    data class Region(val region: RegionKey) : EligibilityExecutionGrain
    data class ChunkBatch(val chunks: List<ChunkKey>) : EligibilityExecutionGrain
}

data class EligibilityResult(
    val projectionGeneration: Long,
    val transactionId: String,
    val eligibleRegions: List<RegionKey>,
    val eligibleChunks: List<ChunkKey>,
    val selectedExecutionGrain: EligibilityExecutionGrain?,
)

/**
 * Pure eligibility derivation from one stable projection snapshot.
 *
 * Side-effect contract:
 * - no world/projection mutation
 * - no scheduling
 * - no event emission
 */
object EligibilityService {

    private val evaluationCounter = java.util.concurrent.atomic.AtomicLong(0L)

    fun evaluate(stable: RenewalStableSnapshot): EligibilityResult {
        val transactionId = "elig-${stable.generation}-${evaluationCounter.incrementAndGet()}"
        val snapshot = stable.snapshotEntries
        val metricsByChunk = stable.metricsByChunk

        val forgettableByChunk = snapshot.associate { entry ->
            entry.key to ((metricsByChunk[entry.key]?.forgettabilityIndex ?: 0.0) >= 1.0)
        }

        val livelyChunkSet = snapshot
            .asSequence()
            .map { it.key }
            .filter { (metricsByChunk[it]?.livelinessIndex ?: 0.0) > 0.0 }
            .toSet()

        val chunksByRegion = snapshot.groupBy { Triple(it.key.world.value.toString(), it.key.regionX, it.key.regionZ) }

        val regionCandidates = linkedSetOf<RegionKey>()
        chunksByRegion.forEach { (region, entries) ->
            val allRegionForgettable = entries.all { forgettableByChunk[it.key] == true }
            if (!allRegionForgettable) return@forEach

            val okNeighbors = neighborRegions8(region.second, region.third).all { (rx, rz) ->
                val neighborEntries = chunksByRegion[Triple(region.first, rx, rz)]
                if (neighborEntries == null) return@all true
                neighborEntries.all { forgettableByChunk[it.key] == true }
            }

            if (okNeighbors) {
                regionCandidates += RegionKey(region.first, region.second, region.third)
            }
        }

        val livelyRegions = livelyChunkSet
            .map { RegionKey(it.world.value.toString(), it.regionX, it.regionZ) }
            .toSet()

        val sortedRegions = regionCandidates
            .sortedWith(
                compareByDescending<RegionKey> { candidate ->
                    minChebyshevDistanceToLivelyRegions(candidate, livelyRegions)
                }
                    .thenBy { it.worldId }
                    .thenBy { it.regionZ }
                    .thenBy { it.regionX }
            )

        val sortedChunks = snapshot
            .asSequence()
            .map { it.key }
            .filter { forgettableByChunk[it] == true }
            .sortedWith(
                compareByDescending<ChunkKey> { candidate ->
                    minChebyshevDistanceToLivelyChunks(candidate, livelyChunkSet)
                }
                    .thenBy { it.world.value.toString() }
                    .thenBy { it.chunkZ }
                    .thenBy { it.chunkX }
            )
            .take(64)
            .toList()

        val selected = when {
            sortedRegions.isNotEmpty() -> EligibilityExecutionGrain.Region(sortedRegions.first())
            sortedChunks.isNotEmpty() -> EligibilityExecutionGrain.ChunkBatch(sortedChunks)
            else -> null
        }

        return EligibilityResult(
            projectionGeneration = stable.generation,
            transactionId = transactionId,
            eligibleRegions = sortedRegions,
            eligibleChunks = sortedChunks,
            selectedExecutionGrain = selected,
        )
    }

    private fun minChebyshevDistanceToLivelyRegions(candidate: RegionKey, lively: Set<RegionKey>): Int {
        if (lively.isEmpty()) return Int.MAX_VALUE
        return lively.asSequence()
            .filter { it.worldId == candidate.worldId }
            .map { kotlin.math.max(kotlin.math.abs(candidate.regionX - it.regionX), kotlin.math.abs(candidate.regionZ - it.regionZ)) }
            .minOrNull() ?: Int.MAX_VALUE
    }

    private fun minChebyshevDistanceToLivelyChunks(candidate: ChunkKey, lively: Set<ChunkKey>): Int {
        if (lively.isEmpty()) return Int.MAX_VALUE
        return lively.asSequence()
            .filter { it.world == candidate.world }
            .map { kotlin.math.max(kotlin.math.abs(candidate.chunkX - it.chunkX), kotlin.math.abs(candidate.chunkZ - it.chunkZ)) }
            .minOrNull() ?: Int.MAX_VALUE
    }

    private fun neighborRegions8(regionX: Int, regionZ: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(8)
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                out += (regionX + dx) to (regionZ + dz)
            }
        }
        return out
    }
}
