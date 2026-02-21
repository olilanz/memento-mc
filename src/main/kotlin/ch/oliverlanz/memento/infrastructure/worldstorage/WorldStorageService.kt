package ch.oliverlanz.memento.infrastructure.worldstorage

import java.nio.file.Path
import net.minecraft.registry.RegistryKey
import net.minecraft.world.World

/**
 * Infrastructure authority for canonical world-save filesystem topology.
 *
 * All components that need dimension-aware region storage paths must depend on this service to
 * avoid drift between scanner and pruning behaviors.
 */
object WorldStorageService {

    enum class RegionDataKind(val directoryName: String) {
        REGION("region"),
        ENTITIES("entities"),
        POI("poi"),
    }

    data class RegionTriplePaths(
        val region: Path,
        val entities: Path,
        val poi: Path,
    )

    fun resolveDimensionDirectory(root: Path, worldKey: RegistryKey<World>): Path {
        return when (worldKey) {
            World.OVERWORLD -> root
            World.NETHER -> root.resolve("DIM-1")
            World.END -> root.resolve("DIM1")
            else -> {
                val id = worldKey.value
                root.resolve("dimensions").resolve(id.namespace).resolve(id.path)
            }
        }
    }

    fun resolveRegionDataDirectory(
        root: Path,
        worldKey: RegistryKey<World>,
        kind: RegionDataKind,
    ): Path = resolveDimensionDirectory(root, worldKey).resolve(kind.directoryName)

    fun resolveRegionFile(
        root: Path,
        worldKey: RegistryKey<World>,
        kind: RegionDataKind,
        regionX: Int,
        regionZ: Int,
    ): Path = resolveRegionDataDirectory(root, worldKey, kind).resolve("r.${regionX}.${regionZ}.mca")

    fun resolveRegionTriple(
        root: Path,
        worldKey: RegistryKey<World>,
        regionX: Int,
        regionZ: Int,
    ): RegionTriplePaths {
        return RegionTriplePaths(
            region = resolveRegionFile(root, worldKey, RegionDataKind.REGION, regionX, regionZ),
            entities = resolveRegionFile(root, worldKey, RegionDataKind.ENTITIES, regionX, regionZ),
            poi = resolveRegionFile(root, worldKey, RegionDataKind.POI, regionX, regionZ),
        )
    }
}

