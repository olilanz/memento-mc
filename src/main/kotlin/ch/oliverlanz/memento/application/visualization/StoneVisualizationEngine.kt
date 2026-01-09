package ch.oliverlanz.memento.application.visualization

import ch.oliverlanz.memento.domain.events.StoneCreated
import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.stones.StoneView
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld

/**
 * Application-level visualization host.
 *
 * This engine is intentionally not a domain component.
 * It listens to domain / operator events and maintains an in-memory set of
 * visual projections (effects) that are ticked over time.
 *
 * Effects are ephemeral: they are not persisted and do not survive restarts.
 * They are expected to be rebuilt through regular startup reconciliation and events.
 */
object StoneVisualizationEngine {

    private var server: MinecraftServer? = null

    /**
     * Active effects keyed by stone identity.
     *
     * For now we keep at most one effect per stone name, replacing on new events.
     * (Composition can be introduced inside a single effect later if needed.)
     */
    private val effectsByStoneName: MutableMap<String, VisualAreaEffect> = LinkedHashMap()

    fun attach(server: MinecraftServer) {
        this.server = server

        StoneDomainEvents.subscribeToStoneCreated(::onStoneCreated)

        // NOTE: We intentionally do not react to witherstone transitions yet in this slice.
        // Wiring for additional event types can be added in the next iteration.
    }

    fun detach() {
        StoneDomainEvents.unsubscribeFromStoneCreated(::onStoneCreated)

        effectsByStoneName.clear()
        this.server = null
    }

    /**
     * Tick all active effects once.
     *
     * Called from the server tick loop (END_SERVER_TICK).
     */
    fun tick() {
        val server = this.server ?: return
        if (effectsByStoneName.isEmpty()) return

        val it = effectsByStoneName.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val effect = entry.value

            val world: ServerWorld = server.getWorld(effect.stone.dimension) ?: run {
                // If the world is not available, terminate the effect to avoid leaks.
                it.remove()
                continue
            }

            val keepAlive = effect.tick(world, world.time)
            if (!keepAlive) {
                it.remove()
            }
        }
    }

    private fun onStoneCreated(event: StoneCreated) {
        // Replace any existing effect for this stone.
        registerOrReplace(event.stone, StoneCreatedAreaEffect(event.stone))
    }

    private fun registerOrReplace(stone: StoneView, effect: VisualAreaEffect) {
        effectsByStoneName[stone.name] = effect
    }
}
