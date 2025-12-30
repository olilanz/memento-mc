package ch.oliverlanz.memento.domain.renewal

import net.minecraft.util.math.ChunkPos

/**
 * RenewalTracker keeps accounting of active renewal batches and their state transitions.
 *
 * It does NOT perform proactive renewal itself (that is done by an executor/adaptor).
 */
object RenewalTracker {

    private val batchesByName: MutableMap<String, RenewalBatch> = mutableMapOf()

    private val subscribers: MutableList<(RenewalEvent) -> Unit> = mutableListOf()

    fun subscribe(handler: (RenewalEvent) -> Unit) {
        subscribers.add(handler)
    }

    fun snapshotBatches(): List<RenewalBatch> = batchesByName.values.toList()

    private fun emit(event: RenewalEvent) {
        for (h in subscribers) h(event)
    }

    fun upsertBatch(batch: RenewalBatch, trigger: RenewalTrigger) {
        val existing = batchesByName[batch.name]
        if (existing == null) {
            batchesByName[batch.name] = batch
            emit(BatchCreated(batch.name, trigger, batch.state, batch.chunks.size))
        } else {
            // Keep identity; update flags for new chunk set
            existing.resetToNewChunkSet(batch.chunks)
            val from = existing.state
            existing.state = batch.state
            emit(BatchUpdated(batch.name, trigger, from, existing.state, existing.chunks.size))
        }
    }

    fun removeBatch(name: String, trigger: RenewalTrigger) {
        if (batchesByName.remove(name) != null) {
            emit(BatchRemoved(name, trigger))
        }
    }

    fun observeChunkUnloaded(pos: ChunkPos) {
        for ((name, batch) in batchesByName) {
            if (!batch.chunks.contains(pos)) continue
            batch.observeUnloaded(pos)
            emit(ChunkObserved(name, RenewalTrigger.CHUNK_UNLOAD, pos, batch.state))

            if (batch.state == RenewalBatchState.WAITING_FOR_UNLOAD && batch.allUnloadedSimultaneously()) {
                emit(GateAttempted(name, RenewalTrigger.CHUNK_UNLOAD, batch.state, RenewalBatchState.UNLOAD_COMPLETED))
            }
        }
    }

    fun observeChunkLoaded(pos: ChunkPos) {
        for ((name, batch) in batchesByName) {
            if (!batch.chunks.contains(pos)) continue
            batch.observeLoaded(pos)
            emit(ChunkObserved(name, RenewalTrigger.CHUNK_LOAD, pos, batch.state))

            if (batch.state == RenewalBatchState.QUEUED_FOR_RENEWAL && batch.allRenewedAtLeastOnce()) {
                emit(GateAttempted(name, RenewalTrigger.CHUNK_LOAD, batch.state, RenewalBatchState.RENEWAL_COMPLETE))
            }
        }
    }
}
