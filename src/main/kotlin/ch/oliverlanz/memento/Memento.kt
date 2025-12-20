package ch.oliverlanz.memento

import ch.oliverlanz.memento.chunkutils.ChunkGroupForgetting
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld

/**
 * Mod entry point and lifecycle wiring.
 *
 * Key mental model:
 * - Witherstone anchors mature over world-days (Overworld day index).
 * - When an anchor matures, its derived chunk group is *marked for forgetting*.
 * - A marked group may only renew when *all its chunks are unloaded*.
 * - Unload does not regenerate; it only creates the opportunity.
 * - Regeneration happens on the subsequent load, via the storage mixin.
 */
object Memento : ModInitializer {

    override fun onInitialize() {
        MementoDebug.info(null, "Memento initializing")

        // Commands
        Commands.register()

        // Load persisted anchors + state, and rebuild derived group marks.
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            MementoDebug.info(server, "Loading anchors and state")

            MementoPersistence.load(server)
            MementoState.load(server)

            // Rebuild marked groups from already-matured anchors (daysRemaining == 0).
            ChunkGroupForgetting.rebuildMarkedGroups(server)

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
            MementoPersistence.save(server)
            MementoState.save(server)
        }

        // Day-change trigger: mature anchors once per Overworld day, then sweep.
        ServerTickEvents.END_SERVER_TICK.register { server ->
            onServerTick(server)
        }

        // Unload trigger: every unload is a chance for marked land to become free for forgetting.
        ServerChunkEvents.CHUNK_UNLOAD.register { world, chunk ->
            ChunkGroupForgetting.onChunkUnloaded(world.server, world, chunk.pos)
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

            ChunkGroupForgetting.ageAnchorsOnce(server)
            ChunkGroupForgetting.sweep(server)
        }
    }
}
