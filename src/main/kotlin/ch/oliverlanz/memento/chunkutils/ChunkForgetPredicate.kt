package ch.oliverlanz.memento.chunkutils

import ch.oliverlanz.memento.MementoAnchors
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
        // For the current slice: only the *exact* anchor chunk is forgettable.
        // Radius/days come later.
        val forget = MementoAnchors.shouldForgetExactChunk(dimension, chunkPos)

        if (forget) {
            val key = "${dimension.value}:" + chunkPos.toLong()
            if (loggedForgetDecisions.add(key)) {
                logger.info(
                    "(memento) Forget predicate matched: dimension={}, chunk=({}, {}) will not be remembered.",
                    dimension.value,
                    chunkPos.x,
                    chunkPos.z
                )
            }
        }

        return forget
    }
}
