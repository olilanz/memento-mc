package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.WitherstoneStateTransition
import ch.oliverlanz.memento.domain.events.WitherstoneTransitionTrigger
import ch.oliverlanz.memento.domain.renewal.RenewalBatch
import ch.oliverlanz.memento.domain.renewal.RenewalBatchState
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.RenewalTrigger
import ch.oliverlanz.memento.domain.stones.StoneRegister
import ch.oliverlanz.memento.domain.stones.Witherstone
import ch.oliverlanz.memento.domain.stones.WitherstoneState
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.ChunkPos

/**
 * Application-layer bridge:
 * - listens to StoneRegister lifecycle transitions
 * - creates / updates renewal batches in RenewalTracker when a Witherstone becomes MATURED
 *
 * This is intentionally NOT in the domain.
 */
object WitherstoneRenewalBridge {

    private var attached: Boolean = false

    private fun onTransition(e: WitherstoneStateTransition) {
        if (e.to != WitherstoneState.MATURED) return

        val trigger = mapTrigger(e.trigger)

        val center = ChunkPos(e.position)
        val radius = (StoneRegister.get(e.stoneName) as? Witherstone)?.radius ?: 0
        val chunks = squareChunkArea(center, radius = radius)

        RenewalTracker.upsertBatch(
            batch = RenewalBatch(
                name = e.stoneName,
                dimension = e.dimension,
                chunks = chunks,
                state = RenewalBatchState.WAITING_FOR_UNLOAD
            ),
            trigger = trigger
        )
    }

    fun attach(server: MinecraftServer) {
        if (attached) return
        attached = true

        StoneDomainEvents.subscribeToWitherstoneTransitions(::onTransition)

        // Reconcile already-matured stones on startup (they may not emit a transition on load).
        // This keeps renewal accounting authoritative without changing domain rules.
        for (s in StoneRegister.list()) {
            val w = s as? Witherstone ?: continue
            if (w.state != WitherstoneState.MATURED) continue

            RenewalTracker.upsertBatch(
                batch = RenewalBatch(
                    name = w.name,
                    dimension = w.dimension,
                    chunks = squareChunkArea(ChunkPos(w.position), radius = w.radius),
                    state = RenewalBatchState.WAITING_FOR_UNLOAD
                ),
                trigger = RenewalTrigger.MANUAL
            )
        }
    }

    fun detach() {
        if (!attached) return
        attached = false
        StoneDomainEvents.unsubscribeFromWitherstoneTransitions(::onTransition)
    }

    private fun mapTrigger(trigger: WitherstoneTransitionTrigger): RenewalTrigger =
        when (trigger) {
            WitherstoneTransitionTrigger.NIGHTLY_TICK -> RenewalTrigger.NIGHTLY_TICK
            WitherstoneTransitionTrigger.SERVER_START -> RenewalTrigger.MANUAL
            WitherstoneTransitionTrigger.OP_COMMAND -> RenewalTrigger.MANUAL
            WitherstoneTransitionTrigger.RENEWAL_COMPLETED -> RenewalTrigger.MANUAL
        }

    private fun squareChunkArea(center: ChunkPos, radius: Int): Set<ChunkPos> {
        val r = radius.coerceAtLeast(0)
        val out = linkedSetOf<ChunkPos>()
        for (dx in -r..r) {
            for (dz in -r..r) {
                out.add(ChunkPos(center.x + dx, center.z + dz))
            }
        }
        return out
    }
}