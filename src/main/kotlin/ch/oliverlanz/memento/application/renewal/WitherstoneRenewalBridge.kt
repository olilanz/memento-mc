package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.WitherstoneStateTransition
import ch.oliverlanz.memento.domain.stones.StoneTopology
import ch.oliverlanz.memento.domain.stones.WitherstoneState
import org.slf4j.LoggerFactory

/**
 * Application-layer wiring for renewal intent derivation.
 *
 * NOTE: Startup must not be a separate semantic code path. This bridge only reacts
 * to normal domain events (Witherstone transitions) and asks the topology to ensure
 * derived renewal intent exists for matured stones.
 *
 * Authority:
 * - StoneTopology derives eligible chunk sets.
 * - RenewalTracker owns RenewalBatch lifecycle and state transitions.
 */
object WitherstoneRenewalBridge {

    private val log = LoggerFactory.getLogger("Memento")
    private var attached: Boolean = false

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

    private fun onWitherstoneTransition(e: WitherstoneStateTransition) {
        if (e.to != WitherstoneState.MATURED) return

        val applied = StoneTopology.reconcileRenewalIntentForMaturedWitherstone(
            stoneName = e.stone.name,
            reason = "transition_${e.trigger}",
        )

        if (applied) {
            log.info(
                "[BRIDGE] matured reconciliation applied witherstone='{}' trigger={}",
                e.stone.name,
                e.trigger,
            )
        }
    }
}
