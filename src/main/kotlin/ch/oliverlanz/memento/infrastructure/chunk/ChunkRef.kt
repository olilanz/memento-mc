
package ch.oliverlanz.memento.infrastructure.chunk

import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.registry.RegistryKey

data class ChunkRef(
    val dimension: RegistryKey<World>,
    val pos: ChunkPos
)
