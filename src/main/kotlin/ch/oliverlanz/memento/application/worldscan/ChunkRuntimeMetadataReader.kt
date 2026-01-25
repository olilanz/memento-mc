package ch.oliverlanz.memento.application.worldscan

import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos

/**
 * Runtime chunk metadata.
 *
 * NOTE:
 * - Only created for chunks that actually exist.
 * - Absence of a value is represented as null.
 */
data class ChunkRuntimeMetadata(
    val inhabitedTimeTicks: Long?,
    val lastUpdateTicks: Long?,
)

/**
 * Runtime-based metadata reader.
 *
 * Semantics:
 * - Uses ONLY public ServerWorld APIs
 * - Does NOT generate chunks
 * - Returns null if the chunk does not exist
 * - Throws on unexpected errors
 */
class ChunkRuntimeMetadataReader(
    private val world: ServerWorld,
) {

    /**
     * Reads runtime metadata ONLY if the chunk is already loaded.
     *
     * This is the polite-path used by the 0.9.6 scanner/provider model.
     * It never triggers IO and never generates chunks.
     */
    fun readIfLoaded(chunkPos: ChunkPos): ChunkRuntimeMetadata? {
        val chunk = world.chunkManager.getWorldChunk(
            chunkPos.x,
            chunkPos.z,
            /* create = */ false
        ) ?: return null

        return ChunkRuntimeMetadata(
            inhabitedTimeTicks = chunk.inhabitedTime,
            lastUpdateTicks = null,
        )
    }

    /**
     * Reads runtime metadata for an existing chunk, if it is currently loaded.
     *
     * 0.9.6 politeness invariant:
     * - Never synchronously load or generate chunks.
     * - If the engine hasn't loaded the chunk yet, return null.
     */
    fun loadAndReadExisting(chunkPos: ChunkPos): ChunkRuntimeMetadata? {
        // 0.9.6 politeness invariant: never synchronously load/generate chunks.
        // Existing chunks must be loaded by the engine (player/other mod/driver async request).
        val chunk = world.chunkManager.getWorldChunk(chunkPos.x, chunkPos.z, /* create = */ false) ?: return null

        // Minecraft 1.21.10 exposes inhabited time but no public last-update timestamp.
        // Missing data is represented explicitly as null.
        return ChunkRuntimeMetadata(
            inhabitedTimeTicks = chunk.inhabitedTime,
            lastUpdateTicks = null,
        )
    }
}
