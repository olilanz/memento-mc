package ch.oliverlanz.memento.domain.memento

import net.minecraft.registry.RegistryKey
import net.minecraft.util.Identifier
import net.minecraft.world.World

data class ChunkSignals(
    val inhabitedTimeTicks: Long,
    val surfaceY: Int?,
    val biomeId: Identifier?,
    val isSpawnChunk: Boolean,
)

data class ChunkKey(
    val world: RegistryKey<World>,
    val regionX: Int,
    val regionZ: Int,
    val chunkX: Int,
    val chunkZ: Int,
)
