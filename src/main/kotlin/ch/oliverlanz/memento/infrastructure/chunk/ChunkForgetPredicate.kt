package ch.oliverlanz.memento.infrastructure.chunk

import ch.oliverlanz.memento.application.stone.MementoStoneLifecycle
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object ChunkForgetPredicate {

    private val logger = LoggerFactory.getLogger("memento")
    private val loggedForgetDecisions = ConcurrentHashMap.newKeySet<String>()

    fun shouldForget(dimension: RegistryKey<World>, chunkPos: ChunkPos): Boolean {
        val forget = MementoStoneLifecycle.shouldForgetNow(dimension, chunkPos)

        if (forget) {
            val key = "${dimension.value}:" + chunkPos.toLong()
            if (loggedForgetDecisions.add(key)) {
                logger.info(
                    "(memento) Loading forgotten chunk: dimension={}, chunk=({}, {}). The chunk will be regenerated.",
                    dimension.value,
                    chunkPos.x,
                    chunkPos.z
                )
            }
        }

        return forget
    }

    fun onChunkRenewalObserved(server: MinecraftServer, dimension: RegistryKey<World>, pos: ChunkPos) {
        MementoStoneLifecycle.onChunkRenewalObserved(server, dimension, pos)
    }
}
