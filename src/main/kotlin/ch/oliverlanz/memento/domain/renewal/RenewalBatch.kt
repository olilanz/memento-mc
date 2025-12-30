package ch.oliverlanz.memento.domain.renewal

import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

data class RenewalBatch(
    val name: String,
    val dimension: RegistryKey<World>,
    val chunks: Set<ChunkPos>,
    var state: RenewalBatchState
) {
    private val unloadedFlags: MutableMap<ChunkPos, Boolean> = chunks.associateWith { false }.toMutableMap()
    private val renewedFlags: MutableMap<ChunkPos, Boolean> = chunks.associateWith { false }.toMutableMap()

    fun nextUnrenewedChunk(): ChunkPos? =
        renewedFlags.entries
            .asSequence()
            .filter { !it.value }
            .map { it.key }
            .sortedWith(compareBy<ChunkPos> { it.x }.thenBy { it.z })
            .firstOrNull()

    fun observeUnloaded(pos: ChunkPos) {
        unloadedFlags[pos] = true
    }

    fun observeLoaded(pos: ChunkPos) {
        unloadedFlags[pos] = false
        renewedFlags[pos] = true
    }

    fun allUnloadedSimultaneously(): Boolean = unloadedFlags.values.all { it }

    fun allRenewedAtLeastOnce(): Boolean = renewedFlags.values.all { it }

    fun resetToNewChunkSet(newChunks: Set<ChunkPos>) {
        unloadedFlags.clear()
        renewedFlags.clear()
        for (c in newChunks) {
            unloadedFlags[c] = false
            renewedFlags[c] = false
        }
    }
}
