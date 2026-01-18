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

    fun read(chunkPos: ChunkPos): ChunkRuntimeMetadata? {
        val chunk = world.chunkManager.getWorldChunk(
            chunkPos.x,
            chunkPos.z,
            /* create = */ false
        ) ?: return null   // chunk does not exist → ignore entirely

        // Minecraft 1.21.10 exposes inhabited time but no public last-update timestamp.
        // Missing data is represented explicitly as null.
        return ChunkRuntimeMetadata(
            inhabitedTimeTicks = chunk.inhabitedTime,
            lastUpdateTicks = null,
        )
    }
}
