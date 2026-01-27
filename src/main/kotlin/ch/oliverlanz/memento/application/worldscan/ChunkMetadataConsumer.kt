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

        // Safe surface sample (center of chunk). This must not cause chunk loads.
        val centerX = pos.startX + 8
        val centerZ = pos.startZ + 8
        val surfaceY: Int? = try {
            world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ)
        } catch (_: Throwable) {
            null
        }

        val key = ChunkKey(
            world = world.registryKey,
            regionX = Math.floorDiv(pos.x, 32),
            regionZ = Math.floorDiv(pos.z, 32),
            chunkX = pos.x,
            chunkZ = pos.z,
        )

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
