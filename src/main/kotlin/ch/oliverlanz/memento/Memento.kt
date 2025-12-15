package ch.oliverlanz.memento

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk
import org.slf4j.LoggerFactory

object Memento : ModInitializer {

    private val logger = LoggerFactory.getLogger("memento")

    override fun onInitialize() {
        logger.info("Memento initializing")

        Commands.register()

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            logger.info("Loading memento anchors...")
            MementoPersistence.load(server)
            logger.info("Loaded ${MementoAnchors.list().size} anchors")
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            logger.info("Saving memento anchors...")
            MementoPersistence.save(server)
        }

        // Observability for the new "forget on load" approach.
        // These callbacks are not part of the forgetting mechanism; they are purely
        // to help us understand when chunks are loaded/unloaded and whether they
        // are marked for forgetting.
        ServerChunkEvents.CHUNK_LOAD.register(ServerChunkEvents.Load { world: ServerWorld, chunk: WorldChunk ->
            val pos = chunk.pos
            if (MementoAnchors.shouldForgetExactChunk(world.registryKey, pos)) {
                logger.warn(
                    "(memento) CHUNK_LOAD: dimension={}, chunk=({}, {}) is MARKED FOR FORGET; it may regenerate on next load.",
                    world.registryKey.value, pos.x, pos.z
                )
            }
        })

        ServerChunkEvents.CHUNK_UNLOAD.register(ServerChunkEvents.Unload { world: ServerWorld, chunk: WorldChunk ->
            val pos: ChunkPos = chunk.pos
            if (MementoAnchors.shouldForgetExactChunk(world.registryKey, pos)) {
                logger.warn(
                    "(memento) CHUNK_UNLOAD: dimension={}, chunk=({}, {}) is now dormant; its memory is destined for oblivion.",
                    world.registryKey.value, pos.x, pos.z
                )
            }
        })
    }
}
