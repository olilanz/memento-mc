package ch.oliverlanz.memento.application.run

import ch.oliverlanz.memento.domain.memento.WorldMementoTopology
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

        val sb = StringBuilder()
        sb.append("world,regionx,regionz,chunkx,chunkz,timeinhabited_ticks,surface_y,biome_id,is_spawn,dominant_stone,has_lorestone_influence,has_witherstone_influence\n")

        topology.entries.forEach { entry ->
            val w = entry.key.world.value.toString()
            val biome = entry.signals.biomeId?.toString() ?: ""
            val surfaceY = entry.signals.surfaceY?.toString() ?: ""

            val dominant = entry.dominantStoneKind?.simpleName ?: ""

            sb.append(w)
                .append(',').append(entry.key.regionX)
                .append(',').append(entry.key.regionZ)
                .append(',').append(entry.key.chunkX)
                .append(',').append(entry.key.chunkZ)
                .append(',').append(entry.signals.inhabitedTimeTicks)
                .append(',').append(surfaceY)
                .append(',').append(biome)
                .append(',').append(if (entry.signals.isSpawnChunk) 1 else 0)
                .append(',').append(dominant)
                .append(',').append(if (entry.hasLorestoneInfluence) 1 else 0)
                .append(',').append(if (entry.hasWitherstoneInfluence) 1 else 0)
                .append('\n')
        }

        Files.write(path, sb.toString().toByteArray(StandardCharsets.UTF_8))
        log.info("[RUN] wrote csv path={} rows={}", path, topology.entries.size)
        return path
    }
}
