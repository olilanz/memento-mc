package ch.oliverlanz.memento

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk
import net.minecraft.world.World
import ch.oliverlanz.memento.chunkutils.ChunkGroupForgetting
import org.slf4j.LoggerFactory

object Memento : ModInitializer {

    private val logger = LoggerFactory.getLogger("memento")

    override fun onInitialize() {
        logger.info("Memento initializing")

        Commands.register()

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            logger.info("Loading memento anchors...")
            MementoPersistence.load(server)
            MementoState.load(server)

            // Initialize the "once per world day" marker. If we don't have persisted state yet,
            // we start from the current Overworld day to avoid an immediate decrement on startup.
            val overworld = server.getWorld(World.OVERWORLD)
            val currentDay = overworld?.time?.div(24000L) ?: -1L
            if (MementoState.get().lastProcessedOverworldDay < 0 && currentDay >= 0) {
                MementoState.set(MementoState.State(lastProcessedOverworldDay = currentDay))
                MementoState.save(server)
            }

            // Rebuild the eligible group queue from persisted anchors.
            ChunkGroupForgetting.rebuildEligibleGroups(server)
            // Some groups may already be fully unloaded. Sweep once to execute immediately.
            ChunkGroupForgetting.sweep(server)
            logger.info("Loaded ${MementoAnchors.list().size} anchors")
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            logger.info("Saving memento anchors...")
            MementoPersistence.save(server)
            MementoState.save(server)
        }

        // Anchor aging trigger ("once per world day").
        //
        // Design rationale:
        // - Players can sleep and ops can use /time set, which may jump time.
        // - We therefore treat "day" as the world day index (time / 24000).
        // - If the day index advances, we age anchors once.
        ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick { server ->
            val overworld = server.getWorld(World.OVERWORLD) ?: return@EndTick
            val dayIndex = overworld.time / 24000L
            val last = MementoState.get().lastProcessedOverworldDay

            if (last >= 0 && dayIndex > last) {
                ChunkGroupForgetting.ageAnchorsOnce(server)
                MementoState.set(MementoState.State(lastProcessedOverworldDay = dayIndex))
                MementoState.save(server)

                // After aging, some groups may become due. Attempt execution for those already unloaded.
                ChunkGroupForgetting.sweep(server)
            }
        })

        // Observability.
        ServerChunkEvents.CHUNK_LOAD.register(ServerChunkEvents.Load { world: ServerWorld, chunk: WorldChunk ->
            val pos = chunk.pos
            if (ChunkGroupForgetting.isQueued(world.registryKey, pos)) {
                logger.info(
                    "(memento) Chunk loaded: dimension={}, chunk=({}, {}) is queued for renewal.",
                    world.registryKey.value, pos.x, pos.z
                )
            }
        })

        ServerChunkEvents.CHUNK_UNLOAD.register(ServerChunkEvents.Unload { world: ServerWorld, chunk: WorldChunk ->
            val pos: ChunkPos = chunk.pos
            if (ChunkGroupForgetting.isQueued(world.registryKey, pos)) {
                logger.info(
                    "(memento) Chunk unloaded: dimension={}, chunk=({}, {}) is queued for renewal.",
                    world.registryKey.value, pos.x, pos.z
                )
            }

            // Unload-trigger: every unload is a chance for a queued group to become eligible.
            ChunkGroupForgetting.onChunkUnloaded(world.server, world, pos)
        })
    }
}
