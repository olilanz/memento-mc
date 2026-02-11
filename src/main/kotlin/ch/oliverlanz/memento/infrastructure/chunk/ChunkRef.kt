package ch.oliverlanz.memento.infrastructure.chunk

import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

data class ChunkRef(val dimension: RegistryKey<World>, val pos: ChunkPos)
