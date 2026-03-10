package ch.oliverlanz.memento.domain.worldmap

import ch.oliverlanz.memento.domain.renewal.projection.RenewalProjectionEvents
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import net.minecraft.server.MinecraftServer

/**
 * Domain-owned lifecycle and ingestion authority for the authoritative world map.
 *
 * Infrastructure components stage/prepare metadata, but authoritative map mutation happens only
 * through [applyFactOnTickThread] on the server tick thread.
 */
class WorldMapService {
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
