package ch.oliverlanz.memento.infrastructure.worldscan

import ch.oliverlanz.memento.infrastructure.worldstorage.WorldStorageService
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.WorldSavePath
import net.minecraft.world.World
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.nio.file.Files
import java.nio.file.Path

/**
 * Stage 2 of the /memento scan pipeline: discover on-disk region files for each world.
 *
 * Design lock (persistence-based discovery):
 * - Regions are discovered by enumerating `region/r.<x>.<z>.mca` under the canonical Minecraft save layout.
 * - No API probing and no fallback paths.
 * - Missing/unreadable folders/files are logged and skipped.
 */
class RegionDiscovery {

    fun discover(server: MinecraftServer, worlds: List<RegistryKey<World>>): WorldDiscoveryPlan {
        val root = server.getSavePath(WorldSavePath.ROOT)

        val discovered = worlds.map { worldKey ->
            val regionDir =
                WorldStorageService.resolveRegionDataDirectory(
                    root = root,
                    worldKey = worldKey,
                    kind = WorldStorageService.RegionDataKind.REGION,
                )
            val regions = discoverRegions(regionDir)
            MementoLog.debug(MementoConcept.SCANNER, "discovered world={} regions={}", worldKey.value, regions.size)
            DiscoveredWorld(world = worldKey, regions = regions)
        }

        return WorldDiscoveryPlan(worlds = discovered)
    }

    private fun discoverRegions(regionDir: Path): List<RegionRef> {
        if (!Files.isDirectory(regionDir)) {
            MementoLog.debug(MementoConcept.SCANNER, "region folder missing; skipping dir={}", regionDir.toAbsolutePath())
            return emptyList()
        }

        val re = Regex("""^r\.(-?\d+)\.(-?\d+)\.mca$""")

        val regions = mutableListOf<RegionRef>()
        Files.newDirectoryStream(regionDir).use { stream ->
            for (p in stream) {
                if (!Files.isRegularFile(p)) continue
                val name = p.fileName.toString()
                val m = re.matchEntire(name) ?: continue
                val x = m.groupValues[1].toIntOrNull() ?: continue
                val z = m.groupValues[2].toIntOrNull() ?: continue
                regions.add(RegionRef(x = x, z = z, file = p.toAbsolutePath()))
            }
        }

        // Deterministic ordering.
        return regions.sortedWith(compareBy<RegionRef> { it.x }.thenBy { it.z })
    }
}
