package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkMetadataFact
import ch.oliverlanz.memento.domain.worldmap.WorldMapService
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

fun interface RenewalProjectionStableListener {
    fun onProjectionStable(snapshot: RenewalStableSnapshot)
}

class RenewalProjection {
    private var worldMapService: WorldMapService? = null

    private val workSet = linkedSetOf<ChunkKey>()
    private val metricsByChunk = ConcurrentHashMap<ChunkKey, RenewalChunkMetrics>()
    private val stableListeners = CopyOnWriteArrayList<RenewalProjectionStableListener>()

    @Volatile
    private var analysisState: RenewalAnalysisState = RenewalAnalysisState.COMPUTING

    @Volatile
    private var decision: RenewalDecision? = null

    @Volatile
    private var attached: Boolean = false

    fun attach(service: WorldMapService) {
        worldMapService = service
        attached = true
        analysisState = RenewalAnalysisState.COMPUTING
    }

    fun detach() {
        attached = false
        worldMapService = null
        synchronized(workSet) {
            workSet.clear()
        }
        metricsByChunk.clear()
        decision = null
        analysisState = RenewalAnalysisState.COMPUTING
        stableListeners.clear()
    }

    fun addStableListener(listener: RenewalProjectionStableListener) {
        stableListeners += listener
    }

    fun removeStableListener(listener: RenewalProjectionStableListener) {
        stableListeners -= listener
    }

    fun statusView(): RenewalProjectionStatusView {
        val pending = synchronized(workSet) { workSet.size }
        return RenewalProjectionStatusView(
            state = analysisState,
            pendingWorkSetSize = pending,
            trackedChunks = metricsByChunk.size,
            hasStableDecision = decision != null,
        )
    }

    fun decisionView(): RenewalDecision? = decision

    fun stableSnapshotOrNull(): RenewalStableSnapshot? {
        if (analysisState != RenewalAnalysisState.STABLE) return null
        return RenewalStableSnapshot(
            metricsByChunk = metricsByChunk.toMap(),
            decision = decision,
        )
    }

    fun observeFactApplied(fact: ChunkMetadataFact) {
        if (!attached) return
        synchronized(workSet) {
            workSet += fact.key
        }
        if (analysisState == RenewalAnalysisState.STABLE) {
            analysisState = RenewalAnalysisState.COMPUTING
        }
    }

    fun observeWorldScanCompleted() {
        if (!attached) return
        if (analysisState == RenewalAnalysisState.STABLE) {
            analysisState = RenewalAnalysisState.COMPUTING
        }
    }

    fun tick() {
        if (!attached) return

        if (analysisState == RenewalAnalysisState.COMPUTING) {
            processComputeWorkSet()
            val remaining = synchronized(workSet) { workSet.size }
            if (remaining == 0) {
                analysisState = RenewalAnalysisState.STABILIZING
            }
        }

        if (analysisState == RenewalAnalysisState.STABILIZING) {
            runStabilizationAndDecision()
            analysisState = RenewalAnalysisState.STABLE

            val stable = RenewalStableSnapshot(metricsByChunk.toMap(), decision)
            stableListeners.forEach { listener ->
                try {
                    listener.onProjectionStable(stable)
                } catch (t: Throwable) {
                    MementoLog.error(MementoConcept.RENEWAL, "projection stable-listener failed", t)
                }
            }
        }
    }

    private fun processComputeWorkSet() {
        val service = worldMapService ?: return
        val snapshot = service.substrate().snapshot()
        if (snapshot.isEmpty()) return

        var processed = 0
        while (processed < MementoConstants.MEMENTO_RENEWAL_PROJECTION_MAX_PER_TICK) {
            val key = synchronized(workSet) {
                val first = workSet.firstOrNull()
                if (first != null) workSet.remove(first)
                first
            } ?: break

            processed++
            val previous = metricsByChunk[key] ?: RenewalChunkMetrics()
            val nextForgettability = computeForgettability(key, snapshot)
            val next = previous.copy(forgettabilityIndex = nextForgettability)

            metricsByChunk[key] = next
            if (previous.forgettabilityIndex != nextForgettability) {
                enqueueNeighborhood(key, 32, snapshot)
            }
        }
    }

    private fun runStabilizationAndDecision() {
        val service = worldMapService ?: return
        val snapshot = service.substrate().snapshot()

        if (snapshot.isEmpty()) {
            metricsByChunk.clear()
            decision = null
            return
        }

        val maxTicks = snapshot.asSequence()
            .mapNotNull { it.signals?.inhabitedTimeTicks }
            .maxOrNull() ?: 0L

        val threshold = maxTicks.toDouble() * 0.8
        val livelyChunkSet = linkedSetOf<ChunkKey>()

        snapshot.forEach { entry ->
            val key = entry.key
            val prior = metricsByChunk[key] ?: RenewalChunkMetrics()

            val lively = when (val ticks = entry.signals?.inhabitedTimeTicks) {
                null -> 1.0
                else -> if (maxTicks == 0L) 0.0 else if (ticks.toDouble() >= threshold) 1.0 else 0.0
            }

            val updated = prior.copy(
                livelinessIndex = lively,
                eligibleByRegionCandidateIndex = 0.0,
                eligibleByChunkCandidateIndex = 0.0,
            )
            metricsByChunk[key] = updated
            if (lively > 0.0) livelyChunkSet += key
        }

        decision = computeDecision(snapshot, livelyChunkSet)

        when (val d = decision) {
            is RenewalDecision.Region -> {
                metricsByChunk.entries.forEach { (key, value) ->
                    if (key.world.value.toString() == d.region.worldId && key.regionX == d.region.regionX && key.regionZ == d.region.regionZ) {
                        metricsByChunk[key] = value.copy(eligibleByRegionCandidateIndex = 1.0)
                    }
                }
            }

            is RenewalDecision.ChunkBatch -> {
                d.chunks.forEach { key ->
                    val value = metricsByChunk[key] ?: RenewalChunkMetrics()
                    metricsByChunk[key] = value.copy(eligibleByChunkCandidateIndex = 1.0)
                }
            }

            null -> Unit
        }
    }

    private fun computeDecision(
        snapshot: List<ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry>,
        livelyChunkSet: Set<ChunkKey>,
    ): RenewalDecision? {
        val forgettableByChunk = snapshot.associate { entry ->
            val metric = metricsByChunk[entry.key] ?: RenewalChunkMetrics()
            entry.key to (metric.forgettabilityIndex >= 1.0)
        }

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

        if (regionCandidates.isNotEmpty()) {
            val livelyRegions = livelyChunkSet
                .map { RegionKey(it.world.value.toString(), it.regionX, it.regionZ) }
                .toSet()

            return regionCandidates
                .sortedWith(
                    compareByDescending<RegionKey> { candidate ->
                        minChebyshevDistanceToLivelyRegions(candidate, livelyRegions)
                    }.thenBy { it.worldId }
                        .thenBy { it.regionZ }
                        .thenBy { it.regionX }
                )
                .firstOrNull()
                ?.let { RenewalDecision.Region(it) }
        }

        val livelyChunks = livelyChunkSet.toList()
        val chunkCandidates = snapshot
            .asSequence()
            .map { it.key }
            .filter { forgettableByChunk[it] == true }
            .sortedWith(
                compareByDescending<ChunkKey> { candidate ->
                    minChebyshevDistanceToLivelyChunks(candidate, livelyChunks)
                }.thenBy { it.world.value.toString() }
                    .thenBy { it.chunkZ }
                    .thenBy { it.chunkX }
            )
            .take(64)
            .toList()

        if (chunkCandidates.isEmpty()) return null
        return RenewalDecision.ChunkBatch(chunkCandidates)
    }

    private fun minChebyshevDistanceToLivelyRegions(candidate: RegionKey, lively: Set<RegionKey>): Int {
        if (lively.isEmpty()) return Int.MAX_VALUE
        return lively.asSequence()
            .filter { it.worldId == candidate.worldId }
            .map { kotlin.math.max(kotlin.math.abs(candidate.regionX - it.regionX), kotlin.math.abs(candidate.regionZ - it.regionZ)) }
            .minOrNull() ?: Int.MAX_VALUE
    }

    private fun minChebyshevDistanceToLivelyChunks(candidate: ChunkKey, lively: List<ChunkKey>): Int {
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

    private fun computeForgettability(
        key: ChunkKey,
        snapshot: List<ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry>,
    ): Double {
        val byKey = snapshot.associateBy { it.key }
        val current = byKey[key]
        val currentTicks = current?.signals?.inhabitedTimeTicks
        if (currentTicks == null) return 0.0
        if (currentTicks > 0L) return 0.0

        val hasNeighborWithActivity = snapshot.asSequence()
            .filter { it.key.world == key.world }
            .filter {
                val dx = kotlin.math.abs(it.key.chunkX - key.chunkX)
                val dz = kotlin.math.abs(it.key.chunkZ - key.chunkZ)
                kotlin.math.max(dx, dz) <= 32
            }
            .any {
                val t = it.signals?.inhabitedTimeTicks
                t == null || t > 0L
            }

        return if (hasNeighborWithActivity) 0.0 else 1.0
    }

    private fun enqueueNeighborhood(
        center: ChunkKey,
        radius: Int,
        snapshot: List<ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry>,
    ) {
        synchronized(workSet) {
            snapshot.asSequence()
                .map { it.key }
                .filter { it.world == center.world }
                .filter {
                    val dx = kotlin.math.abs(it.chunkX - center.chunkX)
                    val dz = kotlin.math.abs(it.chunkZ - center.chunkZ)
                    kotlin.math.max(dx, dz) <= radius
                }
                .forEach { workSet += it }
        }
    }
}
