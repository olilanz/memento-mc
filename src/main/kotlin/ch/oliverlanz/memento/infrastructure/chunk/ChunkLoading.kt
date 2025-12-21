package ch.oliverlanz.memento.infrastructure.chunk

import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.ChunkStatus
import net.minecraft.world.chunk.WorldChunk
import org.slf4j.LoggerFactory
import java.lang.reflect.Method

/**
 * Centralized chunk loading helpers.
 *
 * These functions are intentionally "best effort":
 * - They must never force-load a chunk
 * - They rely on reflective access to avoid hard coupling to internals
 * - They may return false negatives in edge cases, which is acceptable
 *
 * Centralizing this avoids subtle inconsistencies and overload conflicts.
 */
object ChunkLoading {

    private val logger = LoggerFactory.getLogger("memento")

    fun isChunkLoadedBestEffort(world: ServerWorld, pos: ChunkPos): Boolean {
        return getLoadedChunk(world, pos) != null
    }

    private fun getLoadedChunk(world: ServerWorld, pos: ChunkPos): WorldChunk? {
        val cm = world.chunkManager
        return try {
            val method = findGetChunkMethod(cm)
            method.invoke(cm, pos.x, pos.z, ChunkStatus.FULL, false) as? WorldChunk
        } catch (t: Throwable) {
            logger.debug(
                "(memento) chunk loaded check failed for {} {}: {}",
                pos.x, pos.z, t.toString()
            )
            null
        }
    }

    private fun findGetChunkMethod(cm: Any): Method =
        cm.javaClass.methods.first {
            it.name == "getChunk" &&
                    it.parameterTypes.size == 4 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[3] == Boolean::class.javaPrimitiveType
        }
}
