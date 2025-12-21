package ch.oliverlanz.memento.infrastructure.chunk

import ch.oliverlanz.memento.application.land.ChunkGroupForgetting
import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object ChunkForgetPredicate {

    private val logger = LoggerFactory.getLogger("memento")

    /**
     * We intentionally keep logging sparse to avoid spamming the console.
     * Keyed by "dimensionId:chunkLong".
     */
    private val loggedForgetDecisions = ConcurrentHashMap.newKeySet<String>()

    fun shouldForget(
        dimension: RegistryKey<World>,
        chunkPos: ChunkPos
    ): Boolean {
        // The actual regeneration decision is controlled by the group executor.
        // Only chunks that are actively part of a renewal execution are forgotten.
        // (This prevents partial regeneration while a group is still waiting to become fully unloaded.)
        val forget = ChunkGroupForgetting.shouldForgetNow(dimension, chunkPos)

        if (forget) {
            // Feed the observation back into the group executor.
            ChunkGroupForgetting.onChunkRenewalObserved(dimension, chunkPos)
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
}
