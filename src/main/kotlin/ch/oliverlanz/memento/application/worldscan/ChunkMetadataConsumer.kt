package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.domain.memento.ChunkKey
import ch.oliverlanz.memento.domain.memento.ChunkSignals
import ch.oliverlanz.memento.domain.memento.WorldMementoSubstrate
import ch.oliverlanz.memento.infrastructure.chunk.ChunkRef
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.Heightmap
import net.minecraft.world.chunk.WorldChunk

/**
 * Consumes already-loaded chunks and extracts signals into the domain-owned [WorldMementoSubstrate].
 *
 * 0.9.6 invariants:
 * - Never triggers chunk loads.
 * - Reads only from the provided [WorldChunk] and safe, non-loading world APIs.
 */
class ChunkMetadataConsumer(
    private val substrate: WorldMementoSubstrate,
    private val plan: WorldScanPlan,
) {

    fun onChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        val pos: ChunkPos = chunk.pos

        val key = ChunkKey(
            world = world.registryKey,
            regionX = Math.floorDiv(pos.x, 32),
            regionZ = Math.floorDiv(pos.z, 32),
            chunkX = pos.x,
            chunkZ = pos.z,
        )

        // If we already have signals for this chunk, we can treat this as a duplicate load.
        // We still mark the plan as completed so scanning can make forward progress.
        if (substrate.contains(key)) {
            plan.markCompleted(ChunkRef(world.registryKey, pos))
            return
        }

        // Safe surface sample (center of chunk), computed from the chunk's own heightmap.
        // This must not call back into the chunk manager.
        val surfaceY: Int? = runCatching {
            val hm = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES)
            hm.get(8, 8)
        }.getOrNull()

        substrate.upsert(
            key = key,
            signals = ChunkSignals(
                inhabitedTimeTicks = chunk.inhabitedTime,
                lastUpdateTicks = null,
                surfaceY = surfaceY,
                biomeId = null,
                isSpawnChunk = false,
            )
        )

        // Mark scan progress. Plan tracks ChunkRef, not ChunkPos.
        plan.markCompleted(ChunkRef(world.registryKey, pos))
    }
}
