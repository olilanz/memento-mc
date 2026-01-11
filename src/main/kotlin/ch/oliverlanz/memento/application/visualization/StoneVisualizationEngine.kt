package ch.oliverlanz.memento.application.visualization

import ch.oliverlanz.memento.application.time.GameClock
import ch.oliverlanz.memento.application.time.GameClockEvents
import ch.oliverlanz.memento.application.visualization.effects.LorestonePlacementEffect
import ch.oliverlanz.memento.application.visualization.effects.StoneInspectionEffect
import ch.oliverlanz.memento.application.visualization.effects.EffectBase
import ch.oliverlanz.memento.application.visualization.effects.WitherstonePlacementEffect
import ch.oliverlanz.memento.application.visualization.effects.WitherstoneWaitingEffect
import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.StoneLifecycleState
import ch.oliverlanz.memento.domain.events.StoneLifecycleTransition
import ch.oliverlanz.memento.domain.stones.LorestoneView
import ch.oliverlanz.memento.domain.renewal.RenewalBatchLifecycleTransition
import ch.oliverlanz.memento.domain.renewal.RenewalBatchState
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.stones.StoneTopology
import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import org.slf4j.LoggerFactory

/**
 * Application-level visualization host.
 *
 * Owns the lifecycle of visual projections (effects) and ticks them based on [GameClock].
 *
 * Effects are projections only; they must remain safe to expire and re-create.
 */
class StoneVisualizationEngine(
    private val server: MinecraftServer
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private data class EffectKey(
        val stoneName: String,
        val type: VisualizationType,
    )

    private val effectsByKey = mutableMapOf<EffectKey, EffectBase>()

    init {
        StoneDomainEvents.subscribeToLifecycleTransitions(::onLifecycleTransition)
        GameClockEvents.subscribe(::onTick)
    }

    /**
     * Receives authoritative renewal domain events.
     *
     * Waiting visualization is fully event-driven. It exists iff the batch is in
     * [RenewalBatchState.WAITING_FOR_UNLOAD].
     */
    fun onRenewalEvent(event: RenewalEvent) {
        when (event) {
            is RenewalBatchLifecycleTransition -> onRenewalBatchLifecycleTransition(event)
            else -> Unit
        }
    }

    private fun onRenewalBatchLifecycleTransition(event: RenewalBatchLifecycleTransition) {
        // We key waiting effects by stone name (batch identity).
        val stoneName = event.batch.name
        val stone = StoneTopology.get(stoneName) as? WitherstoneView

        // If the stone has already been removed, we treat this as a cleanup boundary.
        if (stone == null) {
            removeAllEffectsForStone(stoneName)
            return
        }

        when {
            event.to == RenewalBatchState.WAITING_FOR_UNLOAD -> {
                visualizeStone(stone, VisualizationType.WITHERSTONE_WAITING)
            }

            event.from == RenewalBatchState.WAITING_FOR_UNLOAD && event.to != RenewalBatchState.WAITING_FOR_UNLOAD -> {
                effectsByKey.remove(EffectKey(stone.name, VisualizationType.WITHERSTONE_WAITING))
            }
        }
    }

    // (Renewal handling above.)

    fun visualizeStone(stone: StoneView, type: VisualizationType) {
        val effect = createEffect(stone, type) ?: return
        registerOrReplace(stone, type, effect)
    }

    private fun createEffect(stone: StoneView, type: VisualizationType): EffectBase? =
        when (type) {
            VisualizationType.PLACEMENT -> when (stone) {
                is WitherstoneView -> WitherstonePlacementEffect(stone)
                is LorestoneView -> LorestonePlacementEffect(stone)
                else -> null
            }

            VisualizationType.INSPECTION ->
                StoneInspectionEffect(stone)

            VisualizationType.WITHERSTONE_WAITING ->
                WitherstoneWaitingEffect(stone)
        }

    private fun onLifecycleTransition(event: StoneLifecycleTransition) {
        when (event.to) {
            StoneLifecycleState.PLACED -> {
                log.debug(
                    "[viz] lifecycle PLACED received for stone='{}' dim='{}' pos={} - registering placement effect",
                    event.stone.name,
                    event.stone.dimension.value,
                    event.stone.position
                )
                visualizeStone(event.stone, VisualizationType.PLACEMENT)
            }

            StoneLifecycleState.CONSUMED -> {
                // Terminal teardown: ensure we do not keep emitting particles after a stone ceased to exist.
                val removed = removeAllEffectsForStone(event.stone.name)
                if (removed > 0) {
                    log.debug(
                        "[viz] lifecycle CONSUMED received for stone='{}' - removed {} active effect(s)",
                        event.stone.name,
                        removed,
                    )
                }
            }

            else -> Unit
        }
    }

    private fun onTick(clock: GameClock) {
        val it = effectsByKey.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val effect = entry.value
            val world: ServerWorld =
                server.getWorld(effect.stone.dimension) ?: continue

            if (!effect.tick(world, clock)) {
                it.remove()
            }
        }
    }

    private fun registerOrReplace(stone: StoneView, type: VisualizationType, effect: EffectBase) {
        effectsByKey[EffectKey(stone.name, type)] = effect
    }

    private fun removeAllEffectsForStone(stoneName: String): Int {
        var removed = 0
        val it = effectsByKey.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key.stoneName == stoneName) {
                it.remove()
                removed++
            }
        }
        return removed
    }
}
