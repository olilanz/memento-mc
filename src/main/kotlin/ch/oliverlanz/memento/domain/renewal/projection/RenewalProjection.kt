package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry
import ch.oliverlanz.memento.domain.worldmap.ChunkMetadataFact
import ch.oliverlanz.memento.domain.worldmap.WorldMapService
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import net.minecraft.registry.RegistryKey
import net.minecraft.world.World

fun interface RenewalProjectionStableListener {
    fun onProjectionStable(snapshot: RenewalStableSnapshot)
}

/**
 * Hybrid projection authority model.
 *
 * Authority boundaries:
 * - Tick thread owns mutable projection state, snapshot capture, generation assignment, and commit.
 * - Worker thread performs pure computation on immutable snapshot input.
 * - Worker output is generation-tagged and must pass generation/supersession checks before commit.
 */
class RenewalProjection {
    private var worldMapService: WorldMapService? = null

    private val dirtySet = linkedSetOf<ChunkKey>()
    private val metricsByChunk = ConcurrentHashMap<ChunkKey, RenewalChunkMetrics>()
    private val stableListeners = CopyOnWriteArrayList<RenewalProjectionStableListener>()
    private val computeExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "memento-renewal-projection").apply { isDaemon = true }
        }

    private var dirtySinceMs: Long? = null
    private var generationHead: Long = 0L
    private var appliedGeneration: Long = 0L
    private var inFlight: InFlightJob? = null
    private var supersededDuringInFlight: Boolean = false
    private var scanRecomputeRequested: Boolean = false

    @Volatile
    private var analysisState: RenewalAnalysisState = RenewalAnalysisState.COMPUTING

    @Volatile
    private var decision: RenewalDecision? = null

    @Volatile
    private var attached: Boolean = false

    private data class InFlightJob(
        val generation: Long,
        val future: java.util.concurrent.Future<WorkerResult>,
    )

    private enum class TriggerReason {
        DIRTY_THRESHOLD,
        DEBOUNCE,
        SCAN_COMPLETED,
    }

    private data class WorkerResult(
        val generation: Long,
        val metricsByChunk: Map<ChunkKey, RenewalChunkMetrics>,
        val decision: RenewalDecision?,
    )

    fun attach(service: WorldMapService) {
        worldMapService = service
        attached = true
        analysisState = RenewalAnalysisState.COMPUTING
    }

    fun detach() {
        attached = false
        worldMapService = null
        synchronized(dirtySet) {
            dirtySet.clear()
            dirtySinceMs = null
        }
        inFlight = null
        supersededDuringInFlight = false
        scanRecomputeRequested = false
        generationHead = 0L
        appliedGeneration = 0L
        metricsByChunk.clear()
        decision = null
        analysisState = RenewalAnalysisState.COMPUTING
        stableListeners.clear()
        computeExecutor.shutdownNow()
    }

    fun addStableListener(listener: RenewalProjectionStableListener) {
        stableListeners += listener
    }

    fun removeStableListener(listener: RenewalProjectionStableListener) {
        stableListeners -= listener
    }

    fun statusView(): RenewalProjectionStatusView {
        val pending = synchronized(dirtySet) { dirtySet.size }
        return RenewalProjectionStatusView(
            state = analysisState,
            pendingWorkSetSize = pending,
            trackedChunks = metricsByChunk.size,
            hasStableDecision = analysisState == RenewalAnalysisState.STABLE && decision != null,
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
        val now = System.currentTimeMillis()
        synchronized(dirtySet) {
            val added = dirtySet.add(fact.key)
            if (added && dirtySinceMs == null) dirtySinceMs = now
        }
        if (inFlight != null) {
            supersededDuringInFlight = true
        }
        if (analysisState == RenewalAnalysisState.STABLE || analysisState == RenewalAnalysisState.STABILIZING) {
            analysisState = RenewalAnalysisState.COMPUTING
        }
    }

    fun observeWorldScanCompleted() {
        if (!attached) return
        scanRecomputeRequested = true
        if (inFlight != null) {
            supersededDuringInFlight = true
        }
        if (analysisState == RenewalAnalysisState.STABLE || analysisState == RenewalAnalysisState.STABILIZING) {
            analysisState = RenewalAnalysisState.COMPUTING
        }
    }

    fun tick() {
        if (!attached) return

        maybeFinalizeInFlight()

        if (inFlight == null) {
            val reason = nextTriggerReason(System.currentTimeMillis())
            if (reason != null) {
                dispatchWorker(reason)
            }
        }
    }

    private fun maybeFinalizeInFlight() {
        val job = inFlight ?: return
        if (!job.future.isDone) return
        inFlight = null

        val result = try {
            job.future.get()
        } catch (t: Throwable) {
            MementoLog.error(
                MementoConcept.PROJECTION,
                "worker failed generation={} (state->COMPUTING)",
                t,
                job.generation,
            )
            analysisState = RenewalAnalysisState.COMPUTING
            return
        }

        if (result.generation != generationHead || supersededDuringInFlight) {
            MementoLog.debug(
                MementoConcept.PROJECTION,
                "worker discard generation={} head={} superseded={} pendingDirty={}",
                result.generation,
                generationHead,
                supersededDuringInFlight,
                synchronized(dirtySet) { dirtySet.size },
            )
            analysisState = RenewalAnalysisState.COMPUTING
            supersededDuringInFlight = false
            return
        }

        metricsByChunk.clear()
        metricsByChunk.putAll(result.metricsByChunk)
        decision = result.decision
        appliedGeneration = result.generation
        analysisState = RenewalAnalysisState.STABLE
        supersededDuringInFlight = false

        MementoLog.debug(
            MementoConcept.PROJECTION,
            "worker apply generation={} tracked={} hasDecision={}",
            appliedGeneration,
            metricsByChunk.size,
            decision != null,
        )

        val stable = RenewalStableSnapshot(metricsByChunk.toMap(), decision)
        stableListeners.forEach { listener ->
            try {
                listener.onProjectionStable(stable)
            } catch (t: Throwable) {
                MementoLog.error(MementoConcept.RENEWAL, "projection stable-listener failed", t)
            }
        }
    }

    private fun nextTriggerReason(nowMs: Long): TriggerReason? {
        if (scanRecomputeRequested) return TriggerReason.SCAN_COMPLETED

        val dirtyCount: Int
        val firstDirtyAt: Long?
        synchronized(dirtySet) {
            dirtyCount = dirtySet.size
            firstDirtyAt = dirtySinceMs
        }

        if (dirtyCount == 0) return null
        if (dirtyCount >= MementoConstants.MEMENTO_RENEWAL_PROJECTION_DIRTY_THRESHOLD) {
            return TriggerReason.DIRTY_THRESHOLD
        }

        if (firstDirtyAt != null && nowMs - firstDirtyAt >= MementoConstants.MEMENTO_RENEWAL_PROJECTION_DIRTY_DEBOUNCE_MS) {
            return TriggerReason.DEBOUNCE
        }

        return null
    }

    private fun dispatchWorker(reason: TriggerReason) {
        val service = worldMapService ?: return
        val snapshot = service.substrate().snapshot()

        val generation = generationHead + 1L
        generationHead = generation

        val dirtySeedCount = synchronized(dirtySet) {
            val count = dirtySet.size
            dirtySet.clear()
            dirtySinceMs = null
            count
        }

        scanRecomputeRequested = false
        supersededDuringInFlight = false
        analysisState = RenewalAnalysisState.STABILIZING

        MementoLog.debug(
            MementoConcept.PROJECTION,
            "worker start generation={} reason={} dirtySeed={} snapshot={}",
            generation,
            reason.name,
            dirtySeedCount,
            snapshot.size,
        )

        val future = computeExecutor.submit<WorkerResult> {
            computeWorkerResult(generation, snapshot)
        }
        inFlight = InFlightJob(generation = generation, future = future)
    }

    private fun computeWorkerResult(
        generation: Long,
        snapshot: List<ChunkScanSnapshotEntry>,
    ): WorkerResult {
        if (snapshot.isEmpty()) {
            return WorkerResult(
                generation = generation,
                metricsByChunk = emptyMap(),
                decision = null,
            )
        }

        val index = buildSnapshotIndex(snapshot)
        val forgettableByChunk = snapshot.associate { entry ->
            entry.key to (computeForgettability(entry.key, index) >= 1.0)
        }

        val maxTicks = snapshot.asSequence()
            .mapNotNull { it.signals?.inhabitedTimeTicks }
            .maxOrNull() ?: 0L
        val threshold = maxTicks.toDouble() * 0.8
        val livelyChunkSet = linkedSetOf<ChunkKey>()
        val metrics = linkedMapOf<ChunkKey, RenewalChunkMetrics>()

        snapshot.forEach { entry ->
            val key = entry.key
            val lively = when (val ticks = entry.signals?.inhabitedTimeTicks) {
                null -> 1.0
                else -> if (maxTicks == 0L) 0.0 else if (ticks.toDouble() >= threshold) 1.0 else 0.0
            }
            if (lively > 0.0) livelyChunkSet += key

            metrics[key] = RenewalChunkMetrics(
                forgettabilityIndex = if (forgettableByChunk[key] == true) 1.0 else 0.0,
                livelinessIndex = lively,
            )
        }

        val decision = computeDecision(
            snapshot = snapshot,
            livelyChunkSet = livelyChunkSet,
            forgettableByChunk = forgettableByChunk,
        )

        when (decision) {
            is RenewalDecision.Region -> {
                metrics.replaceAll { key, value ->
                    if (key.world.value.toString() == decision.region.worldId &&
                        key.regionX == decision.region.regionX &&
                        key.regionZ == decision.region.regionZ
                    ) {
                        value.copy(eligibleByRegionCandidateIndex = 1.0)
                    } else {
                        value
                    }
                }
            }

            is RenewalDecision.ChunkBatch -> {
                decision.chunks.forEach { key ->
                    val value = metrics[key] ?: RenewalChunkMetrics()
                    metrics[key] = value.copy(eligibleByChunkCandidateIndex = 1.0)
                }
            }

            null -> Unit
        }

        return WorkerResult(
            generation = generation,
            metricsByChunk = metrics,
            decision = decision,
        )
    }

    private data class SnapshotIndex(
        val byKey: Map<ChunkKey, ChunkScanSnapshotEntry>,
        val keysByWorld: Map<RegistryKey<World>, List<ChunkKey>>,
    )

    private fun buildSnapshotIndex(entries: List<ChunkScanSnapshotEntry>): SnapshotIndex {
        val byKey = entries.associateBy { it.key }
        val keysByWorld = entries
            .groupBy { it.key.world }
            .mapValues { (_, list) -> list.map { it.key } }

        return SnapshotIndex(
            byKey = byKey,
            keysByWorld = keysByWorld,
        )
    }

    private fun computeDecision(
        snapshot: List<ChunkScanSnapshotEntry>,
        livelyChunkSet: Set<ChunkKey>,
        forgettableByChunk: Map<ChunkKey, Boolean>,
    ): RenewalDecision? {
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
        index: SnapshotIndex,
    ): Double {
        val current = index.byKey[key]
        val currentTicks = current?.signals?.inhabitedTimeTicks
        if (currentTicks == null) return 0.0
        if (currentTicks > 0L) return 0.0

        val worldKeys = index.keysByWorld[key.world] ?: return 0.0
        val hasNeighborWithActivity = worldKeys.asSequence()
            .filter {
                val dx = kotlin.math.abs(it.chunkX - key.chunkX)
                val dz = kotlin.math.abs(it.chunkZ - key.chunkZ)
                kotlin.math.max(dx, dz) <= 32
            }
            .any { neighborKey ->
                val neighbor = index.byKey[neighborKey]
                val t = neighbor?.signals?.inhabitedTimeTicks
                t == null || t > 0L
            }

        return if (hasNeighborWithActivity) 0.0 else 1.0
    }
}
