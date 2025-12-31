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
                log.info("[TRACK] batch='{}' created state={} chunks={} trigger={}",
                    e.batchName, e.state.name, e.chunkCount, e.trigger.name)

            is BatchUpdated ->
                log.info("[TRACK] batch='{}' state {} -> {} chunks={} trigger={}",
                    e.batchName, e.from.name, e.to.name, e.chunkCount, e.trigger.name)

            is InitialSnapshotApplied ->
                log.info("[TRACK] batch='{}' initial snapshot loaded={} unloaded={} trigger={}",
                    e.batchName, e.loaded, e.unloaded, e.trigger.name)

            is BatchRemoved ->
                log.info("[TRACK] batch='{}' removed trigger={}",
                    e.batchName, e.trigger.name)

            is ChunkObserved ->
                log.info("[TRACK] batch='{}' chunk=({}, {}) observed state={} trigger={}",
                    e.batchName, e.chunk.x, e.chunk.z, e.state.name, e.trigger.name)

            is GateAttempted ->
                log.info("[TRACK] batch='{}' gate attempted from={} attempted={} trigger={}",
                    e.batchName, e.from.name, e.attempted.name, e.trigger.name)

            is GatePassed ->
                log.info("[TRACK] batch='{}' gate passed {} -> {} trigger={}",
                    e.batchName, e.from.name, e.to.name, e.trigger.name)

            is BatchCompleted ->
                log.info("[TRACK] batch='{}' completed dim={} trigger={}",
                    e.batchName, e.dimension.value, e.trigger.name)

            is BatchWaitingForRenewal ->
                log.info("[TRACK] batch='{}' waiting for renewal chunks={}",
                    e.batchName, e.chunks.size)
        }
    }
}