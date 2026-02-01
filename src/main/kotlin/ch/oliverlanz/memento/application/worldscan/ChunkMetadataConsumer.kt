package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkSignals
import ch.oliverlanz.memento.domain.worldmap.WorldMementoMap
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.Heightmap
import net.minecraft.world.chunk.WorldChunk
import org.slf4j.LoggerFactory

/**
 * Consumes already-loaded chunks and extracts signals into the domain-owned [WorldMementoMap].
 *
 * 0.9.6 invariants:
 * - Never triggers chunk loads.
 * - Reads only from the provided [WorldChunk] and safe, non-loading world APIs.
 */
class ChunkMetadataConsumer(
    private val map: WorldMementoMap,
) {

    private val log = LoggerFactory.getLogger("memento")

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
        if (map.hasSignals(key)) {
            log.debug(
                "[SCAN] duplicate load dim={} chunk=({}, {}) (signals already present)",
                world.registryKey.value.toString(),
                pos.x,
                pos.z,
            )
            return
        }

        // Safe surface sample (center of chunk), computed from the chunk's own heightmap.
        // This must not call back into the chunk manager.
        val surfaceY: Int? = runCatching {
            val hm = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES)
            hm.get(8, 8)
        }.getOrNull()

        val beforeMissing = map.missingCount()
        val beforeScanned = map.scannedChunks()

        val firstAttach = map.upsertSignals(
            key = key,
            signals = ChunkSignals(
                inhabitedTimeTicks = chunk.inhabitedTime,
                lastUpdateTicks = null,
                surfaceY = surfaceY,
                biomeId = null,
                isSpawnChunk = false,
            )
        )

        val afterMissing = map.missingCount()
        val afterScanned = map.scannedChunks()

        // Telemetry: tie map mutation to convergence.
        // This is intentionally INFO for now.
        log.debug(
            "[SCAN] signals {} dim={} chunk=({}, {}) missing {}->{} scanned {}->{}",
            if (firstAttach) "ATTACHED" else "UPDATED",
            world.registryKey.value.toString(),
            pos.x,
            pos.z,
            beforeMissing,
            afterMissing,
            beforeScanned,
            afterScanned,
        )
    }
}
