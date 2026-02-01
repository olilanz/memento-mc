package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.domain.worldmap.WorldMementoTopology
import ch.oliverlanz.memento.infrastructure.MementoConstants
import net.minecraft.server.MinecraftServer
import net.minecraft.util.WorldSavePath
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Application-level projection of a topology into a CSV file for analysis. */
object MementoCsvWriter {

    private val log = LoggerFactory.getLogger("memento")

    fun write(server: MinecraftServer, topology: WorldMementoTopology): Path {
        val root = server.getSavePath(WorldSavePath.ROOT)
        val path = root.resolve(MementoConstants.MEMENTO_RUN_CSV_FILE)

        // LOCKED SCHEMA (scanner responsibility only; no derived fields, no optional fields):
        // dimension,chunk_x,chunk_z,inhabited_ticks,dominant_stone,surface_y,biome_id,is_spawn_chunk
        val sb = StringBuilder()
        sb.append("dimension,chunk_x,chunk_z,inhabited_ticks,dominant_stone,surface_y,biome_id,is_spawn_chunk\n")

        topology.entries.forEach { entry ->
            val dim = entry.key.world.value.toString()
            val biome = entry.signals.biomeId ?: ""
            val surfaceY = entry.signals.surfaceY?.toString() ?: ""
            val inhabited = entry.signals.inhabitedTimeTicks?.toString() ?: ""
            val dominant = entry.dominantStoneKind?.simpleName ?: ""

            sb.append(dim)
                .append(',').append(entry.key.chunkX)
                .append(',').append(entry.key.chunkZ)
                .append(',').append(inhabited)
                .append(',').append(dominant)
                .append(',').append(surfaceY)
                .append(',').append(biome)
                .append(',').append(if (entry.signals.isSpawnChunk) 1 else 0)
                .append('\n')
        }

        Files.write(path, sb.toString().toByteArray(StandardCharsets.UTF_8))
        log.debug("[RUN] wrote csv path={} rows={}", path, topology.entries.size)
        return path
    }
}
