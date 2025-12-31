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

    /**
     * Batches that reached a terminal state during an observation pass.
     *
     * We defer actual removal until after the current iteration over the batch map completes
     * to avoid concurrent modification.
     */
    private val pendingRetire: MutableMap<String, RenewalTrigger> = mutableMapOf()

    fun subscribe(handler: (RenewalEvent) -> Unit) {
        subscribers.add(handler)
    }

    fun unsubscribe(handler: (RenewalEvent) -> Unit) {
        subscribers.remove(handler)
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


    /**
     * Applies an initial observation snapshot of chunk load state for the given batch.
     *
     * This is used to establish ground truth for unload gating immediately after batch creation / reattachment,
     * without requiring a prior load/unload cycle in this server session.
     */
    fun applyInitialSnapshot(batchName: String, loadedChunks: Set<ChunkPos>, trigger: RenewalTrigger) {
        val batch = batchesByName[batchName] ?: return

        batch.applyInitialLoadedSnapshot(loadedChunks)
        emit(InitialSnapshotApplied(batchName, trigger, loaded = loadedChunks.size, unloaded = batch.chunks.size - loadedChunks.size))

        if (batch.state == RenewalBatchState.WAITING_FOR_UNLOAD) {
            if (batch.allUnloadedSimultaneously()) {
                transitionGatePassed(batch, trigger, to = RenewalBatchState.UNLOAD_COMPLETED)
                transitionGatePassed(batch, trigger, to = RenewalBatchState.QUEUED_FOR_RENEWAL)
            } else {
                emit(GateAttempted(batchName, trigger, batch.state, RenewalBatchState.UNLOAD_COMPLETED))
            }
        }
    }

    fun removeBatch(name: String, trigger: RenewalTrigger) {
        if (batchesByName.remove(name) != null) {
            emit(BatchRemoved(name, trigger))
        }
    }

    private fun flushPendingRetire() {
        if (pendingRetire.isEmpty()) return
        val toRemove = pendingRetire.toMap()
        pendingRetire.clear()
        for ((name, trigger) in toRemove) {
            removeBatch(name, trigger)
        }
    }

    fun observeChunkUnloaded(pos: ChunkPos) {
        for ((name, batch) in batchesByName) {
            if (!batch.chunks.contains(pos)) continue
            batch.observeUnloaded(pos)
            emit(ChunkObserved(name, RenewalTrigger.CHUNK_UNLOAD, pos, batch.state))

            if (batch.state == RenewalBatchState.WAITING_FOR_UNLOAD) {
                if (batch.allUnloadedSimultaneously()) {
                    transitionGatePassed(batch, RenewalTrigger.CHUNK_UNLOAD, to = RenewalBatchState.UNLOAD_COMPLETED)
                    transitionGatePassed(batch, RenewalTrigger.CHUNK_UNLOAD, to = RenewalBatchState.QUEUED_FOR_RENEWAL)
                } else {
                    emit(GateAttempted(name, RenewalTrigger.CHUNK_UNLOAD, batch.state, RenewalBatchState.UNLOAD_COMPLETED))
                }
            }
        }
    }

    fun observeChunkLoaded(pos: ChunkPos) {
        for ((name, batch) in batchesByName) {
            if (!batch.chunks.contains(pos)) continue

            // Loads after a batch is complete are expected but not interesting for observability.
            if (batch.state == RenewalBatchState.RENEWAL_COMPLETE) continue

            batch.observeLoaded(pos)

            // Renewal evidence is only recorded once the batch has entered QUEUED_FOR_RENEWAL.
            if (batch.state == RenewalBatchState.QUEUED_FOR_RENEWAL) {
                batch.observeRenewed(pos)
            }

            emit(ChunkObserved(name, RenewalTrigger.CHUNK_LOAD, pos, batch.state))

            if (batch.state == RenewalBatchState.QUEUED_FOR_RENEWAL) {
                if (batch.allRenewedAtLeastOnce()) {
                    transitionGatePassed(batch, RenewalTrigger.CHUNK_LOAD, to = RenewalBatchState.RENEWAL_COMPLETE)
                } else {
                    emit(GateAttempted(name, RenewalTrigger.CHUNK_LOAD, batch.state, RenewalBatchState.RENEWAL_COMPLETE))
                }
            }
        }
        flushPendingRetire()
    }

    private fun transitionGatePassed(batch: RenewalBatch, trigger: RenewalTrigger, to: RenewalBatchState) {
        val from = batch.state
        if (from == to) return

        // When a batch becomes queued for renewal, renewal evidence starts from zero.
        if (to == RenewalBatchState.QUEUED_FOR_RENEWAL) {
            batch.resetRenewalEvidence()
        }

        batch.state = to
        emit(GatePassed(batch.name, trigger, from, to))

        if (to == RenewalBatchState.RENEWAL_COMPLETE) {
            emit(BatchCompleted(batch.name, trigger, batch.dimension))
            pendingRetire[batch.name] = trigger
            return
        }

        // Execution boundary: emit the chunk set exactly once when entering QUEUED_FOR_RENEWAL.
        if (to == RenewalBatchState.QUEUED_FOR_RENEWAL) {
            emit(BatchQueuedForRenewal(batch.name, trigger, batch.dimension, batch.chunks.toList()))
        }
    }
}