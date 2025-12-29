package ch.oliverlanz.memento.infrastructure.chunk

import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import org.slf4j.LoggerFactory

/**
 * Central predicate used by VersionedChunkStorageMixin.
 *
 * In the current branch, legacy forgetting logic has been unwired. The new renewal tracker
 * will take over this predicate in a dedicated slice.
 */
object ChunkForgetPredicate {

    private val log = LoggerFactory.getLogger("memento")

    @Volatile
    private var warned = false

    /**
     * Predicate used by the mixin. Keep this signature stable.
     */
    @JvmStatic
    fun shouldForget(server: MinecraftServer, dimension: RegistryKey<World>, pos: ChunkPos): Boolean {
        if (!warned) {
            warned = true
            log.warn("ChunkForgetPredicate currently returns false for all chunks (new RenewalTracker not wired yet).")
        }
        return false
    }

    /**
     * Backwards-compatible alias.
     */
    @JvmStatic
    fun shouldForgetNow(server: MinecraftServer, dimension: RegistryKey<World>, pos: ChunkPos): Boolean =
        shouldForget(server, dimension, pos)
}
