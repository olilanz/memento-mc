package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.renewal.BatchCompleted
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.stones.StoneAuthority
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog

/**
 * Application-layer wiring that closes the Witherstone lifecycle.
 *
 * When a renewal batch completes, the originating Witherstone must be consumed.
 *
 * Naming invariant (locked):
 * - batchName == witherstoneName (StoneAuthority is the authority for batch naming)
 */
object WitherstoneConsumptionBridge {

    fun onRenewalEvent(e: RenewalEvent) {
        if (e !is BatchCompleted) return

        // Consumption is idempotent in StoneAuthority.
        StoneAuthority.consume(e.batchName)

        MementoLog.debug(
            MementoConcept.RENEWAL,
            "renewal completion applied: consumed stone='{}' trigger={} dim={}",
            e.batchName,
            e.trigger,
            e.dimension.value.toString()
        )
    }
}
