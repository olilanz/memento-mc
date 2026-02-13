package ch.oliverlanz.memento.infrastructure.worldscan

import ch.oliverlanz.memento.domain.worldmap.ChunkMetadataFact
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance
import ch.oliverlanz.memento.domain.worldmap.ChunkSignals
import ch.oliverlanz.memento.domain.worldmap.WorldMementoMap
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.Heightmap
import net.minecraft.world.chunk.WorldChunk

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

    fun extractFact(
        world: ServerWorld,
        chunk: WorldChunk,
        source: ChunkScanProvenance,
        scanTick: Long,
    ): ChunkMetadataFact {
        val pos: ChunkPos = chunk.pos

        val key = ChunkKey(
            world = world.registryKey,
            regionX = Math.floorDiv(pos.x, 32),
            regionZ = Math.floorDiv(pos.z, 32),
            chunkX = pos.x,
            chunkZ = pos.z,
        )

        // Duplicate publications are acceptable and handled idempotently at ingestion time.
        // Keep read-side check available for optional diagnostics and future guards.
        map.hasSignals(key)

        // Safe surface sample (center of chunk), computed from the chunk's own heightmap.
        // This must not call back into the chunk manager.
        val surfaceY: Int? = runCatching {
            val hm = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES)
            hm.get(8, 8)
        }.getOrNull()

        return ChunkMetadataFact(
            key = key,
            signals = ChunkSignals(
                inhabitedTimeTicks = chunk.inhabitedTime,
                lastUpdateTicks = null,
                surfaceY = surfaceY,
                biomeId = null,
                isSpawnChunk = false,
            ),
            source = source,
            scanTick = scanTick,
        )
    }
}
