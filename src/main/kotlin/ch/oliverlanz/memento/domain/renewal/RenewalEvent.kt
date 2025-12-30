package ch.oliverlanz.memento.domain.renewal

import net.minecraft.util.math.ChunkPos

/**
 * Domain-level events for RenewalTracker observability + integration.
 *
 * Rule of thumb:
 *  - "Attempted*" events are purely observational (not consumed for behavior).
 *  - Successful transition events may be consumed by other components.
 */
sealed interface RenewalEvent {
    val trigger: RenewalTrigger
}

data class BatchCreated(
    val batchName: String,
    override val trigger: RenewalTrigger,
    val state: RenewalBatchState,
    val chunkCount: Int
) : RenewalEvent

data class BatchUpdated(
    val batchName: String,
    override val trigger: RenewalTrigger,
    val from: RenewalBatchState,
    val to: RenewalBatchState,
    val chunkCount: Int
) : RenewalEvent

data class BatchRemoved(
    val batchName: String,
    override val trigger: RenewalTrigger
) : RenewalEvent

/**
 * Observability only: a chunk event that is relevant to an existing batch.
 * Not emitted for irrelevant chunks.
 */
data class ChunkObserved(
    val batchName: String,
    override val trigger: RenewalTrigger,
    val chunk: ChunkPos,
    val state: RenewalBatchState
) : RenewalEvent

/**
 * Observability only: indicates we attempted a gate transition but did not pass.
 * Example: we saw an unload for the "last missing chunk", but another chunk has since re-loaded.
 */
data class GateAttempted(
    val batchName: String,
    override val trigger: RenewalTrigger,
    val from: RenewalBatchState,
    val attempted: RenewalBatchState
) : RenewalEvent
