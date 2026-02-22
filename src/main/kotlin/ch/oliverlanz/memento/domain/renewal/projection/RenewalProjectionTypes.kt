package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry

/**
 * Derived renewal metrics attached to a factual chunk key in the projection layer.
 *
 * Indices are modeled as Double for algorithm replaceability; current slice uses binary values.
 */
data class RenewalChunkMetrics(
    val forgettabilityIndex: Double = 0.0,
    val livelinessIndex: Double = 0.0,
)

/** Read-only operational status exposed to observational command surfaces. */
data class RenewalProjectionStatusView(
    val state: RenewalAnalysisState,
    val pendingWorkSetSize: Int,
    val trackedChunks: Int,
    val hasStableSnapshot: Boolean,
    val stableGeneration: Long? = null,
    val blockedOnGate: Boolean = false,
    val runningDurationMs: Long? = null,
    val lastCompletedDurationMs: Long? = null,
    val lastCompletedAtMs: Long? = null,
    val lastCompletedReason: String? = null,
)

/**
 * Stable projection export payload.
 *
 * Produced only when analysis state is STABLE and consumed by read-only adapters
 * (CSV exporter, inspect surfaces).
 */
data class RenewalStableSnapshot(
    val generation: Long,
    val snapshotEntries: List<ChunkScanSnapshotEntry>,
    val metricsByChunk: Map<ChunkKey, RenewalChunkMetrics>,
)
