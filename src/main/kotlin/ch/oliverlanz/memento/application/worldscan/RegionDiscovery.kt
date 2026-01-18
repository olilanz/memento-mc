package ch.oliverlanz.memento.application.worldscan

import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.WorldSavePath
import net.minecraft.world.World
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Stage 2 of the /memento run pipeline: discover on-disk region files for each world.
 *
 * Design lock (persistence-based discovery):
 * - Regions are discovered by enumerating `region/r.<x>.<z>.mca` under the canonical Minecraft save layout.
 * - No API probing and no fallback paths.
 * - Missing/unreadable folders/files are logged and skipped.
 */
class RegionDiscovery {

    private val log = LoggerFactory.getLogger("memento")

    fun discover(server: MinecraftServer, worlds: List<RegistryKey<World>>): WorldDiscoveryPlan {
        val root = server.getSavePath(WorldSavePath.ROOT)

        val discovered = worlds.map { worldKey ->
            val regionDir = resolveRegionDirectory(root, worldKey)
            val regions = discoverRegions(regionDir)
            log.info("[RUN] discovered world={} regions={}", worldKey.value, regions.size)
            DiscoveredWorld(world = worldKey, regions = regions)
        }

        return WorldDiscoveryPlan(worlds = discovered)
    }

    private fun resolveRegionDirectory(root: Path, worldKey: RegistryKey<World>): Path {
        // Canonical Minecraft save layout (design-time decision).
        return when (worldKey) {
            World.OVERWORLD -> root.resolve("region")
            World.NETHER -> root.resolve("DIM-1").resolve("region")
            World.END -> root.resolve("DIM1").resolve("region")
            else -> {
                val id = worldKey.value
                root.resolve("dimensions").resolve(id.namespace).resolve(id.path).resolve("region")
            }
        }
    }

    private fun discoverRegions(regionDir: Path): List<RegionRef> {
        if (!Files.isDirectory(regionDir)) {
            log.info("[RUN] region folder missing; skipping dir={}", regionDir.toAbsolutePath())
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
