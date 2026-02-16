package ch.oliverlanz.memento.domain.renewal

import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks and manages RenewalBatch lifecycles.
 *
 * Locked responsibility:
 * - RenewalBatch is a synchronization primitive (chunks are processed together)
 * - StoneAuthority provides eligible chunk sets
 * - RenewalTracker owns batch lifecycle + state transitions based on observations
 *
 * Domain code does not log. Observability is provided via [RenewalEvent] subscriptions.
 */
object RenewalTracker {

    /**
     * Read-only batch projection for inspection / startup snapshotting.
     *
     * Intentionally minimal and stable: no mutation, no lifecycle operations.
     */

    private val batches: MutableMap<String, RenewalBatch> = ConcurrentHashMap()
    private val listeners = linkedSetOf<(RenewalEvent) -> Unit>()

    fun subscribe(listener: (RenewalEvent) -> Unit) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: (RenewalEvent) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Read-only snapshots for inspection/reporting.
     */
    fun snapshotBatches(): List<RenewalBatchSnapshot> =
        batches.values
            .sortedBy { it.name }
            .map { b ->
                RenewalBatchSnapshot(
                    name = b.name,
                    dimension = b.dimension,
                    chunks = b.chunks,
                    state = b.state
                )
            }

    /**
     * Create or replace a batch definition.
     *
     * Rebuilds are treated as normal upserts: the tracker does not care whether a batch is new.
     */
    fun upsertBatchDefinition(
        name: String,
        dimension: RegistryKey<World>,
        chunks: Set<ChunkPos>,
        trigger: RenewalTrigger
    ) {
        val existing = batches.remove(name)
        if (existing != null) {
            emit(BatchRemoved(batchName = name, trigger = trigger))
        }

        val batch = RenewalBatch(
            name = name,
            dimension = dimension,
            chunks = chunks,
            state = RenewalBatchState.WAITING_FOR_UNLOAD
        )
        batches[name] = batch
        emit(
            BatchCreated(
                batchName = name,
                trigger = trigger,
                state = batch.state,
                chunkCount = batch.chunks.size
            )
        )

        emit(
            RenewalBatchLifecycleTransition(
                batch = RenewalBatchSnapshot(
                    name = batch.name,
                    dimension = batch.dimension,
                    chunks = batch.chunks,
                    state = batch.state,
                ),
                from = null,
                to = batch.state,
                trigger = trigger,
            )
        )
    }

    fun removeBatch(name: String, trigger: RenewalTrigger) {
        val removed = batches.remove(name) ?: return
        emit(BatchRemoved(batchName = removed.name, trigger = trigger))
    }

    /**
     * Apply an initial snapshot of loaded chunks for a given batch.
     *
     * This is purely observational: it seeds unload-gate flags without assuming gameplay intent.
     *
     * IMPORTANT:
     * - Applying evidence must be followed by the same gate evaluation as chunk unload events.
     * - Otherwise batches can stall forever at startup (no unload event can "happen" for unknown chunks).
     */
    fun applyInitialSnapshot(batchName: String, loadedChunks: Set<ChunkPos>, trigger: RenewalTrigger) {
        val batch = batches[batchName] ?: return
        batch.applyInitialLoadedSnapshot(loadedChunks)

        val unloaded = batch.chunks.count { !loadedChunks.contains(it) }
        emit(
            InitialSnapshotApplied(
                batchName = batchName,
                trigger = trigger,
                loaded = loadedChunks.size,
                unloaded = unloaded
            )
        )

        // Single code path: initial evidence can satisfy the unload gate immediately.
        maybePassUnloadGate(batch, trigger)
    }

    /**
     * Observational hook: a chunk was observed unloaded.
     *
     * Dimension is currently ignored by design (single-world assumption is enforced elsewhere).
     */
    fun observeChunkUnloaded(pos: ChunkPos) {
        for (batch in batches.values) {
            if (!batch.chunks.contains(pos)) continue

            batch.observeUnloaded(pos)
            emit(ChunkObserved(batchName = batch.name, trigger = RenewalTrigger.CHUNK_UNLOAD, chunk = pos, state = batch.state))

            // Single code path: unload evidence may satisfy the gate.
            maybePassUnloadGate(batch, RenewalTrigger.CHUNK_UNLOAD)
        }
    }

    /**
     * Observational hook: a chunk was observed loaded.
     *
     * Dimension is currently ignored by design (single-world assumption is enforced elsewhere).
     */
    fun observeChunkLoaded(pos: ChunkPos) {
        for (batch in batches.values) {
            if (!batch.chunks.contains(pos)) continue

            batch.observeLoaded(pos)
            emit(ChunkObserved(batchName = batch.name, trigger = RenewalTrigger.CHUNK_LOAD, chunk = pos, state = batch.state))

            // If a chunk loads while we are waiting for renewal, treat it as renewal evidence.
            // This is intentionally compatible with the application ChunkLoadDriver forcing loads as a completion signal.
            if (batch.state == RenewalBatchState.WAITING_FOR_RENEWAL) {
                batch.observeRenewed(pos)

                if (batch.allRenewedAtLeastOnce()) {
                    transition(batch, RenewalBatchState.RENEWAL_COMPLETE, RenewalTrigger.CHUNK_LOAD)
                    emit(BatchCompleted(batchName = batch.name, trigger = RenewalTrigger.CHUNK_LOAD, dimension = batch.dimension))

                    // Retire from active tracking (terminal state).
                    batches.remove(batch.name)
                }
            }
        }
    }

    /**
     * Shared unload-gate evaluation.
     *
     * Called after any observational update that could satisfy the unload gate:
     * - initial snapshot
     * - chunk unload events
     *
     * No "startup semantics" exist here. This is normal gate evaluation.
     */
    private fun maybePassUnloadGate(batch: RenewalBatch, trigger: RenewalTrigger) {
        if (batch.state != RenewalBatchState.WAITING_FOR_UNLOAD) return
        if (!batch.allUnloadedSimultaneously()) return

        transition(batch, RenewalBatchState.UNLOAD_COMPLETED, trigger)

        // Locked execution boundary: once unload gate is passed, the batch becomes ready for renewal.
        transition(batch, RenewalBatchState.WAITING_FOR_RENEWAL, trigger)

        // New execution phase: start collecting renewal evidence from scratch.
        batch.resetRenewalEvidence()
        emit(
            BatchWaitingForRenewal(
                batchName = batch.name,
                trigger = trigger,
                dimension = batch.dimension,
                chunks = batch.chunks.toList()
            )
        )
    }

    private fun transition(batch: RenewalBatch, to: RenewalBatchState, trigger: RenewalTrigger) {
        val from = batch.state
        if (from == to) return
        batch.state = to
emit(
    RenewalBatchLifecycleTransition(
        batch = RenewalBatchSnapshot(
            name = batch.name,
            dimension = batch.dimension,
            chunks = batch.chunks,
            state = batch.state,
        ),
        from = from,
        to = to,
        trigger = trigger,
    )
)
emit(BatchUpdated(batchName = batch.name, trigger = trigger, from = from, to = to, chunkCount = batch.chunks.size))
        emit(GatePassed(batchName = batch.name, trigger = trigger, from = from, to = to))
    }

    private fun emit(e: RenewalEvent) {
        for (l in listeners) l(e)
    }
}
