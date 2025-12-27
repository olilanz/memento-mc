package ch.oliverlanz.memento

import ch.oliverlanz.memento.infrastructure.MementoState
import ch.oliverlanz.memento.infrastructure.MementoPersistence
import ch.oliverlanz.memento.infrastructure.MementoConstants
import ch.oliverlanz.memento.infrastructure.MementoDebug
import ch.oliverlanz.memento.application.stone.WitherstoneLifecycle
import ch.oliverlanz.memento.domain.stones.StoneRegister
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.advanceTime
import ch.oliverlanz.memento.domain.renewal.onChunkLoaded
import ch.oliverlanz.memento.domain.renewal.onChunkUnloaded
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos

/**
 * Mod entry point and lifecycle wiring.
 */
object Memento : ModInitializer {

    const val NEW_MODE = true

    override fun onInitialize() {
        MementoDebug.info(null, "Memento initializing")

        if (NEW_MODE) {
            initializeShadowComponents()
        }

        // Commands
        Commands.register()

        // Load persisted anchors + state, and rebuild derived group marks.
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            MementoDebug.info(server, "Loading anchors and state")

            // Attach server reference for mixin-driven renewal observations.
            WitherstoneLifecycle.attachServer(server)

            MementoPersistence.load(server)
            MementoState.load(server)

            // Rebuild marked groups from already-matured anchors (daysRemaining == 0).
            WitherstoneLifecycle.rebuildMarkedGroups(server)

            // Initialize day tracking if this is a fresh world/state.
            val overworld = server.getWorld(ServerWorld.OVERWORLD)
            if (overworld != null) {
                val currentDay = overworld.timeOfDay / MementoConstants.OVERWORLD_DAY_TICKS
                if (MementoState.get().lastProcessedOverworldDay < 0 && currentDay >= 0) {
                    MementoState.set(MementoState.State(lastProcessedOverworldDay = currentDay))
                    MementoState.save(server)
                }
            }
        }

        // Persist state on shutdown.
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            WitherstoneLifecycle.detachServer(server)
            MementoPersistence.save(server)
            MementoState.save(server)
        }

        // Day-change trigger: mature anchors once per Overworld day, then sweep.
        ServerTickEvents.END_SERVER_TICK.register { server ->
            onServerTick(server)
        }

        // Unload trigger: every unload is a chance for marked land to become free for forgetting.
        ServerChunkEvents.CHUNK_UNLOAD.register { world, chunk ->
            WitherstoneLifecycle.onChunkUnloaded(world.server, world, chunk.pos)
        }
    }

    private fun onServerTick(server: MinecraftServer) {
        val overworld = server.getWorld(ServerWorld.OVERWORLD) ?: return

        val dayIndex = overworld.timeOfDay / MementoConstants.OVERWORLD_DAY_TICKS
        val last = MementoState.get().lastProcessedOverworldDay
        if (dayIndex != last) {
            // Record first, so we never double-run on the same index.
            MementoState.set(MementoState.State(lastProcessedOverworldDay = dayIndex))
            MementoState.save(server)

            WitherstoneLifecycle.ageAnchorsOnce(server)
            WitherstoneLifecycle.sweep(server)
        }

        // Budgeted regeneration work (chunk queue processing).
        WitherstoneLifecycle.tick(server)
    }

    private fun initializeShadowComponents() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            MementoPersistence.load(server)
            advanceTime(server.ticks.toLong())
        }

        ServerChunkEvents.CHUNK_UNLOAD.register { world, chunk ->
            onChunkUnloaded(ChunkPos(chunk.pos.x, chunk.pos.z))
        }

        ServerChunkEvents.CHUNK_LOAD.register { world, chunk ->
            onChunkLoaded(ChunkPos(chunk.pos.x, chunk.pos.z))
        }
    }
}
