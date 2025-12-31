package ch.oliverlanz.memento.application.stone

import ch.oliverlanz.memento.domain.stones.StoneRegisterHooks
import ch.oliverlanz.memento.infrastructure.MementoState
import ch.oliverlanz.memento.infrastructure.MementoState.State
import net.minecraft.server.MinecraftServer
import net.minecraft.world.World
import org.slf4j.LoggerFactory

class OverworldDayObserver {

    private val log = LoggerFactory.getLogger("memento")

    private var server: MinecraftServer? = null

    fun attach(server: MinecraftServer) {
        this.server = server

        MementoState.load(server)

        val currentDay = currentOverworldDayIndex(server) ?: return
        val lastDay = MementoState.get().lastProcessedOverworldDay

        if (lastDay < 0L) {
            MementoState.set(State(lastProcessedOverworldDay = currentDay))
            MementoState.save(server)
            log.info("[STONE] overworld day observer initialized day={}", currentDay)
        }
    }

    fun detach() {
        server = null
    }

    fun tick() {
        val server = this.server ?: return

        val currentDay = currentOverworldDayIndex(server) ?: return
        val lastDay = MementoState.get().lastProcessedOverworldDay

        if (lastDay < 0L) {
            MementoState.set(State(lastProcessedOverworldDay = currentDay))
            MementoState.save(server)
            return
        }

        if (currentDay <= lastDay) return

        val deltaDays = (currentDay - lastDay).toInt()
        log.info("[STONE] overworld day advanced from={} to={} days={}", lastDay, currentDay, deltaDays)

        StoneRegisterHooks.onNightlyCheckpoint(deltaDays)

        MementoState.set(State(lastProcessedOverworldDay = currentDay))
        MementoState.save(server)
    }

    private fun currentOverworldDayIndex(server: MinecraftServer): Long? {
        val overworld = server.getWorld(World.OVERWORLD) ?: return null
        return overworld.timeOfDay / 24000L
    }
}
