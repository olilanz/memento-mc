package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.StoneLifecycleState
import ch.oliverlanz.memento.domain.events.StoneLifecycleTransition
import ch.oliverlanz.memento.domain.renewal.StoneRenewalDerivation
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog

/**
 * Application-layer wiring for renewal intent derivation.
 *
 * NOTE: Startup must not be a separate semantic code path. This bridge only reacts
 * to normal domain events (Witherstone transitions) and asks renewal-side derivation to ensure
 * derived renewal intent exists for matured stones.
 *
 * Authority:
 * - StoneMapService provides dominance projection consumed by renewal derivation.
 * - RenewalTracker owns RenewalBatch lifecycle and state transitions.
 */
object WitherstoneRenewalBridge {
    private var attached: Boolean = false

    fun attach() {
        if (attached) return
        StoneDomainEvents.subscribeToLifecycleTransitions(::onLifecycleTransition)
        attached = true
    }

    fun detach() {
        if (!attached) return
        StoneDomainEvents.unsubscribeFromLifecycleTransitions(::onLifecycleTransition)
        attached = false
    }

    private fun onLifecycleTransition(e: StoneLifecycleTransition) {
        val witherstone = e.stone as? WitherstoneView ?: return
        if (e.to != StoneLifecycleState.MATURED) return

        val applied = StoneRenewalDerivation.ensureForMaturedWitherstone(
            stoneName = witherstone.name,
            reason = "transition_${e.trigger}",
        )

        if (applied) {
            MementoLog.debug(MementoConcept.RENEWAL,
                "renewal intent ensured stone='{}' trigger={}",
                witherstone.name,
                e.trigger,
            )
        }
    }
}
