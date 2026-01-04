package ch.oliverlanz.memento.domain.renewal

import org.slf4j.LoggerFactory

/**
 * Logging adapter for RenewalTracker domain events.
 *
 * Domain code does not log. This hook turns typed events into concise,
 * test-friendly log lines.
 */
object RenewalTrackerLogging {

    private val log = LoggerFactory.getLogger("memento")

    private fun triggerLabel(trigger: RenewalTrigger): String = when (trigger) {
        RenewalTrigger.INITIAL_SNAPSHOT -> "STARTUP"
        else -> trigger.name
    }

    private var attached = false

    fun attachOnce() {
        if (attached) return
        RenewalTracker.subscribe(::logEvent)
        attached = true
    }

    fun detach() {
        if (!attached) return
        RenewalTracker.unsubscribe(::logEvent)
        attached = false
    }

    private fun logEvent(e: RenewalEvent) {
        when (e) {
            is BatchCreated ->
                log.debug("[LOADER] batch='{}' created state={} chunks={} trigger={}",
                    e.batchName, e.state.name, e.chunkCount, e.trigger.name)

            is BatchUpdated ->
                log.debug("[LOADER] batch='{}' state {} -> {} chunks={} trigger={}",
                    e.batchName, e.from.name, e.to.name, e.chunkCount, e.trigger.name)

            is InitialSnapshotApplied ->
                log.info("[LOADER] batch='{}' status loadedChunks={} unloadedChunks={} trigger={}",
                    e.batchName, e.loaded, e.unloaded, triggerLabel(e.trigger))

            is BatchRemoved ->
                log.info("[LOADER] batch='{}' {} trigger={}",
                    e.batchName,
                    if (e.trigger == RenewalTrigger.MANUAL) "definition removed" else "definition replaced",
                    triggerLabel(e.trigger))

            is ChunkObserved ->
                log.debug("[LOADER] batch='{}' chunk=({}, {}) observed state={} trigger={}",
                    e.batchName, e.chunk.x, e.chunk.z, e.state.name, e.trigger.name)

            is GateAttempted ->
                log.debug("[LOADER] batch='{}' gate attempted from={} attempted={} trigger={}",
                    e.batchName, e.from.name, e.attempted.name, e.trigger.name)

            is GatePassed ->
                log.info("[LOADER] batch='{}' gate passed {} -> {} trigger={}",
                    e.batchName, e.from.name, e.to.name, e.trigger.name)

            is BatchCompleted ->
                log.info("[LOADER] batch='{}' completed dim={} trigger={}",
                    e.batchName, e.dimension.value, e.trigger.name)

            is BatchWaitingForRenewal ->
                log.debug("[LOADER] batch='{}' waiting for renewal chunks={}",
                    e.batchName, e.chunks.size)
        }
    }
}