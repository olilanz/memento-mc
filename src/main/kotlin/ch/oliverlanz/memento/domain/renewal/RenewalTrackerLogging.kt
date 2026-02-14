package ch.oliverlanz.memento.domain.renewal

import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog

/**
 * Logging adapter for RenewalTracker domain events.
 *
 * Domain code does not log. This hook turns typed events into concise,
 * test-friendly log lines.
 */
object RenewalTrackerLogging {

    private data class RenewalInfoMarker(
        val state: RenewalBatchState,
        val chunkCount: Int,
    )

    private fun triggerLabel(trigger: RenewalTrigger): String = when (trigger) {
        RenewalTrigger.INITIAL_SNAPSHOT -> "STARTUP"
        else -> trigger.name
    }

    private var attached = false
    private val lastLifecycleInfoByBatch = linkedMapOf<String, RenewalInfoMarker>()

    fun attachOnce() {
        if (attached) return
        RenewalTracker.subscribe(::logEvent)
        attached = true
    }

    fun detach() {
        if (!attached) return
        RenewalTracker.unsubscribe(::logEvent)
        attached = false
        lastLifecycleInfoByBatch.clear()
    }

    private fun logEvent(e: RenewalEvent) {
        when (e) {
            is BatchCreated ->
                MementoLog.debug(MementoConcept.RENEWAL, "batch='{}' created state={} chunks={} trigger={}",
                    e.batchName, e.state.name, e.chunkCount, e.trigger.name)

            is BatchUpdated ->
                MementoLog.debug(MementoConcept.RENEWAL, "batch='{}' state {} -> {} chunks={} trigger={}",
                    e.batchName, e.from.name, e.to.name, e.chunkCount, e.trigger.name)

            is InitialSnapshotApplied ->
                MementoLog.debug(MementoConcept.RENEWAL, "batch='{}' initial snapshot applied loadedChunks={} unloadedChunks={} trigger={}",
                    e.batchName, e.loaded, e.unloaded, triggerLabel(e.trigger))

            is BatchRemoved ->
                MementoLog.debug(MementoConcept.RENEWAL, "batch='{}' {} trigger={}",
                    e.batchName,
                    if (e.trigger == RenewalTrigger.MANUAL) "definition removed" else "definition replaced",
                    triggerLabel(e.trigger)).also {
                    lastLifecycleInfoByBatch.remove(e.batchName)
                }

            is ChunkObserved ->
                MementoLog.debug(MementoConcept.RENEWAL, "batch='{}' chunk=({}, {}) observed state={} trigger={}",
                    e.batchName, e.chunk.x, e.chunk.z, e.state.name, e.trigger.name)

            is GateAttempted ->
                MementoLog.debug(MementoConcept.RENEWAL, "batch='{}' gate attempted from={} attempted={} trigger={}",
                    e.batchName, e.from.name, e.attempted.name, e.trigger.name)

            is GatePassed ->
                MementoLog.debug(MementoConcept.RENEWAL, "batch='{}' gate passed {} -> {} trigger={}",
                    e.batchName, e.from.name, e.to.name, e.trigger.name)

            is BatchCompleted ->
                MementoLog.info(MementoConcept.RENEWAL, "renewal completed stone='{}' dim={} trigger={}",
                    e.batchName, e.dimension.value, e.trigger.name).also {
                    lastLifecycleInfoByBatch.remove(e.batchName)
                }

            is BatchWaitingForRenewal ->
                MementoLog.debug(MementoConcept.RENEWAL, "batch='{}' waiting for renewal chunks={}",
                    e.batchName, e.chunks.size)

            is RenewalBatchLifecycleTransition -> {
                MementoLog.debug(MementoConcept.RENEWAL,
                    "batch='{}' lifecycle {} -> {} chunks={}",
                    e.batch.name, e.from, e.to, e.batch.chunks.size)

                // INFO-level domain narrative: explain state in operator-readable form.
                val marker = RenewalInfoMarker(
                    state = e.to,
                    chunkCount = e.batch.chunks.size,
                )
                if (lastLifecycleInfoByBatch[e.batch.name] == marker) return

                when (e.to) {
                    RenewalBatchState.WAITING_FOR_UNLOAD -> {
                        lastLifecycleInfoByBatch[e.batch.name] = marker
                        MementoLog.info(MementoConcept.RENEWAL,
                            "renewal pending unload stone='{}' dim='{}' chunks={} trigger={}",
                            e.batch.name,
                            e.batch.dimension.value.toString(),
                            e.batch.chunks.size,
                            triggerLabel(e.trigger),
                        )
                    }

                    RenewalBatchState.WAITING_FOR_RENEWAL -> {
                        lastLifecycleInfoByBatch[e.batch.name] = marker
                        MementoLog.info(MementoConcept.RENEWAL,
                            "renewal armed stone='{}' dim='{}' chunks={} trigger={} (awaiting chunk-load evidence)",
                            e.batch.name,
                            e.batch.dimension.value.toString(),
                            e.batch.chunks.size,
                            triggerLabel(e.trigger),
                        )
                    }

                    else -> Unit
                }
            }
        }
    }
}
