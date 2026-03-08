package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.renewal.election.RenewalElection
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkMetadataFact
import ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry
import ch.oliverlanz.memento.domain.worldmap.DominantStoneEffectSignal
import ch.oliverlanz.memento.domain.worldmap.DominantStoneSignal
import ch.oliverlanz.memento.domain.worldmap.WorldMapService
import ch.oliverlanz.memento.infrastructure.async.GlobalAsyncExclusionGate
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

fun interface RenewalProjectionStableListener {
    fun onProjectionStable(snapshot: RenewalPublishedView)
}

/**
 * Partitioned boolean renewal projection.
 *
 * Projection boundary contract:
 * - Projection state is fully derived from WorldMap substrate facts.
 * - Projection-derived state is never persisted back into WorldMap.
 * - Commit/publish is atomic and tick-thread only.
 * - Worker computes from immutable seeds and never mutates committed caches directly.
 * - Region-level projection caches are projection-internal and fully recomputable.
 * - Dirty-set roles (source-dirty, affected-dirty, context-only) are distinct and non-interchangeable.
 *
 * Invariants:
 * - Projection state is fully derived and recomputable.
 * - Region-level eligibility is derived from region forgettability only.
 * - Chunk-level eligibility is derived independently and does not consult region forgettability.
 * - Protection dominance is encoded before execution by stone-authority/derivation surfaces.
 * - No mixed arbitration state exists between region and chunk mechanisms.
 * - Only affected-dirty regions are mutated at commit.
 * - Unaffected regions remain bitwise identical across commits.
 * - Election is derived exclusively from committed state.
 * - No numeric ranking surfaces exist.
 */
class RenewalProjection {

    private companion object {
        // NOTE:
        // Dirty-expansion dependency radius must remain >= memorability expansion radius.
        // Current coupling: 1 region ring == 32 chunks, memorability radius == 24 chunks.
        // If memorability radius changes, dirty expansion rules must be updated accordingly.
        private const val AFFECTED_EXPANSION_RING_REGIONS = 1
        private const val CONTEXT_EXPANSION_RING_REGIONS = 1
    }

    private var worldMapService: WorldMapService? = null

    private val stableListeners = CopyOnWriteArrayList<RenewalProjectionStableListener>()

    // Tick-thread owned projection caches (derived only; never persisted into WorldMap)
    private val chunkSourceByKey = linkedMapOf<ChunkKey, ChunkScanSnapshotEntry>()
    private val chunkDerivationByKey = linkedMapOf<ChunkKey, RenewalChunkDerivation>()
    private val regionForgettableByRegion = linkedMapOf<RegionKey, Boolean>()

    // Dirty lifecycle (tick-thread only)
    private val sourceDirtyRegions = linkedSetOf<RegionKey>()
    private var dirtySinceMs: Long? = null
    private var scanRecomputeRequested: Boolean = false
    private var cacheInvalidationRequested: Boolean = false

    @Volatile
    private var publishedView: RenewalPublishedView =
        RenewalPublishedView(
            generation = 0L,
            chunkDerivationByChunk = emptyMap(),
            regionForgettableByRegion = emptyMap(),
            rankedCandidates = emptyList(),
        )

    private var generationHead: Long = 0L
    private var inFlight: InFlightJob? = null
    private var supersededDuringInFlight: Boolean = false
    private var blockedOnGate: Boolean = false
    private var lastCompletedDurationMs: Long? = null
    private var lastCompletedAtMs: Long? = null
    private var lastCompletedReason: TriggerReason? = null

    @Volatile
    private var attached: Boolean = false

    private data class InFlightJob(
        val generation: Long,
        val reason: TriggerReason,
        val startedAtMs: Long,
        val future: java.util.concurrent.Future<CandidateView>,
    )

    private enum class TriggerReason {
        DIRTY_THRESHOLD,
        DEBOUNCE,
        SCAN_COMPLETED,
        CACHE_INVALIDATION,
    }

    private data class CandidateView(
        val generation: Long,
        val affectedRegions: List<RegionKey>,
        val regionForgettableUpdates: Map<RegionKey, Boolean>,
        val chunkDerivationUpdates: Map<ChunkKey, RenewalChunkDerivation>,
        val materializedAffectedRegionCount: Int,
        val materializedContextRegionCount: Int,
        val fullSnapshotRecoveryUsed: Boolean,
    )

    fun attach(service: WorldMapService) {
        worldMapService = service
        attached = true
        rebuildSourceCacheFromWorldSnapshot(reason = "attach")
        cacheInvalidationRequested = true
    }

    fun detach() {
        attached = false
        worldMapService = null
        inFlight?.future?.cancel(true)
        inFlight = null
        sourceDirtyRegions.clear()
        dirtySinceMs = null
        scanRecomputeRequested = false
        cacheInvalidationRequested = false
        supersededDuringInFlight = false
        blockedOnGate = false
        lastCompletedDurationMs = null
        lastCompletedAtMs = null
        lastCompletedReason = null
        generationHead = 0L

        chunkSourceByKey.clear()
        chunkDerivationByKey.clear()
        regionForgettableByRegion.clear()

        publishedView = RenewalPublishedView(
            generation = 0L,
            chunkDerivationByChunk = emptyMap(),
            regionForgettableByRegion = emptyMap(),
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

    fun hasPendingChanges(): Boolean {
        return sourceDirtyRegions.isNotEmpty() || inFlight != null || cacheInvalidationRequested || scanRecomputeRequested
    }

    fun statusView(): RenewalProjectionStatusView {
        val now = System.currentTimeMillis()
        val runningDuration = inFlight?.let { now - it.startedAtMs }
        val published = publishedView
        return RenewalProjectionStatusView(
            pendingWorkSetSize = sourceDirtyRegions.size,
            trackedChunks = chunkDerivationByKey.size,
            trackedRegions = regionForgettableByRegion.size,
            committedGeneration = published.generation,
            blockedOnGate = blockedOnGate,
            runningDurationMs = runningDuration,
            lastCompletedDurationMs = lastCompletedDurationMs,
            lastCompletedAtMs = lastCompletedAtMs,
            lastCompletedReason = lastCompletedReason?.name,
        )
    }

    fun publishedViewOrNull(): RenewalPublishedView? {
        if (!attached) return null
        return publishedView
    }

    fun committedSnapshotOrNull(): RenewalCommittedSnapshot? = publishedViewOrNull()

    fun currentCandidates(limit: Int): List<RenewalRankedCandidate> {
        if (limit <= 0) return emptyList()
        return publishedView.rankedCandidates.take(limit)
    }

    fun isStillElected(snapshot: RenewalPublishedView, candidateId: RenewalCandidateId): Boolean {
        return snapshot.rankedCandidates.any { it.id == candidateId }
    }

    fun observeFactApplied(fact: ChunkMetadataFact) {
        if (!attached) return

        val deleteFact =
            fact.signals == null &&
                fact.dominantStone == null &&
                fact.dominantStoneEffect == null

        if (deleteFact) {
            chunkSourceByKey.remove(fact.key)
        } else {
            val existing = chunkSourceByKey[fact.key]
            val mergedSignals = fact.signals ?: existing?.signals
            val mergedDominantStone = fact.dominantStone ?: existing?.dominantStone ?: DominantStoneSignal.NONE
            val mergedDominantStoneEffect = fact.dominantStoneEffect ?: existing?.dominantStoneEffect ?: DominantStoneEffectSignal.NONE

            if (mergedSignals == null && existing == null) {
                // Stone-only fact for a key that is not scanned yet: no projection source row to track.
                return
            }

            chunkSourceByKey[fact.key] = ChunkScanSnapshotEntry(
                key = fact.key,
                signals = mergedSignals,
                dominantStone = mergedDominantStone,
                dominantStoneEffect = mergedDominantStoneEffect,
                scanTick = existing?.scanTick ?: fact.scanTick,
                provenance = existing?.provenance ?: fact.source,
                unresolvedReason = fact.unresolvedReason ?: existing?.unresolvedReason,
            )
        }

        markSourceDirty(regionOf(fact.key))
        if (inFlight != null) supersededDuringInFlight = true
        blockedOnGate = false
    }

    fun observeWorldScanCompleted() {
        if (!attached) return
        // Recovery path allowed to materialize from authoritative world snapshot.
        rebuildSourceCacheFromWorldSnapshot(reason = "scan_completed")
        scanRecomputeRequested = true
        if (inFlight != null) supersededDuringInFlight = true
        blockedOnGate = false
    }

    fun tick() {
        if (!attached) return
        maybeFinalizeInFlight()
    }

    fun onMediumPulse() {
        if (!attached) return
        if (inFlight != null) return
        val reason = nextTriggerReason(System.currentTimeMillis()) ?: return
        dispatchWorker(reason)
    }

    private fun nextTriggerReason(nowMs: Long): TriggerReason? {
        if (cacheInvalidationRequested) return TriggerReason.CACHE_INVALIDATION
        if (scanRecomputeRequested) return TriggerReason.SCAN_COMPLETED

        val dirtyCount = sourceDirtyRegions.size
        val firstDirtyAt = dirtySinceMs

        if (dirtyCount == 0) return null
        if (dirtyCount >= MementoConstants.MEMENTO_RENEWAL_PROJECTION_DIRTY_REGION_TRIGGER_THRESHOLD) {
            return TriggerReason.DIRTY_THRESHOLD
        }
        if (firstDirtyAt != null && nowMs - firstDirtyAt >= MementoConstants.MEMENTO_RENEWAL_PROJECTION_DIRTY_REGION_DEBOUNCE_MS) {
            return TriggerReason.DEBOUNCE
        }
        return null
    }

    private fun dispatchWorker(reason: TriggerReason) {
        val generation = generationHead + 1L

        val sourceSeed: Map<ChunkKey, ChunkScanSnapshotEntry> = LinkedHashMap(chunkSourceByKey)
        val dirtySeed = when {
            cacheInvalidationRequested || scanRecomputeRequested ->
                sourceSeed.keys.asSequence().map(::regionOf).toSortedSet(regionOrder()).toList()

            else -> sourceDirtyRegions.toList().sortedWith(regionOrder())
        }

        val submit = GlobalAsyncExclusionGate.submitIfIdle(
            concept = MementoConcept.PROJECTION,
            owner = "renewal-projection",
        ) {
            Callable {
                computeCandidateView(
                    generation = generation,
                    sourceSnapshotByKey = sourceSeed,
                    sourceDirtySeed = dirtySeed,
                    fullSnapshotRecovery = cacheInvalidationRequested || scanRecomputeRequested,
                )
            }
        }

        when (submit) {
            is GlobalAsyncExclusionGate.SubmitResult.Busy -> {
                blockedOnGate = true
                MementoLog.debug(
                    MementoConcept.PROJECTION,
                    "worker blocked-on-gate reason={} activeOwner={} pendingSourceDirty={} retry=medium-cadence",
                    reason.name,
                    submit.activeOwner,
                    sourceDirtyRegions.size,
                )
            }

            is GlobalAsyncExclusionGate.SubmitResult.Accepted -> {
                generationHead = generation
                blockedOnGate = false
                supersededDuringInFlight = false

                if (cacheInvalidationRequested) cacheInvalidationRequested = false
                if (scanRecomputeRequested) scanRecomputeRequested = false
                sourceDirtyRegions.clear()
                dirtySinceMs = null

                inFlight = InFlightJob(
                    generation = generation,
                    reason = reason,
                    startedAtMs = System.currentTimeMillis(),
                    future = submit.future,
                )

                MementoLog.debug(
                    MementoConcept.PROJECTION,
                    "worker start generation={} reason={} sourceDirtySeed={} fullSnapshotRecovery={} retryPolicy=medium-cadence-retained-intent",
                    generation,
                    reason.name,
                    dirtySeed.size,
                    reason == TriggerReason.CACHE_INVALIDATION || reason == TriggerReason.SCAN_COMPLETED,
                )
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
                "worker discard generation={} reason={} durationMs={} head={} superseded={} pendingSourceDirty={}",
                result.generation,
                job.reason.name,
                durationMs,
                generationHead,
                supersededDuringInFlight,
                sourceDirtyRegions.size,
            )
            supersededDuringInFlight = false
            blockedOnGate = false
            return
        }

        // Commit mutation guard:
        // - Generation validation (above) must pass before any cache mutation.
        // - Only affected-dirty regions may be mutated at commit.
        // - No cross-region spillover is permitted.
        val affected = result.affectedRegions.toSet()
        val affectedKey = { key: ChunkKey -> affected.contains(regionOf(key)) }

        chunkDerivationByKey.entries.removeIf { (key, _) -> affectedKey(key) }
        result.chunkDerivationUpdates.forEach { (key, value) ->
            if (affectedKey(key)) {
                chunkDerivationByKey[key] = value
            }
        }

        regionForgettableByRegion.entries.removeIf { (region, _) -> affected.contains(region) }
        result.regionForgettableUpdates.forEach { (region, forgettable) ->
            if (affected.contains(region)) {
                regionForgettableByRegion[region] = forgettable
            }
        }

        // Projection remains read/derive-only: election authority is invoked as a
        // separate stage from committed projection output.
        val election = RenewalElection.evaluate(
            RenewalElectionInput(
                generation = result.generation,
                regionForgettableByRegion = regionForgettableByRegion.toMap(),
                chunkDerivationByChunk = chunkDerivationByKey.toMap(),
            )
        )
        val ranked = RenewalElection.asRankedCandidates(election)

        val committed = RenewalPublishedView(
            generation = result.generation,
            chunkDerivationByChunk = chunkDerivationByKey.toMap(),
            regionForgettableByRegion = regionForgettableByRegion.toMap(),
            rankedCandidates = ranked,
        )
        publishedView = committed

        supersededDuringInFlight = false
        blockedOnGate = false

        MementoLog.debug(
            MementoConcept.PROJECTION,
            "worker commit generation={} reason={} durationMs={} affectedRegions={} contextRegions={} trackedRegions={} trackedChunks={} rankedCandidates={} fullSnapshotRecovery={}",
            committed.generation,
            job.reason.name,
            durationMs,
            result.materializedAffectedRegionCount,
            result.materializedContextRegionCount,
            committed.regionForgettableByRegion.size,
            committed.chunkDerivationByChunk.size,
            committed.rankedCandidates.size,
            result.fullSnapshotRecoveryUsed,
        )

        stableListeners.forEach { listener ->
            try {
                listener.onProjectionStable(committed)
            } catch (t: Throwable) {
                MementoLog.error(MementoConcept.RENEWAL, "projection stable-listener failed", t)
            }
        }
    }

    private fun computeCandidateView(
        generation: Long,
        sourceSnapshotByKey: Map<ChunkKey, ChunkScanSnapshotEntry>,
        sourceDirtySeed: List<RegionKey>,
        fullSnapshotRecovery: Boolean,
    ): CandidateView {
        // Phase contract (boolean partitioned derivation):
        // Pass 1: region forgettable (region-only).
        // Pass 2: memorable source chunks (absolute inhabited threshold OR lore influence).
        // Pass 3: memorability expansion (radius = MEMENTO_RENEWAL_MEMORABLE_EXPANSION_RADIUS_CHUNKS).
        // Pass 4: chunk eligibility = !memorable (stone-driven path is independent from region forgettability).
        //
        // Region prune and chunk renewal eligibility are separate domains and must not be conflated.
        if (sourceSnapshotByKey.isEmpty()) {
            return CandidateView(
                generation = generation,
                affectedRegions = emptyList(),
                regionForgettableUpdates = emptyMap(),
                chunkDerivationUpdates = emptyMap(),
                materializedAffectedRegionCount = 0,
                materializedContextRegionCount = 0,
                fullSnapshotRecoveryUsed = fullSnapshotRecovery,
            )
        }

        val sourceDirty = sourceDirtySeed.toSortedSet(regionOrder())
        if (sourceDirty.isEmpty()) {
            return CandidateView(
                generation = generation,
                affectedRegions = emptyList(),
                regionForgettableUpdates = emptyMap(),
                chunkDerivationUpdates = emptyMap(),
                materializedAffectedRegionCount = 0,
                materializedContextRegionCount = 0,
                fullSnapshotRecoveryUsed = fullSnapshotRecovery,
            )
        }

        val allKnownRegions = sourceSnapshotByKey.keys
            .asSequence()
            .map(::regionOf)
            .toSet()

        val affectedAll = expandRing(sourceDirty, AFFECTED_EXPANSION_RING_REGIONS)
            .filter { allKnownRegions.contains(it) }
            .toSortedSet(regionOrder())

        val affected = affectedAll
            .take(MementoConstants.MEMENTO_RENEWAL_MAX_AFFECTED_REGIONS_PER_DISPATCH)
            .toSortedSet(regionOrder())

        MementoLog.debug(
            MementoConcept.PROJECTION,
            "partition window generation={} maxAffectedPerDispatch={} sourceDirty={} affectedAll={} affectedSelected={} overflowRetained={}",
            generation,
            MementoConstants.MEMENTO_RENEWAL_MAX_AFFECTED_REGIONS_PER_DISPATCH,
            sourceDirty.size,
            affectedAll.size,
            affected.size,
            (affectedAll.size - affected.size).coerceAtLeast(0),
        )

        val contextAll = expandRing(affected, CONTEXT_EXPANSION_RING_REGIONS)
            .filter { allKnownRegions.contains(it) }
            .toMutableSet()
        contextAll.removeAll(affected)
        val contextOnly = contextAll.toSortedSet(regionOrder())

        val materializedRegions = linkedSetOf<RegionKey>().apply {
            addAll(affected)
            addAll(contextOnly)
        }

        val entriesByRegion = sourceSnapshotByKey.values
            .asSequence()
            .filter { materializedRegions.contains(regionOf(it.key)) }
            .groupBy { regionOf(it.key) }

        val regionHasInhabited = linkedMapOf<RegionKey, Boolean>()
        val regionHasLore = linkedMapOf<RegionKey, Boolean>()

        materializedRegions.sortedWith(regionOrder()).forEach { region ->
            val entries = entriesByRegion[region].orEmpty()
            regionHasInhabited[region] = entries.any { (it.signals?.inhabitedTimeTicks ?: 0L) > 0L }
            regionHasLore[region] = entries.any { entry ->
                entry.dominantStoneEffect == DominantStoneEffectSignal.LORE_PROTECT
            }
        }

        val regionForgettableUpdates = linkedMapOf<RegionKey, Boolean>()
        affected.forEach { region ->
            val neighbors = neighborRegions8(region)
            val localInhabited = regionHasInhabited[region] == true
            val localLore = regionHasLore[region] == true
            val neighborInhabited = neighbors.any { regionHasInhabited[it] == true }
            val neighborLore = neighbors.any { regionHasLore[it] == true }
            regionForgettableUpdates[region] = !(localInhabited || localLore || neighborInhabited || neighborLore)
        }

        val allEntriesInMaterialized = entriesByRegion.values.flatten()
        val memorableSourcePackedByWorld = linkedMapOf<RegistryKey<World>, MutableSet<Long>>()
        allEntriesInMaterialized.forEach { entry ->
            val inhabitedTicks = entry.signals?.inhabitedTimeTicks ?: 0L
            val hasLore = entry.dominantStoneEffect == DominantStoneEffectSignal.LORE_PROTECT

            if (inhabitedTicks >= MementoConstants.MEMENTO_RENEWAL_MEMORABLE_INHABITED_TICKS_THRESHOLD || hasLore) {
                memorableSourcePackedByWorld
                    .computeIfAbsent(entry.key.world) { linkedSetOf() }
                    .add(packChunk(entry.key.chunkX, entry.key.chunkZ))
            }
        }

        val chunkDerivationUpdates = linkedMapOf<ChunkKey, RenewalChunkDerivation>()
        affected.forEach { region ->
            val entries = entriesByRegion[region].orEmpty()
            entries.forEach { entry ->
                val key = entry.key
                val packed = packChunk(key.chunkX, key.chunkZ)
                val sourceSet = memorableSourcePackedByWorld[key.world].orEmpty()
                val memorable = if (sourceSet.contains(packed)) {
                    true
                } else {
                    hasSourceWithinRadius(
                        candidate = key,
                        sourcePacked = sourceSet,
                        radius = MementoConstants.MEMENTO_RENEWAL_MEMORABLE_EXPANSION_RADIUS_CHUNKS,
                    )
                }

                chunkDerivationUpdates[key] = RenewalChunkDerivation(
                    memorable = memorable,
                    // Stone-driven chunk renewal is intentionally independent of region forgettability.
                    // Chunk renewal eligibility is derived from chunk-local memorability/protection only.
                    eligibleChunkRenewal = !memorable,
                )
            }
        }

        return CandidateView(
            generation = generation,
            affectedRegions = affected.toList(),
            regionForgettableUpdates = regionForgettableUpdates,
            chunkDerivationUpdates = chunkDerivationUpdates,
            materializedAffectedRegionCount = affected.size,
            materializedContextRegionCount = contextOnly.size,
            fullSnapshotRecoveryUsed = fullSnapshotRecovery,
        )
    }

    private fun rebuildSourceCacheFromWorldSnapshot(reason: String) {
        val service = worldMapService ?: return
        val snapshot = service.substrate().snapshot()
        chunkSourceByKey.clear()
        snapshot.forEach { entry ->
            chunkSourceByKey[entry.key] = entry
            markSourceDirty(regionOf(entry.key))
        }
        MementoLog.debug(
            MementoConcept.PROJECTION,
            "projection source-cache rebuild reason={} scannedEntries={} mode=full-snapshot-recovery",
            reason,
            snapshot.size,
        )
    }

    private fun markSourceDirty(region: RegionKey) {
        if (sourceDirtyRegions.add(region) && dirtySinceMs == null) {
            dirtySinceMs = System.currentTimeMillis()
        }
    }

    private fun regionOf(key: ChunkKey): RegionKey =
        RegionKey(
            worldId = key.world.value.toString(),
            regionX = key.regionX,
            regionZ = key.regionZ,
        )

    private fun regionOrder(): Comparator<RegionKey> =
        compareBy<RegionKey> { it.worldId }
            .thenBy { it.regionX }
            .thenBy { it.regionZ }

    private fun expandRing(seed: Set<RegionKey>, ring: Int): Set<RegionKey> {
        if (ring <= 0) return seed
        val out = linkedSetOf<RegionKey>()
        seed.forEach { region ->
            for (dx in -ring..ring) {
                for (dz in -ring..ring) {
                    out += RegionKey(
                        worldId = region.worldId,
                        regionX = region.regionX + dx,
                        regionZ = region.regionZ + dz,
                    )
                }
            }
        }
        return out
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

    private fun hasSourceWithinRadius(candidate: ChunkKey, sourcePacked: Set<Long>, radius: Int): Boolean {
        if (sourcePacked.isEmpty()) return false
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                val packed = packChunk(candidate.chunkX + dx, candidate.chunkZ + dz)
                if (sourcePacked.contains(packed)) return true
            }
        }
        return false
    }

    private fun packChunk(x: Int, z: Int): Long {
        return (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
    }
}
