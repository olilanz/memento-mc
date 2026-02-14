package ch.oliverlanz.memento.infrastructure.worldscan

import ch.oliverlanz.memento.domain.worldmap.WorldMementoTopology
import ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry
import ch.oliverlanz.memento.domain.stones.StoneMapService
import ch.oliverlanz.memento.domain.stones.Stone
import ch.oliverlanz.memento.MementoConstants
import net.minecraft.server.MinecraftServer
import net.minecraft.util.WorldSavePath
import net.minecraft.util.math.ChunkPos
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Application-level projection of a topology into a CSV file for analysis. */
object MementoCsvWriter {


    fun writeSnapshot(server: MinecraftServer, snapshot: List<ChunkScanSnapshotEntry>) {
        val path = server.getSavePath(WorldSavePath.ROOT)
            .resolve(MementoConstants.MEMENTO_SCAN_CSV_FILE)

        Files.createDirectories(path.parent)

        val sb = StringBuilder()
        sb.append("dimension,chunk_x,chunk_z,scan_tick,inhabited_ticks,dominant_stone,surface_y,biome_id,is_spawn_chunk,provenance,unresolved_reason\n")

        // Dominant influence map per world (already lore-resolved by StoneAuthority)
        val dominantByWorld = linkedMapOf<net.minecraft.registry.RegistryKey<net.minecraft.world.World>, Map<ChunkPos, kotlin.reflect.KClass<out ch.oliverlanz.memento.domain.stones.Stone>>>()

        snapshot.forEach { entry ->
            val key = entry.key
            val signals = entry.signals

            val dominantByChunk = dominantByWorld.getOrPut(key.world) {
                StoneMapService.dominantByChunk(key.world)
            }
            val dominant = dominantByChunk[ChunkPos(key.chunkX, key.chunkZ)]?.simpleName ?: ""

            val dim = key.world.value.toString()
            val inhabited = signals?.inhabitedTimeTicks?.toString() ?: ""
            val surfaceY = signals?.surfaceY?.toString() ?: ""
            val biome = signals?.biomeId ?: ""
            val isSpawn = signals?.isSpawnChunk?.toString() ?: ""
            val provenance = entry.provenance.name
            val unresolvedReason = entry.unresolvedReason?.name ?: ""

            sb.append(dim)
                .append(',').append(key.chunkX)
                .append(',').append(key.chunkZ)
                .append(',').append(entry.scanTick)
                .append(',').append(inhabited)
                .append(',').append(dominant)
                .append(',').append(surfaceY)
                .append(',').append(biome)
                .append(',').append(isSpawn)
                .append(',').append(provenance)
                .append(',').append(unresolvedReason)
                .append('\n')
        }

        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8)
        MementoLog.info(MementoConcept.SCANNER, "Wrote scan CSV: {}", path)
    }

    fun write(server: MinecraftServer, topology: WorldMementoTopology): Path {
        val root = server.getSavePath(WorldSavePath.ROOT)
        val path = root.resolve(MementoConstants.MEMENTO_SCAN_CSV_FILE)

        // LOCKED SCHEMA (scanner responsibility only; no derived fields, no optional fields):
        // dimension,chunk_x,chunk_z,inhabited_ticks,dominant_stone,surface_y,biome_id,is_spawn_chunk
        val sb = StringBuilder()
        sb.append("dimension,chunk_x,chunk_z,inhabited_ticks,dominant_stone,surface_y,biome_id,is_spawn_chunk\n")

        topology.entries.forEach { entry ->
            val dim = entry.key.world.value.toString()
            val signals = entry.signals
            val biome = signals?.biomeId ?: ""
            val surfaceY = signals?.surfaceY?.toString() ?: ""
            val inhabited = signals?.inhabitedTimeTicks?.toString() ?: ""
            val dominant = entry.dominantStoneKind?.simpleName ?: ""

            sb.append(dim)
                .append(',').append(entry.key.chunkX)
                .append(',').append(entry.key.chunkZ)
                .append(',').append(inhabited)
                .append(',').append(dominant)
                .append(',').append(surfaceY)
                .append(',').append(biome)
                .append(',').append(if (signals?.isSpawnChunk == true) 1 else 0)
                .append('\n')
        }

        Files.write(path, sb.toString().toByteArray(StandardCharsets.UTF_8))
        MementoLog.info(MementoConcept.SCANNER, "snapshot written csv={} rows={}", path, topology.entries.size)
        return path
    }
}
