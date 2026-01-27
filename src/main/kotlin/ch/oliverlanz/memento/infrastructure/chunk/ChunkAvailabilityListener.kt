package ch.oliverlanz.memento.infrastructure.chunk

import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk

/**
 * Consumer of observed chunk availability.
 *
 * This is fed by [ChunkLoadDriver], which is the sole owner of engine event subscriptions.
 * Application and domain code should not subscribe to Fabric chunk events directly.
 */
interface ChunkAvailabilityListener {
    fun onChunkLoaded(world: ServerWorld, chunk: WorldChunk)

    /** Optional hook (used by renewal tracker domain hooks). */
    fun onChunkUnloaded(world: ServerWorld, pos: ChunkPos) { /* default no-op */ }
}
