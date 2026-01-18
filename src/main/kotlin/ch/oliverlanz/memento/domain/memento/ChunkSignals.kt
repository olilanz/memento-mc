package ch.oliverlanz.memento.domain.memento

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

data class ChunkKey(
    val world: RegistryKey<World>,
    val regionX: Int,
    val regionZ: Int,
    val chunkX: Int,
    val chunkZ: Int,
)
