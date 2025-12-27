package ch.oliverlanz.memento.domain.land

import ch.oliverlanz.memento.domain.events.StoneMatured
import ch.oliverlanz.memento.domain.events.StoneRemoved
import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import ch.oliverlanz.memento.infrastructure.MementoConstants

/**
 * RenewalTracker is responsible for managing the renewal lifecycle.
 * It tracks RenewalBatch instances and responds to stone lifecycle events.
 */
object RenewalTracker {

    private val renewalBatches = mutableMapOf<String, RenewalBatch>()

    /**
     * Handle the StoneMatured event.
     * @param event The StoneMatured event.
     */
    fun onStoneMatured(event: StoneMatured) {
        if (!renewalBatches.containsKey(event.stoneName)) {
            println("[RenewalTracker] Stone matured: ${event.stoneName}")
            renewalBatches[event.stoneName] = createBatch(event.stoneName)
        }
    }

    /**
     * Handle the StoneRemoved event.
     * @param event The StoneRemoved event.
     */
    fun onStoneRemoved(event: StoneRemoved) {
        renewalBatches.remove(event.stoneName)
        println("[RenewalTracker] Stone removed: ${event.stoneName}")
    }

    /**
     * Handle chunk unload events.
     * @param chunkPos The position of the unloaded chunk.
     */
    fun onChunkUnloaded(chunkPos: ChunkPos) {
        renewalBatches.values.forEach { batch ->
            batch.onChunkUnloaded(chunkPos)
            println("[RenewalTracker] Chunk unloaded: $chunkPos")
        }
    }

    /**
     * Handle chunk load events.
     * @param chunkPos The position of the loaded chunk.
     */
    fun onChunkLoaded(chunkPos: ChunkPos) {
        renewalBatches.values.forEach { batch ->
            batch.onChunkLoaded(chunkPos)
            println("[RenewalTracker] Chunk loaded: $chunkPos")
        }
    }

    /**
     * Expose inspection data for all renewal batches.
     * @return A map of stone names to their associated RenewalBatch.
     */
    fun inspectBatches(): Map<String, RenewalBatch> {
        return renewalBatches.toMap()
    }

    /**
     * Create a new RenewalBatch for a stone.
     * @param stoneName The name of the stone.
     * @return The created RenewalBatch.
     */
    private fun createBatch(stoneName: String): RenewalBatch {
        return RenewalBatch(
            anchorName = stoneName,
            dimension = World.OVERWORLD,
            stonePos = BlockPos(0, 0, 0),
            radiusChunks = MementoConstants.DEFAULT_CHUNKS_RADIUS,
            chunks = listOf(ChunkPos(0, 0))
        )
    }
}