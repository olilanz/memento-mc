package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.renewal.BatchCompleted
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.stones.StoneTopology
import org.slf4j.LoggerFactory

/**
 * Application-layer wiring that closes the Witherstone lifecycle.
 *
 * When a renewal batch completes, the originating Witherstone must be consumed.
 *
 * Naming invariant (locked):
 * - batchName == witherstoneName (StoneTopology is the authority for batch naming)
 */
object WitherstoneConsumptionBridge {

    private val log = LoggerFactory.getLogger("Memento")

    fun onRenewalEvent(e: RenewalEvent) {
        if (e !is BatchCompleted) return

        // Consumption is idempotent in StoneTopology.
        StoneTopology.consume(e.batchName)

        log.info(
            "[BRIDGE] renewal completed -> consuming witherstone='{}' trigger={} dimension={}",
            e.batchName,
            e.trigger,
            e.dimension.value.toString()
        )
    }
}
