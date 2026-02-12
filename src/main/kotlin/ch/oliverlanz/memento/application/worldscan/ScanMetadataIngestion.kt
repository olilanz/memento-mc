package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance
import ch.oliverlanz.memento.domain.worldmap.ChunkScanUnresolvedReason
import ch.oliverlanz.memento.domain.worldmap.ChunkSignals

/**
 * Application-facing fact contract for scan metadata ingestion.
 *
 * Providers submit immutable facts through [ScanMetadataIngestionPort]. Facts are queued from any
 * thread and applied later on the server tick thread by [BoundedScanMetadataTickApplier].
 */
data class ScanMetadataFact(
    val key: ChunkKey,
    /** Provenance of this scan outcome (file-primary, fallback, unsolicited, ...). */
    val source: ChunkScanProvenance,
    /** Optional unresolved reason when [signals] is absent or partial by design. */
    val unresolvedReason: ChunkScanUnresolvedReason? = null,
    /** Optional signal payload for the chunk. Null is valid best-effort data. */
    val signals: ChunkSignals? = null,
    /**
     * Absolute world tick for this scan observation.
     *
     * Semantics: this value is written as-is to the world map scan record for [key].
     */
    val scanTick: Long,
)

/** Thread-safe enqueue boundary owned by the worldscan application layer. */
fun interface ScanMetadataIngestionPort {
    fun ingest(fact: ScanMetadataFact)
}

