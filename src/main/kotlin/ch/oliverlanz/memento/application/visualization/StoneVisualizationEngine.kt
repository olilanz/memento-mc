package ch.oliverlanz.memento.application.visualization

import ch.oliverlanz.memento.domain.events.StoneCreated
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
        StoneDomainEvents.subscribeToStoneCreated(::onStoneCreated)
        GameClockEvents.subscribe(::onTick)
    }

    private fun onStoneCreated(event: StoneCreated) {
        val effect = when (event.stone) {
            is WitherstoneView -> WitherstoneCreatedEffect(event.stone)
            is LorestoneView -> LorestoneCreatedEffect(event.stone)
            else -> return
        }

        log.debug(
            "[viz] StoneCreated received for stone='{}' dim='{}' pos={} - registering creation effect",
            event.stone.name,
            event.stone.dimension.value,
            event.stone.position
        )

        registerOrReplace(event.stone, effect)
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