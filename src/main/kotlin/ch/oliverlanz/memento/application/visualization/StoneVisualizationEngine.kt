package ch.oliverlanz.memento.application.visualization
import ch.oliverlanz.memento.application.visualization.effects.VisualAreaEffect
import ch.oliverlanz.memento.application.visualization.effects.WitherstoneCreatedEffect
import ch.oliverlanz.memento.application.visualization.effects.LorestoneCreatedEffect

import ch.oliverlanz.memento.domain.events.StoneLifecycleState
import ch.oliverlanz.memento.domain.events.StoneLifecycleTransition
import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import ch.oliverlanz.memento.domain.stones.LorestoneView
import ch.oliverlanz.memento.application.time.GameClock
import ch.oliverlanz.memento.application.time.GameClockEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import org.slf4j.LoggerFactory

/**
 * Application-level visualization host.
 */
class StoneVisualizationEngine(
    private val server: MinecraftServer
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val effectsByStoneName = mutableMapOf<String, VisualAreaEffect>()

    init {
        StoneDomainEvents.subscribeToLifecycleTransitions(::onLifecycleTransition)
        GameClockEvents.subscribe(::onTick)
    }

    private fun onLifecycleTransition(event: StoneLifecycleTransition) {
        when (event.to) {
            StoneLifecycleState.PLACED -> {
                val effect = when (event.stone) {
                    is WitherstoneView -> WitherstoneCreatedEffect(event.stone)
                    is LorestoneView -> LorestoneCreatedEffect(event.stone)
                    else -> return
                }

                log.debug(
                    "[viz] lifecycle PLACED received for stone='{}' dim='{}' pos={} - registering placement effect",
                    event.stone.name,
                    event.stone.dimension.value,
                    event.stone.position
                )

                registerOrReplace(event.stone, effect)
            }

            StoneLifecycleState.CONSUMED -> {
                // Terminal teardown: ensure we do not keep emitting particles after a stone ceased to exist.
                val removed = effectsByStoneName.remove(event.stone.name)
                if (removed != null) {
                    log.debug(
                        "[viz] lifecycle CONSUMED received for stone='{}' - removing active effect",
                        event.stone.name,
                    )
                }
            }

            else -> Unit
        }
    }

    private fun onTick(clock: GameClock) {
        val it = effectsByStoneName.entries.iterator()
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

    private fun registerOrReplace(stone: StoneView, effect: VisualAreaEffect) {
        effectsByStoneName[stone.name] = effect
    }
}