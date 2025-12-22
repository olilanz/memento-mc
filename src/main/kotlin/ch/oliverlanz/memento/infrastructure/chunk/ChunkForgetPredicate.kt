package ch.oliverlanz.memento.infrastructure.chunk

import ch.oliverlanz.memento.application.stone.MementoStoneLifecycle
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

/**
 * Central predicate used by VersionedChunkStorageMixin.
 */
object ChunkForgetPredicate {

    /**
     * Predicate used by the mixin. Keep this signature stable.
     *
     * Some mixin injection points don't have direct access to MinecraftServer. In that case we
     * fall back to the currently attached server (if any). If none is attached we do nothing.
     */
    @JvmStatic
    fun shouldForget(dimension: RegistryKey<World>, pos: ChunkPos): Boolean {
        val server = MementoStoneLifecycle.currentServerOrNull() ?: return false
        return shouldForget(server, dimension, pos)
    }

    @JvmStatic
    fun shouldForget(server: MinecraftServer, dimension: RegistryKey<World>, pos: ChunkPos): Boolean {
        // If the chunk is queued (in-flight) for renewal, we both:
        //  - return true to force regeneration (read empty NBT)
        //  - emit an observation that this renewal actually happened
        if (!MementoStoneLifecycle.isChunkRenewalQueued(dimension, pos)) {
            return false
        }

        MementoStoneLifecycle.onChunkRenewalObserved(server, dimension, pos)
        return true
    }

    /**
     * Backwards-compatible alias.
     */
    @JvmStatic
    fun shouldForgetNow(server: MinecraftServer, dimension: RegistryKey<World>, pos: ChunkPos): Boolean =
        shouldForget(server, dimension, pos)
}
