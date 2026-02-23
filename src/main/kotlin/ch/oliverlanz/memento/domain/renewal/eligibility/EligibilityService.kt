package ch.oliverlanz.memento.domain.renewal.eligibility

import ch.oliverlanz.memento.domain.renewal.projection.RegionKey
import ch.oliverlanz.memento.domain.renewal.projection.RenewalStableSnapshot
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import net.minecraft.world.World

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

    private const val MAX_REGION_DISTANCE_RING: Int = 64
    private const val MAX_CHUNK_DISTANCE_RING: Int = 96
    /**
     * Bound chunk fallback list size retained in projection ranking.
     *
     * Drift revalidation for `/memento renew force <N>` is membership-based against
     * projection-ranked candidates, so this bound should stay comfortably above operator preview
     * and expected force counts.
     */
    private const val MAX_CHUNK_FALLBACK_CANDIDATES: Int = 256

    private val evaluationCounter = java.util.concurrent.atomic.AtomicLong(0L)

    private data class RegionAggregate(
        val region: RegionKey,
        val allChunksForgettableConservative: Boolean,
        val hasLivelyChunk: Boolean,
    )

    private class BoundedCheckCounter {
        var checks: Int = 0
            private set

        fun tick() {
            checks++
        }
    }

    fun evaluate(stable: RenewalStableSnapshot): EligibilityResult {
        val transactionId = "elig-${stable.generation}-${evaluationCounter.incrementAndGet()}"
        val snapshot = stable.snapshotEntries
        val metricsByChunk = stable.metricsByChunk
        val boundedChecks = BoundedCheckCounter()

        val forgettableByChunk = snapshot.associate { entry ->
            entry.key to ((metricsByChunk[entry.key]?.forgettabilityIndex ?: 0.0) >= 1.0)
        }

        val livelyChunkSet = snapshot
            .asSequence()
            .map { it.key }
            .filter { (metricsByChunk[it]?.livelinessIndex ?: 0.0) > 0.0 }
            .toSet()

        val chunksByRegion = snapshot.groupBy { RegionKey(it.key.world.value.toString(), it.key.regionX, it.key.regionZ) }
        val livelyRegions = livelyChunkSet
            .map { RegionKey(it.world.value.toString(), it.regionX, it.regionZ) }
            .toSet()
        val livelyRegionPackedByWorld = buildPackedRegionSetByWorld(livelyRegions)

        val aggregatesByRegion = chunksByRegion.mapValues { (region, entries) ->
            RegionAggregate(
                region = region,
                allChunksForgettableConservative = entries.all { forgettableByChunk[it.key] == true },
                hasLivelyChunk = livelyRegions.contains(region),
            )
        }

        val regionCandidates = linkedSetOf<RegionKey>()
        aggregatesByRegion.forEach { (region, aggregate) ->
            if (!aggregate.allChunksForgettableConservative) return@forEach
            if (aggregate.hasLivelyChunk) return@forEach

            val okNeighbors = neighborRegions8(region).all { neighbor ->
                boundedChecks.tick()
                val neighborAgg = aggregatesByRegion[neighbor] ?: return@all true
                neighborAgg.allChunksForgettableConservative
            }

            if (okNeighbors) {
                regionCandidates += region
            }
        }

        val regionDistanceByKey = regionCandidates.associateWith { candidate ->
            minChebyshevDistanceToLivelyRegions(candidate, livelyRegionPackedByWorld, boundedChecks)
        }

        val sortedRegions = regionCandidates
            .sortedWith(
                compareBy<RegionKey> { candidate ->
                    worldRank(candidate.worldId)
                }
                    .thenByDescending { candidate ->
                        regionDistanceByKey[candidate] ?: Int.MAX_VALUE
                    }
                    .thenBy { it.worldId }
                    .thenBy { it.regionZ }
                    .thenBy { it.regionX }
            )

        val livelyChunkPackedByWorld = buildPackedChunkSetByWorld(livelyChunkSet)

        val sortedChunks = if (sortedRegions.isEmpty()) {
            val chunkCandidates = snapshot
                .asSequence()
                .map { it.key }
                .filter { forgettableByChunk[it] == true }
                .toList()

            val chunkDistanceByKey = chunkCandidates.associateWith { candidate ->
                minChebyshevDistanceToLivelyChunks(candidate, livelyChunkPackedByWorld, boundedChecks)
            }

            snapshot
                .asSequence()
                .map { it.key }
                .filter { forgettableByChunk[it] == true }
                .sortedWith(
                    compareBy<ChunkKey> { candidate ->
                        worldRank(candidate.world.value.toString())
                    }
                        .thenByDescending { candidate ->
                            chunkDistanceByKey[candidate] ?: Int.MAX_VALUE
                        }
                        .thenBy { it.world.value.toString() }
                        .thenBy { it.chunkZ }
                        .thenBy { it.chunkX }
                )
                .take(MAX_CHUNK_FALLBACK_CANDIDATES)
                .toList()
        } else {
            emptyList()
        }

        val selected = when {
            sortedRegions.isNotEmpty() -> EligibilityExecutionGrain.Region(sortedRegions.first())
            sortedChunks.isNotEmpty() -> EligibilityExecutionGrain.ChunkBatch(sortedChunks)
            else -> null
        }

        MementoLog.info(
            MementoConcept.RENEWAL,
            "eligibility bounded-eval generation={} transaction={} checks={} regionRingMax={} chunkRingMax={} regionCandidates={} chunkFallbackCandidates={} selected={}",
            stable.generation,
            transactionId,
            boundedChecks.checks,
            MAX_REGION_DISTANCE_RING,
            MAX_CHUNK_DISTANCE_RING,
            sortedRegions.size,
            sortedChunks.size,
            selected?.javaClass?.simpleName ?: "NONE",
        )

        return EligibilityResult(
            projectionGeneration = stable.generation,
            transactionId = transactionId,
            eligibleRegions = sortedRegions,
            eligibleChunks = sortedChunks,
            selectedExecutionGrain = selected,
        )
    }

    private fun worldRank(worldId: String): Int {
        return when (worldId) {
            World.OVERWORLD.value.toString() -> 0
            World.NETHER.value.toString() -> 1
            World.END.value.toString() -> 2
            else -> 99
        }
    }

    private fun minChebyshevDistanceToLivelyRegions(
        candidate: RegionKey,
        livelyByPackedWorld: Map<String, Set<Long>>,
        checks: BoundedCheckCounter,
    ): Int {
        val livelyByPacked = livelyByPackedWorld[candidate.worldId] ?: return Int.MAX_VALUE
        if (livelyByPacked.isEmpty()) return Int.MAX_VALUE

        if (livelyByPacked.contains(packRegion(candidate.regionX, candidate.regionZ))) {
            checks.tick()
            return 0
        }

        for (distance in 1..MAX_REGION_DISTANCE_RING) {
            for (dx in -distance..distance) {
                val top = packRegion(candidate.regionX + dx, candidate.regionZ - distance)
                checks.tick()
                if (livelyByPacked.contains(top)) return distance

                val bottom = packRegion(candidate.regionX + dx, candidate.regionZ + distance)
                checks.tick()
                if (livelyByPacked.contains(bottom)) return distance
            }
            for (dz in (-distance + 1) until distance) {
                val left = packRegion(candidate.regionX - distance, candidate.regionZ + dz)
                checks.tick()
                if (livelyByPacked.contains(left)) return distance

                val right = packRegion(candidate.regionX + distance, candidate.regionZ + dz)
                checks.tick()
                if (livelyByPacked.contains(right)) return distance
            }
        }

        return MAX_REGION_DISTANCE_RING + 1
    }

    private fun minChebyshevDistanceToLivelyChunks(
        candidate: ChunkKey,
        livelyByPackedWorld: Map<net.minecraft.registry.RegistryKey<World>, Set<Long>>,
        checks: BoundedCheckCounter,
    ): Int {
        val livelyByPackedChunk = livelyByPackedWorld[candidate.world] ?: return Int.MAX_VALUE
        if (livelyByPackedChunk.isEmpty()) return Int.MAX_VALUE

        if (livelyByPackedChunk.contains(packChunk(candidate.chunkX, candidate.chunkZ))) {
            checks.tick()
            return 0
        }

        for (distance in 1..MAX_CHUNK_DISTANCE_RING) {
            for (dx in -distance..distance) {
                val top = packChunk(candidate.chunkX + dx, candidate.chunkZ - distance)
                checks.tick()
                if (livelyByPackedChunk.contains(top)) return distance

                val bottom = packChunk(candidate.chunkX + dx, candidate.chunkZ + distance)
                checks.tick()
                if (livelyByPackedChunk.contains(bottom)) return distance
            }
            for (dz in (-distance + 1) until distance) {
                val left = packChunk(candidate.chunkX - distance, candidate.chunkZ + dz)
                checks.tick()
                if (livelyByPackedChunk.contains(left)) return distance

                val right = packChunk(candidate.chunkX + distance, candidate.chunkZ + dz)
                checks.tick()
                if (livelyByPackedChunk.contains(right)) return distance
            }
        }

        return MAX_CHUNK_DISTANCE_RING + 1
    }

    private fun neighborRegions8(region: RegionKey): List<RegionKey> {
        val out = ArrayList<RegionKey>(8)
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                out += RegionKey(region.worldId, region.regionX + dx, region.regionZ + dz)
            }
        }
        return out
    }

    private fun buildPackedChunkSetByWorld(livelyChunkSet: Set<ChunkKey>): Map<net.minecraft.registry.RegistryKey<World>, Set<Long>> {
        return livelyChunkSet
            .groupBy { it.world }
            .mapValues { (_, keys) -> keys.mapTo(linkedSetOf()) { key -> packChunk(key.chunkX, key.chunkZ) } }
    }

    private fun buildPackedRegionSetByWorld(livelyRegions: Set<RegionKey>): Map<String, Set<Long>> {
        return livelyRegions
            .groupBy { it.worldId }
            .mapValues { (_, regions) -> regions.mapTo(linkedSetOf()) { region -> packRegion(region.regionX, region.regionZ) } }
    }

    private fun packChunk(x: Int, z: Int): Long {
        return (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
    }

    private fun packRegion(x: Int, z: Int): Long {
        return (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
    }
}
