package ch.oliverlanz.memento.domain.worldmap

import ch.oliverlanz.memento.domain.renewal.projection.RenewalProjectionEvents
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import net.minecraft.server.MinecraftServer

/**
 * Domain-owned lifecycle and ingestion authority for the authoritative world map.
 *
 * Invariant references:
 * - WorldMapService is the sole mutation authority for WorldMementoMap institutional memory.
 * - Infrastructure publishes boundary-safe discovery/metadata facts; it does not mutate map state.
 * - All authoritative map mutation executes on tick thread.
 *
 * Responsibility boundary:
 * - Owns substrate lifecycle for server attach/detach.
 * - Owns ingestion and merge policy for factual updates.
 * - Exposes observational read surfaces over live institutional memory.
 *
 * Infrastructure components stage/prepare metadata, but authoritative map mutation happens only
 * through [applyFactOnTickThread] on the server tick thread.
 */
class WorldMapService {
    data class CoverageView(
        val discoveredUniverseCount: Int,
        val scannedSubsetCount: Int,
        val missingMetadataCount: Int,
    )

    private val map = WorldMementoMap()

    @Volatile private var attached: Boolean = false
    @Volatile private var firstFullScanCompleted: Boolean = false

    fun attach(server: MinecraftServer) {
        // World-map scan completion is runtime-derived and intentionally not persisted.
        firstFullScanCompleted = false
        attached = true
        MementoLog.info(
            MementoConcept.WORLD,
            "world-map service attached firstFullScanCompleted={}",
            firstFullScanCompleted,
        )
    }

    fun detach() {
        attached = false
        firstFullScanCompleted = false
        MementoLog.info(MementoConcept.WORLD, "world-map service detached")
    }

    fun hasInitialScanCompleted(): Boolean = firstFullScanCompleted

    fun markInitialScanCompletedOnTickThread(reason: String) {
        if (!attached) return
        if (firstFullScanCompleted) return

        firstFullScanCompleted = true
        MementoLog.info(
            MementoConcept.SCANNER,
            "initial scan completion recorded reason={}",
            reason,
        )
    }

    fun substrate(): WorldMementoMap = map

    /** Explicit discovered-universe vs scanned-subset read surface for consumers. */
    fun coverageView(): CoverageView =
        CoverageView(
            discoveredUniverseCount = map.totalChunks(),
            scannedSubsetCount = map.scannedChunks(),
            missingMetadataCount = map.missingCount(),
        )

    /** Explicit scanned-subset read surface (projection/CSV observational consumers). */
    fun observedScannedEntries(): List<ChunkScanSnapshotEntry> = map.snapshot()

    /** Explicit discovered-universe read surface (all known chunks, scanned or unscanned). */
    fun discoveredUniverseKeys(): List<ChunkKey> = map.discoveredUniverseKeys()

    /**
     * Discovery ingestion boundary on tick thread.
     *
     * Merge semantics:
     * - merge-only
     * - idempotent
     * - non-destructive (does not overwrite metadata)
     *
     * @return true when discovery inserted a new chunk existence record.
     */
    fun ingestDiscoveryOnTickThread(key: ChunkKey): Boolean {
        if (!attached) return false
        return map.ensureExistsAndReportInserted(key)
    }

    /**
     * Applies one metadata fact directly on the server tick thread.
     *
     * Caller owns bounded batching policy; this service owns authoritative map mutation semantics.
     */
    fun applyFactOnTickThread(fact: ChunkMetadataFact) {
        if (!attached) return

        val isScanFact = (fact.signals != null || fact.unresolvedReason != null)
        val signalsChanged = fact.signals?.let { signals ->
            map.upsertSignals(fact.key, signals)
        } ?: false

        val dominantStoneChanged =
            if (fact.dominantStone != null && fact.dominantStoneEffect != null) {
                map.upsertDominantStone(
                    key = fact.key,
                    dominantStone = fact.dominantStone,
                    dominantStoneEffect = fact.dominantStoneEffect,
                )
            } else {
                false
            }

        val firstScannedMark = if (isScanFact) {
            map.markScanned(
                key = fact.key,
                scanTick = fact.scanTick,
                provenance = fact.source,
                unresolvedReason = fact.unresolvedReason,
            )
        } else {
            map.ensureExists(fact.key)
            false
        }

        // Debug signal for projection churn analysis:
        // if both are false, we received another fact for an already-known, already-scanned key
        // without factual signal change.
        if (isScanFact && !signalsChanged && !firstScannedMark) {
            MementoLog.debug(
                MementoConcept.PROJECTION,
                "world-map duplicate fact key=({}, {}) world={} source={} unresolvedReason={}",
                fact.key.chunkX,
                fact.key.chunkZ,
                fact.key.world.value,
                fact.source,
                fact.unresolvedReason,
            )
        }

        // Projection recomputation trigger is strictly fact-change based (signals or stone-effect),
        // not scan-discovery based.
        if (signalsChanged || dominantStoneChanged) {
            RenewalProjectionEvents.emitFactApplied(fact)
        }
    }

    /**
     * Ambient ingestion boundary with localized monotonic merge guard.
     *
     * Ambient facts contribute to freshness only when they are newer observations or add missing
     * information without weakening already-known values.
     */
    fun applyAmbientFactOnTickThread(fact: ChunkMetadataFact): Boolean {
        if (!attached) return false
        val existing = map.scannedEntry(fact.key)
        if (!acceptAmbientFact(incoming = fact, existing = existing)) return false

        val mergedSignals = mergeAmbientSignals(existing = existing?.signals, incoming = fact.signals)
        val mergedTick = maxObservationTick(existing = existing?.scanTick, incoming = fact.scanTick)
        val preserveExistingObservationMeta = existing != null && fact.scanTick <= existing.scanTick

        // Provenance/unresolved metadata must follow the effective observation tick, not just
        // signal completeness. When an older (or equal) ambient fact is accepted only to fill
        // missing signal fields, keep the existing observation metadata so source semantics
        // remain aligned with the newer recorded observation.
        val existingObservationMeta = if (preserveExistingObservationMeta) existing else null
        val mergedProvenance = if (preserveExistingObservationMeta) {
            existingObservationMeta!!.provenance
        } else {
            fact.source
        }
        val mergedUnresolvedReason = if (preserveExistingObservationMeta) {
            existingObservationMeta!!.unresolvedReason
        } else {
            fact.unresolvedReason
        }

        val merged = fact.copy(
            signals = mergedSignals,
            scanTick = mergedTick,
            source = mergedProvenance,
            unresolvedReason = mergedUnresolvedReason,
        )

        applyFactOnTickThread(merged)
        return true
    }

    /**
     * Forces a chunk observation tick old enough to be considered stale without mutating factual
     * signals.
     *
     * This is used by regeneration acknowledgment to trigger ambient re-enrichment on the next
     * very-low sweep while preserving existing metadata payload.
     *
     * @return true when a scanned entry existed and stale-mark was applied.
     */
    fun forceStaleObservationTickOnTickThread(key: ChunkKey, staleScanTick: Long): Boolean {
        if (!attached) return false
        val existing = map.scannedEntry(key) ?: return false

        applyFactOnTickThread(
            ChunkMetadataFact(
                key = key,
                source = existing.provenance,
                unresolvedReason = existing.unresolvedReason,
                signals = existing.signals,
                scanTick = staleScanTick,
            )
        )
        return true
    }

    internal fun attachForTesting() {
        attached = true
    }

    private fun acceptAmbientFact(incoming: ChunkMetadataFact, existing: ChunkScanSnapshotEntry?): Boolean {
        if (existing == null) return true
        if (incoming.scanTick > existing.scanTick) return true
        return addsCompleteness(existing = existing.signals, incoming = incoming.signals)
    }

    private fun addsCompleteness(existing: ChunkSignals?, incoming: ChunkSignals?): Boolean {
        if (incoming == null) return false
        if (existing == null) return true

        return (existing.inhabitedTimeTicks == null && incoming.inhabitedTimeTicks != null) ||
            (existing.lastUpdateTicks == null && incoming.lastUpdateTicks != null) ||
            (existing.surfaceY == null && incoming.surfaceY != null) ||
            (existing.biomeId == null && incoming.biomeId != null) ||
            (!existing.isSpawnChunk && incoming.isSpawnChunk)
    }

    private fun mergeAmbientSignals(existing: ChunkSignals?, incoming: ChunkSignals?): ChunkSignals? {
        if (incoming == null) return existing
        if (existing == null) return incoming

        return ChunkSignals(
            inhabitedTimeTicks = incoming.inhabitedTimeTicks ?: existing.inhabitedTimeTicks,
            lastUpdateTicks = incoming.lastUpdateTicks ?: existing.lastUpdateTicks,
            surfaceY = incoming.surfaceY ?: existing.surfaceY,
            biomeId = incoming.biomeId ?: existing.biomeId,
            isSpawnChunk = existing.isSpawnChunk || incoming.isSpawnChunk,
        )
    }

    private fun maxObservationTick(existing: Long?, incoming: Long): Long {
        val current = existing ?: return incoming
        return if (incoming > current) incoming else current
    }

    /**
     * Removes all map entries for one world-region tuple on the tick thread.
     *
     * Projection recomputation is explicitly triggered for each removed key so derived renewal
     * decisions can drop stale region evidence immediately.
     */
    fun expungeRegionOnTickThread(
        world: net.minecraft.registry.RegistryKey<net.minecraft.world.World>,
        regionX: Int,
        regionZ: Int,
        scanTick: Long,
    ): Int {
        if (!attached) return 0
        val removed = map.expungeRegion(world, regionX, regionZ)
        if (removed.isEmpty()) return 0

        removed.forEach { key ->
            RenewalProjectionEvents.emitFactApplied(
                ChunkMetadataFact(
                    key = key,
                    source = ChunkScanProvenance.FILE_PRIMARY,
                    unresolvedReason = ChunkScanUnresolvedReason.FILE_MISSING,
                    signals = null,
                    scanTick = scanTick,
                )
            )
        }
        return removed.size
    }

}
