package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.WitherstoneStateTransition
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.RenewalTrigger
import ch.oliverlanz.memento.domain.stones.StoneTopology
import ch.oliverlanz.memento.domain.stones.Witherstone
import net.minecraft.util.math.ChunkPos
import org.slf4j.LoggerFactory

/**
 * Application-layer bridge:
 * - listens to Witherstone maturity transitions
 * - derives / refreshes renewal batch definitions in RenewalTracker
 *
 * Topology semantics (including Lorestone protection) live in StoneTopology.
 * RenewalTracker owns the RenewalBatch lifecycle and state transitions.
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

    fun reconcileAfterStoneTopologyAttached(reason: String) {
        val all = StoneTopology.list()
        log.info("[BRIDGE] reconcile start reason={} stonesInTopology={}", reason, all.size)
        for (s in all) {
            val w = s as? Witherstone ?: continue
            if (!w.isMatured()) continue
            upsertBatchDefinitionFor(w, reason = reason)
        }
        log.info("[BRIDGE] reconcile done reason={}", reason)
    }

    private fun onWitherstoneTransition(e: WitherstoneStateTransition) {
        // We care only about MATURED transitions.
        if (e.to.name != "MATURED") return

        val stone = StoneTopology.get(e.stoneName) as? Witherstone ?: return
        if (!stone.isMatured()) return

        upsertBatchDefinitionFor(stone, reason = "transition_${e.trigger.name}")
    }

    private fun upsertBatchDefinitionFor(stone: Witherstone, reason: String) {
        val chunks: Set<ChunkPos> = StoneTopology.eligibleChunksFor(stone)
        log.debug("[BRIDGE] upsert batch definition name='{}' reason={} chunks={}", stone.name, reason, chunks.size)
        RenewalTracker.upsertBatchDefinition(
            name = stone.name,
            dimension = stone.dimension,
            chunks = chunks,
            trigger = RenewalTrigger.SYSTEM
        )
    }

    private fun Witherstone.isMatured(): Boolean =
        this.state.name == "MATURED"
}
