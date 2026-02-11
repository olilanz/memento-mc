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

    /**
     * Best-effort outcome: the driver observed (or attempted) a load but gave up before a stable
     * FULLY_LOADED chunk became accessible on the tick thread.
     *
     * This is a chunk *loading lifecycle* outcome only. Consumers may choose to:
     * - mark best-effort progress (e.g. scan coverage),
     * - attempt a later enrichment pass, or
     * - ignore the outcome.
     */
    fun onChunkLoadExpired(world: ServerWorld, pos: ChunkPos) {
        /* default no-op */
    }

    /** Optional hook (used by renewal tracker domain hooks). */
    fun onChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
        /* default no-op */
    }
}
