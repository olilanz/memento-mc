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
    fun observeUnloaded(pos: ChunkPos) {
        unloadedFlags[pos] = true
    }

    fun observeLoaded(pos: ChunkPos) {
        unloadedFlags[pos] = false
    }

    /**
     * Applies an initial snapshot of current chunk load state for this batch.
     *
     * This is an observation step (not an assumption): chunks that are currently not loaded
     * are treated as 'unloaded' for the purpose of the unload gate.
     */
    fun applyInitialLoadedSnapshot(loadedChunks: Set<ChunkPos>) {
        for (c in chunks) {
            unloadedFlags[c] = !loadedChunks.contains(c)
        }
    }


    /**
     * Records that this chunk has been observed loaded after the batch entered WAITING_FOR_RENEWAL.
     * This is evidence that renewal has happened at least once for this chunk.
     */
    fun observeRenewed(pos: ChunkPos) {
        renewedFlags[pos] = true
    }

    fun resetRenewalEvidence() {
        for (c in chunks) {
            renewedFlags[c] = false
        }
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