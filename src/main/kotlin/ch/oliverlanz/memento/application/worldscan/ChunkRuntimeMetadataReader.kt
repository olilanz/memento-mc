package ch.oliverlanz.memento.application.worldscan

import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.ChunkStatus

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
     * Reads runtime metadata for a chunk.
     *
     * Important:
     * - This method will request the chunk to be loaded from disk (best-effort).
     * - It MUST only be used for chunks that are known to exist (from region headers),
     *   otherwise `create=true` could generate new chunks.
     */
    fun loadAndReadExisting(chunkPos: ChunkPos): ChunkRuntimeMetadata? {
        // Best-effort load. With create=true this may generate if the chunk does not exist.
        // The scanner must guarantee "exists" via region headers before calling this.
        world.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, /* create = */ true)

        val chunk = world.chunkManager.getWorldChunk(
            chunkPos.x,
            chunkPos.z,
            /* create = */ false
        ) ?: return null

        // Minecraft 1.21.10 exposes inhabited time but no public last-update timestamp.
        // Missing data is represented explicitly as null.
        return ChunkRuntimeMetadata(
            inhabitedTimeTicks = chunk.inhabitedTime,
            lastUpdateTicks = null,
        )
    }
}
