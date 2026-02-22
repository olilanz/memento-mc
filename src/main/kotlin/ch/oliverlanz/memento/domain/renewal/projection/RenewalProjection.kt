package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkMetadataFact
import ch.oliverlanz.memento.domain.worldmap.WorldMapService
import ch.oliverlanz.memento.infrastructure.async.GlobalAsyncExclusionGate
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Callable
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
    private companion object {
        private const val NEIGHBOR_RADIUS = 32
    }

    private var worldMapService: WorldMapService? = null

    private val dirtySet = linkedSetOf<ChunkKey>()
    private val metricsByChunk = ConcurrentHashMap<ChunkKey, RenewalChunkMetrics>()
    private val stableListeners = CopyOnWriteArrayList<RenewalProjectionStableListener>()

    private var dirtySinceMs: Long? = null
    private var generationHead: Long = 0L
    private var appliedGeneration: Long = 0L
    private var inFlight: InFlightJob? = null
    private var supersededDuringInFlight: Boolean = false
    private var scanRecomputeRequested: Boolean = false
    private var blockedOnGate: Boolean = false
    private var lastCompletedDurationMs: Long? = null
    private var lastCompletedAtMs: Long? = null
    private var lastCompletedReason: TriggerReason? = null

    @Volatile
    private var analysisState: RenewalAnalysisState = RenewalAnalysisState.COMPUTING

    @Volatile
    private var attached: Boolean = false

    private data class InFlightJob(
        val generation: Long,
        val reason: TriggerReason,
        val startedAtMs: Long,
        val future: java.util.concurrent.Future<WorkerResult>,
    )

    private enum class TriggerReason {
        DIRTY_THRESHOLD,
        DEBOUNCE,
        SCAN_COMPLETED,
    }

    private data class WorkerResult(
        val generation: Long,
        val snapshotEntries: List<ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry>,
        val metricsByChunk: Map<ChunkKey, RenewalChunkMetrics>,
    )

    fun attach(service: WorldMapService) {
        worldMapService = service
        attached = true
        analysisState = RenewalAnalysisState.COMPUTING
    }

    fun detach() {
        attached = false
        worldMapService = null
        inFlight?.future?.cancel(true)
        synchronized(dirtySet) {
            dirtySet.clear()
            dirtySinceMs = null
        }
        inFlight = null
        supersededDuringInFlight = false
        scanRecomputeRequested = false
        blockedOnGate = false
        lastCompletedDurationMs = null
        lastCompletedAtMs = null
        lastCompletedReason = null
        generationHead = 0L
        appliedGeneration = 0L
        metricsByChunk.clear()
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
        val now = System.currentTimeMillis()
        val pending = synchronized(dirtySet) { dirtySet.size }
        val runningDuration = inFlight?.let { now - it.startedAtMs }
        return RenewalProjectionStatusView(
            state = analysisState,
            pendingWorkSetSize = pending,
            trackedChunks = metricsByChunk.size,
            hasStableSnapshot = analysisState == RenewalAnalysisState.STABLE,
            stableGeneration = if (analysisState == RenewalAnalysisState.STABLE) appliedGeneration else null,
            blockedOnGate = blockedOnGate,
            runningDurationMs = runningDuration,
            lastCompletedDurationMs = lastCompletedDurationMs,
            lastCompletedAtMs = lastCompletedAtMs,
            lastCompletedReason = lastCompletedReason?.name,
        )
    }

    fun stableSnapshotOrNull(): RenewalStableSnapshot? {
        if (analysisState != RenewalAnalysisState.STABLE) return null
        val service = worldMapService ?: return null
        return RenewalStableSnapshot(
            generation = appliedGeneration,
            snapshotEntries = service.substrate().snapshot(),
            metricsByChunk = metricsByChunk.toMap(),
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
            blockedOnGate = false
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
            blockedOnGate = false
        }
    }

    fun tick() {
        if (!attached) return

        maybeFinalizeInFlight()
    }

    fun onMediumPulse() {
        if (!attached) return
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
        val completedAtMs = System.currentTimeMillis()
        val durationMs = (completedAtMs - job.startedAtMs).coerceAtLeast(0L)
        lastCompletedDurationMs = durationMs
        lastCompletedAtMs = completedAtMs
        lastCompletedReason = job.reason

        val result = try {
            job.future.get()
        } catch (t: Throwable) {
            MementoLog.error(
                MementoConcept.PROJECTION,
                "worker failed generation={} reason={} durationMs={} (state->COMPUTING)",
                t,
                job.generation,
                job.reason.name,
                durationMs,
            )
            analysisState = RenewalAnalysisState.COMPUTING
            blockedOnGate = false
            return
        }

        if (result.generation != generationHead || supersededDuringInFlight) {
            MementoLog.debug(
                MementoConcept.PROJECTION,
                "worker discard generation={} reason={} durationMs={} head={} superseded={} pendingDirty={}",
                result.generation,
                job.reason.name,
                durationMs,
                generationHead,
                supersededDuringInFlight,
                synchronized(dirtySet) { dirtySet.size },
            )
            analysisState = RenewalAnalysisState.COMPUTING
            supersededDuringInFlight = false
            blockedOnGate = false
            return
        }

        metricsByChunk.clear()
        metricsByChunk.putAll(result.metricsByChunk)
        appliedGeneration = result.generation
        analysisState = RenewalAnalysisState.STABLE
        supersededDuringInFlight = false
        blockedOnGate = false

        MementoLog.debug(
            MementoConcept.PROJECTION,
            "worker apply generation={} reason={} durationMs={} tracked={}",
            appliedGeneration,
            job.reason.name,
            durationMs,
            metricsByChunk.size,
        )

        val stable = RenewalStableSnapshot(
            generation = result.generation,
            snapshotEntries = result.snapshotEntries,
            metricsByChunk = metricsByChunk.toMap(),
        )
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
        val dirtySeedCount = synchronized(dirtySet) { dirtySet.size }

        val submit = GlobalAsyncExclusionGate.submitIfIdle(
            concept = MementoConcept.PROJECTION,
            owner = "renewal-projection",
        ) {
            Callable {
                computeWorkerResult(generation, snapshot)
            }
        }

        when (submit) {
            is GlobalAsyncExclusionGate.SubmitResult.Busy -> {
                blockedOnGate = true
                analysisState = RenewalAnalysisState.COMPUTING
                MementoLog.debug(
                    MementoConcept.PROJECTION,
                    "worker blocked-on-gate reason={} activeOwner={} pendingDirty={} retry=medium-cadence",
                    reason.name,
                    submit.activeOwner,
                    dirtySeedCount,
                )
                return
            }

            is GlobalAsyncExclusionGate.SubmitResult.Accepted -> {
                generationHead = generation
                synchronized(dirtySet) {
                    dirtySet.clear()
                    dirtySinceMs = null
                }

                scanRecomputeRequested = false
                supersededDuringInFlight = false
                blockedOnGate = false
                analysisState = RenewalAnalysisState.STABILIZING

                MementoLog.debug(
                    MementoConcept.PROJECTION,
                    "worker start generation={} reason={} dirtySeed={} snapshot={} retryPolicy=medium-cadence-retained-intent",
                    generation,
                    reason.name,
                    dirtySeedCount,
                    snapshot.size,
                )
                inFlight = InFlightJob(
                    generation = generation,
                    reason = reason,
                    startedAtMs = System.currentTimeMillis(),
                    future = submit.future,
                )
            }
        }
    }

    private fun computeWorkerResult(
        generation: Long,
        snapshot: List<ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry>,
    ): WorkerResult {
        if (snapshot.isEmpty()) {
            return WorkerResult(
                generation = generation,
                snapshotEntries = emptyList(),
                metricsByChunk = emptyMap(),
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

        return WorkerResult(
            generation = generation,
            snapshotEntries = snapshot,
            metricsByChunk = metrics,
        )
    }

    private data class SnapshotIndex(
        val byKey: Map<ChunkKey, ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry>,
        val byPackedChunkByWorld: Map<RegistryKey<World>, Map<Long, ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry>>,
    )

    private fun buildSnapshotIndex(entries: List<ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry>): SnapshotIndex {
        val byKey = entries.associateBy { it.key }
        val byPackedChunkByWorld = entries
            .groupBy { it.key.world }
            .mapValues { (_, list) ->
                linkedMapOf<Long, ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry>().apply {
                    list.forEach { entry ->
                        this[packChunk(entry.key.chunkX, entry.key.chunkZ)] = entry
                    }
                }
            }

        return SnapshotIndex(
            byKey = byKey,
            byPackedChunkByWorld = byPackedChunkByWorld,
        )
    }

    private fun computeForgettability(
        key: ChunkKey,
        index: SnapshotIndex,
    ): Double {
        val current = index.byKey[key]
        val currentTicks = current?.signals?.inhabitedTimeTicks
        if (currentTicks == null) return 0.0
        if (currentTicks > 0L) return 0.0

        val worldByPackedChunk = index.byPackedChunkByWorld[key.world] ?: return 0.0
        var hasNeighborWithActivity = false
        for (dx in -NEIGHBOR_RADIUS..NEIGHBOR_RADIUS) {
            if (hasNeighborWithActivity) break
            for (dz in -NEIGHBOR_RADIUS..NEIGHBOR_RADIUS) {
                val neighbor = worldByPackedChunk[packChunk(key.chunkX + dx, key.chunkZ + dz)]
                val t = neighbor?.signals?.inhabitedTimeTicks
                if (t == null || t > 0L) {
                    hasNeighborWithActivity = true
                    break
                }
            }
        }

        return if (hasNeighborWithActivity) 0.0 else 1.0
    }

    private fun packChunk(x: Int, z: Int): Long {
        return (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
    }
}
