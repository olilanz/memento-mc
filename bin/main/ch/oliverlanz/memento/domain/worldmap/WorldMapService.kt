package ch.oliverlanz.memento.domain.worldmap

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Domain-owned lifecycle and ingestion authority for the authoritative world map.
 *
 * Infrastructure components may publish [ChunkMetadataFact] from any thread through
 * [ingestionPort]. Facts are applied on the server tick thread via [tick].
 */
class WorldMapService(
    private val maxAppliesPerTick: Int = MementoConstants.MEMENTO_SCAN_METADATA_APPLIER_MAX_PER_TICK,
) {
    private val map = WorldMementoMap()
    private val queue = ConcurrentLinkedQueue<ChunkMetadataFact>()

    @Volatile private var attached: Boolean = false

    private val port = ChunkMetadataIngestionPort { fact -> queue.add(fact) }

    fun attach() {
        attached = true
        MementoLog.info(MementoConcept.WORLD, "world-map service attached")
    }

    fun detach() {
        attached = false
        val pending = queue.size
        queue.clear()
        MementoLog.info(MementoConcept.WORLD, "world-map service detached pendingFactsDropped={}", pending)
    }

    fun ingestionPort(): ChunkMetadataIngestionPort = port

    fun substrate(): WorldMementoMap = map

    fun pendingCount(): Int = queue.size

    /** Applies up to [maxAppliesPerTick] facts on tick thread. */
    fun tick(): Int {
        if (!attached || maxAppliesPerTick <= 0) return 0

        var applied = 0
        while (applied < maxAppliesPerTick) {
            val fact = queue.poll() ?: break
            applyFact(fact)
            applied++
        }
        return applied
    }

    private fun applyFact(fact: ChunkMetadataFact) {
        fact.signals?.let { signals ->
            map.upsertSignals(fact.key, signals)
        }

        map.markScanned(
            key = fact.key,
            scanTick = fact.scanTick,
            provenance = fact.source,
            unresolvedReason = fact.unresolvedReason,
        )
    }
}

