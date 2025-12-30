package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.WitherstoneStateTransition
import ch.oliverlanz.memento.domain.renewal.RenewalBatch
import ch.oliverlanz.memento.domain.renewal.RenewalBatchState
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.RenewalTrigger
import ch.oliverlanz.memento.domain.stones.StoneRegister
import ch.oliverlanz.memento.domain.stones.Witherstone
import net.minecraft.util.math.ChunkPos
import org.slf4j.LoggerFactory

/**
 * Application-layer bridge:
 * - listens to Witherstone maturity transitions
 * - creates/updates a RenewalBatch in RenewalTracker
 *
 * Keeps RenewalTracker passive and StoneRegister authoritative.
 */
object WitherstoneRenewalBridge {

    private val log = LoggerFactory.getLogger("memento")

    private var attached = false

    fun attach() {
        if (attached) return

        // Subscribe BEFORE any startup evaluation that might emit transitions.
        StoneDomainEvents.subscribeToWitherstoneTransitions(::onWitherstoneTransition)
        attached = true
    }

    fun detach() {
        if (!attached) return
        StoneDomainEvents.unsubscribeFromWitherstoneTransitions(::onWitherstoneTransition)
        attached = false
    }

    fun reconcileAfterStoneRegisterAttached(reason: String) {
        for (s in StoneRegister.list()) {
            val w = s as? Witherstone ?: continue
            if (!w.isMatured()) continue
            upsertBatchFor(w, reason = reason)
        }
    }

    private fun onWitherstoneTransition(e: WitherstoneStateTransition) {
        // We care only about MATURED transitions (attempts are observable elsewhere).
        if (e.to.name != "MATURED") return

        val stone = StoneRegister.get(e.stoneName) as? Witherstone ?: return
        if (!stone.isMatured()) return

        upsertBatchFor(stone, reason = "transition_${e.trigger.name}")
    }

    private fun upsertBatchFor(stone: Witherstone, reason: String) {
        val chunks = computeChunks(stone)
        val batch = RenewalBatch(
            name = stone.name,
            dimension = stone.dimension,
            chunks = chunks,
            state = RenewalBatchState.WAITING_FOR_UNLOAD
        )

        // RenewalTrigger enum currently doesn't include stone triggers; use MANUAL and log reason explicitly.
        log.info("[BRIDGE] upsert batch name='{}' reason={} chunks={}", stone.name, reason, chunks.size)
        RenewalTracker.upsertBatch(batch, trigger = RenewalTrigger.MANUAL)
    }

    private fun computeChunks(stone: Witherstone): Set<ChunkPos> {
        val center = ChunkPos(stone.position.x shr 4, stone.position.z shr 4)
        val r = stone.radius
        val out = LinkedHashSet<ChunkPos>()
        for (dx in -r..r) {
            for (dz in -r..r) {
                out.add(ChunkPos(center.x + dx, center.z + dz))
            }
        }
        return out
    }

    private fun Witherstone.isMatured(): Boolean =
        this.state.name == "MATURED"
}
