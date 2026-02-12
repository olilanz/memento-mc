package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.domain.worldmap.WorldMementoMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Tick-thread applier for scan metadata facts.
 *
 * Enqueue is thread-safe and may be called from any thread. Application to [WorldMementoMap] is
 * bounded and must be driven from the server tick thread via [tick].
 */
class BoundedScanMetadataTickApplier(
    private val map: WorldMementoMap,
    private val maxAppliesPerTick: Int,
) : ScanMetadataIngestionPort {

    private val queue = ConcurrentLinkedQueue<ScanMetadataFact>()

    override fun ingest(fact: ScanMetadataFact) {
        queue.add(fact)
    }

    /** Applies up to [maxAppliesPerTick] queued facts; returns number of applied facts. */
    fun tick(): Int {
        if (maxAppliesPerTick <= 0) return 0

        var applied = 0
        while (applied < maxAppliesPerTick) {
            val fact = queue.poll() ?: break
            applyFact(fact)
            applied++
        }
        return applied
    }

    fun pendingCount(): Int = queue.size

    private fun applyFact(fact: ScanMetadataFact) {
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

