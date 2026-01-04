package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.WitherstoneStateTransition
import ch.oliverlanz.memento.domain.stones.StoneTopology
import ch.oliverlanz.memento.domain.stones.WitherstoneState
import org.slf4j.LoggerFactory

/**
 * Application-layer wiring:
 * - listens to Witherstone lifecycle transitions
 * - triggers topology reconciliation when stones become MATURED
 *
 * Authority:
 * - StoneTopology is the sole authority for deriving renewal intent (chunk sets).
 * - RenewalTracker owns RenewalBatch lifecycle and state transitions.
 */
object WitherstoneRenewalBridge {

    private val log = LoggerFactory.getLogger("memento")

    private var attached = false

    fun attach() {
        if (attached) return
        StoneDomainEvents.subscribeToWitherstoneTransitions(::onWitherstoneTransition)
        attached = true
    }

    fun detach() {
        if (!attached) return
        StoneDomainEvents.unsubscribeFromWitherstoneTransitions(::onWitherstoneTransition)
        attached = false
    }

    /**
     * Called after StoneTopology has been attached (and persistence loaded).
     *
     * The bridge does not derive intent itself.
     * It simply asks the topology to reconcile all currently-matured witherstones.
     */
    fun reconcileAfterStoneTopologyAttached(reason: String) {
        val count = StoneTopology.reconcileAllMaturedWitherstones(reason)
        log.info("[BRIDGE] reconcile done reason={} maturedWitherstones={}", reason, count)
    }

    private fun onWitherstoneTransition(e: WitherstoneStateTransition) {
        // We care only about MATURED transitions.
        if (e.to != WitherstoneState.MATURED) return

        val applied = StoneTopology.reconcileRenewalIntentForMaturedWitherstone(
            stoneName = e.stoneName,
            reason = "transition_${e.trigger}",
        )

        if (applied) {
            log.info(
                "[BRIDGE] matured reconciliation applied witherstone='{}' trigger={}",
                e.stoneName,
                e.trigger,
            )
        }
    }
}
