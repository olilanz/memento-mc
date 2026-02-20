package ch.oliverlanz.memento.domain.worldmap

import ch.oliverlanz.memento.domain.renewal.projection.RenewalProjectionEvents
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog

/**
 * Domain-owned lifecycle and ingestion authority for the authoritative world map.
 *
 * Infrastructure components stage/prepare metadata, but authoritative map mutation happens only
 * through [applyFactOnTickThread] on the server tick thread.
 */
class WorldMapService {
    private val map = WorldMementoMap()

    @Volatile private var attached: Boolean = false

    fun attach() {
        attached = true
        MementoLog.info(MementoConcept.WORLD, "world-map service attached")
    }

    fun detach() {
        attached = false
        MementoLog.info(MementoConcept.WORLD, "world-map service detached")
    }

    fun substrate(): WorldMementoMap = map

    /**
     * Applies one metadata fact directly on the server tick thread.
     *
     * Caller owns bounded batching policy; this service owns authoritative map mutation semantics.
     */
    fun applyFactOnTickThread(fact: ChunkMetadataFact) {
        if (!attached) return
        fact.signals?.let { signals ->
            map.upsertSignals(fact.key, signals)
        }

        map.markScanned(
            key = fact.key,
            scanTick = fact.scanTick,
            provenance = fact.source,
            unresolvedReason = fact.unresolvedReason,
        )

        RenewalProjectionEvents.emitFactApplied(fact)
    }
}
