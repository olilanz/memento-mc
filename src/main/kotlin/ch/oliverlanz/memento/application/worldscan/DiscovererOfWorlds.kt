package ch.oliverlanz.memento.application.worldscan

import net.minecraft.server.MinecraftServer
import net.minecraft.util.WorldSavePath
import net.minecraft.world.World
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Application/infrastructure component responsible for discovering worlds and regions.
 *
 * Slice 3: world discovery uses the server's loaded worlds; region discovery enumerates on-disk region files.
 *
 * Design lock: persistence-based discovery.
 * - Worlds are taken from the server.
 * - Regions are discovered by enumerating `region/r.<x>.<z>.mca` under the dimension save folder.
 * - No runtime probing/fallbacks; we accept the canonical Minecraft save layout.
 */
class DiscovererOfWorlds {

    private val log = LoggerFactory.getLogger("memento")

    fun discover(server: MinecraftServer): WorldDiscoveryPlan {
        val root = server.getSavePath(WorldSavePath.ROOT)

        val worlds = server.worlds
            .map { it.registryKey }
            .sortedBy { it.value.toString() }

        val discovered = worlds.map { worldKey ->
            val regionDir = resolveRegionDirectory(root, worldKey)
            val regions = discoverRegions(regionDir)
            log.info("[RUN] discovered world={} regions={}", worldKey.value, regions.size)
            DiscoveredWorld(world = worldKey, regions = regions)
        }

        return WorldDiscoveryPlan(worlds = discovered)
    }

    private fun resolveRegionDirectory(root: Path, worldKey: net.minecraft.registry.RegistryKey<World>): Path {
        // Canonical Minecraft save layout.
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
                val x = m.groupValues[1].toIntOrNull()
                val z = m.groupValues[2].toIntOrNull()
                if (x == null || z == null) continue
                regions.add(RegionRef(x = x, z = z, file = p.toAbsolutePath()))
            }
        }

        // Deterministic ordering.
        return regions.sortedWith(compareBy<RegionRef> { it.x }.thenBy { it.z })
    }
}
