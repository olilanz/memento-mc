package ch.oliverlanz.memento.application.run

import net.minecraft.registry.RegistryKey
import net.minecraft.world.World
import java.nio.file.Path

/** Application-level discovery output used to plan extraction work. */
data class WorldDiscoveryPlan(
    val worlds: List<DiscoveredWorld>,
)

data class DiscoveredWorld(
    val world: RegistryKey<World>,
    val regions: List<RegionRef>,
)

data class RegionRef(
    val x: Int,
    val z: Int,
    /** Absolute path to the region file (e.g. .../region/r.<x>.<z>.mca). */
    val file: Path,

    /**
     * Planned chunk work units within this region.
     *
     * Slice (world+region discovery): filled by [ChunkDiscovery] with dummy values.
     * Later slices will populate this list using region headers (existing chunk slots only).
     */
    val chunks: List<ChunkRef> = emptyList(),
)

/**
 * Planned chunk coordinate within a region.
 *
 * Stored as region-local coords (0..31) to match the region file header layout.
 */
data class ChunkRef(
    val localX: Int,
    val localZ: Int,
)
