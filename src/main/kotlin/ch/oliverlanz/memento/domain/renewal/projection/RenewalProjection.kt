package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.renewal.eligibility.EligibilityService
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
    fun onProjectionStable(snapshot: RenewalCommittedSnapshot)
}

/**
 * Hybrid projection authority model.
 *
 * Authority boundaries:
 * - Tick thread owns mutable projection state, generation assignment, and commit publication.
 * - Worker thread owns snapshot materialization and pure computation on that snapshot.
 * - Worker output is generation-tagged and must pass generation/supersession checks before commit.
 */
class RenewalProjection {
    private companion object {
        private const val NEIGHBOR_RADIUS = 32
    }

    private var worldMapService: WorldMapService? = null

    private val dirtySet = linkedSetOf<ChunkKey>()
    private val stableListeners = CopyOnWriteArrayList<RenewalProjectionStableListener>()
    private val metricsByChunk = ConcurrentHashMap<ChunkKey, RenewalChunkMetrics>()

    @Volatile
    private var committedSnapshot: RenewalCommittedSnapshot =
        RenewalCommittedSnapshot(
            generation = 0L,
            snapshotEntries = emptyList(),
            metricsByChunk = emptyMap(),
            rankedCandidates = emptyList(),
        )

    private var dirtySinceMs: Long? = null
    private var generationHead: Long = 0L
    private var inFlight: InFlightJob? = null
    private var supersededDuringInFlight: Boolean = false
    private var scanRecomputeRequested: Boolean = false
    private var blockedOnGate: Boolean = false
    private var lastCompletedDurationMs: Long? = null
    private var lastCompletedAtMs: Long? = null
    private var lastCompletedReason: TriggerReason? = null

    @Volatile
    private var attached: Boolean = false

    /**
     * Read-only safety signal for command surfaces.
     *
     * true means the projection has observed world-map changes not yet represented in a committed
     * snapshot (dirty pending and/or worker running).
     */
    fun hasPendingChanges(): Boolean {
        val hasDirty = synchronized(dirtySet) { dirtySet.isNotEmpty() }
        return hasDirty || inFlight != null
    }

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
        val rankedCandidates: List<RenewalRankedCandidate>,
    )

    fun attach(service: WorldMapService) {
        worldMapService = service
        attached = true
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
        metricsByChunk.clear()
        committedSnapshot = RenewalCommittedSnapshot(
            generation = 0L,
            snapshotEntries = emptyList(),
            metricsByChunk = emptyMap(),
            rankedCandidates = emptyList(),
        )
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
        val committed = committedSnapshot
        return RenewalProjectionStatusView(
            pendingWorkSetSize = pending,
            trackedChunks = metricsByChunk.size,
            committedGeneration = committed.generation,
            blockedOnGate = blockedOnGate,
            runningDurationMs = runningDuration,
            lastCompletedDurationMs = lastCompletedDurationMs,
            lastCompletedAtMs = lastCompletedAtMs,
            lastCompletedReason = lastCompletedReason?.name,
        )
    }

    fun committedSnapshotOrNull(): RenewalCommittedSnapshot? {
        if (!attached) return null
        return committedSnapshot
    }

    fun topRankedCandidates(limit: Int): List<RenewalRankedCandidate> {
        if (limit <= 0) return emptyList()
        return committedSnapshot.rankedCandidates.take(limit)
    }

    fun isStillEligible(snapshot: RenewalCommittedSnapshot, candidateId: RenewalCandidateId): Boolean {
        return snapshot.rankedCandidates.any { it.id == candidateId }
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
        blockedOnGate = false
    }

    fun observeWorldScanCompleted() {
        if (!attached) return
        scanRecomputeRequested = true
        if (inFlight != null) {
            supersededDuringInFlight = true
        }
        blockedOnGate = false
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
                "worker failed generation={} reason={} durationMs={}",
                t,
                job.generation,
                job.reason.name,
                durationMs,
            )
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
            supersededDuringInFlight = false
            blockedOnGate = false
            return
        }

        metricsByChunk.clear()
        metricsByChunk.putAll(result.metricsByChunk)
        supersededDuringInFlight = false
        blockedOnGate = false

        val committed = RenewalCommittedSnapshot(
            generation = result.generation,
            snapshotEntries = result.snapshotEntries,
            metricsByChunk = result.metricsByChunk,
            rankedCandidates = result.rankedCandidates,
        )
        committedSnapshot = committed

        MementoLog.debug(
            MementoConcept.PROJECTION,
            "worker commit generation={} reason={} durationMs={} tracked={} ranked={}",
            committed.generation,
            job.reason.name,
            durationMs,
            metricsByChunk.size,
            committed.rankedCandidates.size,
        )

        stableListeners.forEach { listener ->
            try {
                listener.onProjectionStable(committed)
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

        val generation = generationHead + 1L
        val dirtySeedCount = synchronized(dirtySet) { dirtySet.size }

        val submit = GlobalAsyncExclusionGate.submitIfIdle(
            concept = MementoConcept.PROJECTION,
            owner = "renewal-projection",
        ) {
            Callable {
                // Intentional off-thread snapshot: WorldMementoMap is CHM-backed and snapshot reads
                // are safe best-effort materialization under concurrent updates.
                val snapshot = service.substrate().snapshot()
                computeWorkerResult(generation, snapshot)
            }
        }

        when (submit) {
            is GlobalAsyncExclusionGate.SubmitResult.Busy -> {
                blockedOnGate = true
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

                MementoLog.debug(
                    MementoConcept.PROJECTION,
                    "worker start generation={} reason={} dirtySeed={} snapshot=pending-worker-materialization retryPolicy=medium-cadence-retained-intent",
                    generation,
                    reason.name,
                    dirtySeedCount,
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
                rankedCandidates = emptyList(),
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

        val rankedCandidates = rankedCandidates(
            generation = generation,
            snapshot = snapshot,
            metrics = metrics,
        )

        return WorkerResult(
            generation = generation,
            snapshotEntries = snapshot,
            metricsByChunk = metrics,
            rankedCandidates = rankedCandidates,
        )
    }

    private fun rankedCandidates(
        generation: Long,
        snapshot: List<ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry>,
        metrics: Map<ChunkKey, RenewalChunkMetrics>,
    ): List<RenewalRankedCandidate> {
        val result = EligibilityService.evaluate(
            RenewalCommittedSnapshot(
                generation = generation,
                snapshotEntries = snapshot,
                metricsByChunk = metrics,
                rankedCandidates = emptyList(),
            )
        )

        val out = mutableListOf<RenewalRankedCandidate>()
        var rank = 1
        result.eligibleRegions.forEach { region ->
            out += RenewalRankedCandidate(
                id = RenewalCandidateId(
                    action = RenewalCandidateAction.REGION_PRUNE,
                    worldKey = region.worldId,
                    regionX = region.regionX,
                    regionZ = region.regionZ,
                ),
                rank = rank,
                byRegionPrune = true,
            )
            rank++
        }

        if (result.eligibleRegions.isEmpty()) {
            result.eligibleChunks.forEach { chunk ->
                out += RenewalRankedCandidate(
                    id = RenewalCandidateId(
                        action = RenewalCandidateAction.CHUNK_RENEW,
                        worldKey = chunk.world.value.toString(),
                        chunkX = chunk.chunkX,
                        chunkZ = chunk.chunkZ,
                    ),
                    rank = rank,
                    byRegionPrune = false,
                )
                rank++
            }
        }

        return out
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
                // Absence in snapshot means "non-existing / not discovered chunk" and must not block
                // forgettability. Existing chunk entries with unknown ticks remain conservative blockers.
                if (neighbor == null) continue

                val t = neighbor.signals?.inhabitedTimeTicks
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
