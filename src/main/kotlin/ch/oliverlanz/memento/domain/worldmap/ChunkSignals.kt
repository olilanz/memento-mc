package ch.oliverlanz.memento.domain.worldmap

import net.minecraft.registry.RegistryKey
import net.minecraft.util.Identifier
import net.minecraft.world.World

/**
 * Signals observed for an existing chunk.
 *
 * Semantics:
 * - NULL means the signal could not be retrieved (error)
 * - Absence must never be normalized to 0
 */
data class ChunkSignals(
    val inhabitedTimeTicks: Long?,
    val lastUpdateTicks: Long?,
    val surfaceY: Int?,
    val biomeId: String?,
    val isSpawnChunk: Boolean,
)

/**
 * Dominant stone topology signal at chunk granularity.
 *
 * This is geometric/topological only and intentionally independent from lifecycle-derived effect.
 */
enum class DominantStoneSignal {
    NONE,
    LORE,
    WITHER,
}

/**
 * Dominant stone effect signal consumed by projection.
 *
 * This is lifecycle-aware effect semantics derived by stone authority.
 */
enum class DominantStoneEffectSignal {
    NONE,
    LORE_PROTECT,
    WITHER_FORGET,
}

data class ChunkKey(
    val world: RegistryKey<World>,
    val regionX: Int,
    val regionZ: Int,
    val chunkX: Int,
    val chunkZ: Int,
)
