package ch.oliverlanz.memento.application.worldscan

import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos

data class ChunkRuntimeMetadata(
    val inhabitedTimeTicks: Long,
    val lastUpdateTicks: Long,
    val ok: Boolean,
    val hasValues: Boolean,
)

/**
 * Runtime-based metadata reader.
 *
 * Guarantees:
 * - Uses only public ServerWorld APIs
 * - Does NOT generate chunks
 * - No NBT, no region files, no compression
 */
class ChunkRuntimeMetadataReader(
    private val world: ServerWorld,
) {

    fun read(chunkPos: ChunkPos): ChunkRuntimeMetadata {
        val chunk = world.chunkManager.getWorldChunk(
            chunkPos.x,
            chunkPos.z,
            /* create = */ false
        ) ?: return ChunkRuntimeMetadata(
            inhabitedTimeTicks = 0L,
            lastUpdateTicks = 0L,
            ok = false,
            hasValues = false,
        )

        val inhabited = chunk.inhabitedTime

        // Minecraft 1.21.10 exposes no public last-update timestamp.
        // We explicitly set this to 0 for now.
        val lastUpdate = 0L

        return ChunkRuntimeMetadata(
            inhabitedTimeTicks = inhabited,
            lastUpdateTicks = lastUpdate,
            ok = true,
            hasValues = inhabited != 0L,
        )
    }
}
