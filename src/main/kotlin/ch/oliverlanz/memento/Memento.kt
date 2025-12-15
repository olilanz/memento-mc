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

        // Observability for the "forget on load" approach.
        ServerChunkEvents.CHUNK_LOAD.register(ServerChunkEvents.Load { world: ServerWorld, chunk: WorldChunk ->
            val pos = chunk.pos
            if (MementoAnchors.shouldForgetExactChunk(world.registryKey, pos)) {
                logger.info(
                    "(memento) Chunk loaded: dimension={}, chunk=({}, {}) is marked for renewal (not remembered).",
                    world.registryKey.value, pos.x, pos.z
                )
            }
        })

        ServerChunkEvents.CHUNK_UNLOAD.register(ServerChunkEvents.Unload { world: ServerWorld, chunk: WorldChunk ->
            val pos: ChunkPos = chunk.pos
            if (MementoAnchors.shouldForgetExactChunk(world.registryKey, pos)) {
                logger.info(
                    "(memento) Chunk unloaded: dimension={}, chunk=({}, {}) is marked for renewal (not remembered).",
                    world.registryKey.value, pos.x, pos.z
                )
            }
        })
    }
}
