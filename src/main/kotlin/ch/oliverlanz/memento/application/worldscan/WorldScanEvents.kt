package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance
import ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry
import ch.oliverlanz.memento.domain.worldmap.ChunkScanUnresolvedReason
import net.minecraft.server.MinecraftServer

/**
 * Scan lifecycle events.
 *
 * The scanner is always attached and continuously maintains the WorldMementoMap.
 * "Scan" refers to an *active baseline pass* that converges until all known chunks
 * have metadata attached, after which the scanner switches into passive mode.
 */

/** Listener interface owned by the scanner boundary (application layer). */
fun interface WorldScanListener {
    fun onWorldScanCompleted(server: MinecraftServer, event: WorldScanCompleted)
}

/** Emitted exactly once per completed active scan. */
data class WorldScanCompleted(
    val reason: String,
    val plannedChunks: Int,
    val scannedChunks: Int,
    val missingChunks: Int,
    /** Aggregate completion counts keyed by scan provenance enum. */
    val provenanceCounts: Map<ChunkScanProvenance, Int>,
    /** Aggregate completion counts keyed by unresolved-reason enum. */
    val unresolvedReasonCounts: Map<ChunkScanUnresolvedReason, Int>,
    /** Aggregate completion count of unresolved entries where reason is absent (null). */
    val unresolvedWithoutReasonCount: Int,
    /**
     * Snapshot of scanned chunks at completion time.
     *
     * Contract note: per-chunk entries may carry scan provenance and unresolved reason metadata
     * in addition to nullable signals.
     */
    val snapshot: List<ChunkScanSnapshotEntry>,
)
