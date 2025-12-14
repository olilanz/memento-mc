package ch.oliverlanz.memento.chunkutils

import ch.oliverlanz.memento.MementoAnchors
import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

object ChunkForgetPredicate {

    fun shouldForget(
        dimension: RegistryKey<World>,
        chunkPos: ChunkPos
    ): Boolean {
        return MementoAnchors.shouldForgetChunk(dimension, chunkPos)
    }
}
