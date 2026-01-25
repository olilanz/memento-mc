package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.application.chunk.ChunkLoadProvider
import ch.oliverlanz.memento.application.chunk.ChunkLoadRequest
import ch.oliverlanz.memento.domain.renewal.BatchCompleted
import ch.oliverlanz.memento.domain.renewal.BatchWaitingForRenewal
import ch.oliverlanz.memento.domain.renewal.GatePassed
import ch.oliverlanz.memento.domain.renewal.RenewalBatchState
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import java.util.ArrayDeque

/**
 * Bridges RenewalTracker domain events into paced chunk-load intent.
 *
 * This provider is passive:
 * - It collects work from renewal domain events.
 * - The driver pulls one chunk at a time via [nextChunkLoad].
 */
class RenewalChunkLoadProvider : ChunkLoadProvider {

    private data class BatchQueue(
        val dimension: RegistryKey<World>,
        val remaining: ArrayDeque<ChunkPos>,
    )

    // Keep batch order stable by insertion.
    private val batchOrder: ArrayDeque<String> = ArrayDeque()
    private val batches: MutableMap<String, BatchQueue> = linkedMapOf()

    // Best-effort dedupe across all batches.
    private val pendingKeys: MutableSet<String> = mutableSetOf()

    override fun nextChunkLoad(): ChunkLoadRequest? {
        while (batchOrder.isNotEmpty()) {
            val batchName = batchOrder.first()
            val b = batches[batchName]
            if (b == null) {
                batchOrder.removeFirst()
                continue
            }

            while (b.remaining.isNotEmpty()) {
                val pos = b.remaining.removeFirst()
                val k = key(batchName, b.dimension, pos)
                if (!pendingKeys.remove(k)) {
                    // Either already satisfied via observed load, or previously drained.
                    continue
                }
                return ChunkLoadRequest(label = batchName, dimension = b.dimension, pos = pos)
            }

            // Batch exhausted.
            batches.remove(batchName)
            batchOrder.removeFirst()
        }
        return null
    }

    fun onRenewalEvent(e: RenewalEvent) {
        when (e) {
            is BatchWaitingForRenewal -> {
                val q = BatchQueue(
                    dimension = e.dimension,
                    remaining = ArrayDeque(e.chunks)
                )

                // Replace any previous queue for the same batch name.
                if (!batches.containsKey(e.batchName)) {
                    batchOrder.addLast(e.batchName)
                } else {
                    // Remove previous keys for this batch; we will rebuild.
                    removePendingForBatch(e.batchName, batches[e.batchName]!!.dimension)
                }

                batches[e.batchName] = q
                for (pos in e.chunks) {
                    pendingKeys.add(key(e.batchName, e.dimension, pos))
                }
            }

            is BatchCompleted -> {
                dropBatch(e.batchName)
            }

            is GatePassed -> {
                if (e.to == RenewalBatchState.RENEWAL_COMPLETE) {
                    dropBatch(e.batchName)
                }
            }

            else -> Unit
        }
    }

    /**
     * Optional but recommended: observe any chunk load to avoid redundant requests.
     */
    fun onChunkLoaded(dimension: RegistryKey<World>, pos: ChunkPos) {
        // Remove all matching pending keys regardless of batch.
        val toRemove = pendingKeys.filter { it.endsWith("|" + dimension.value.toString() + "|" + pos.x + "," + pos.z) }
        for (k in toRemove) pendingKeys.remove(k)
    }

    private fun dropBatch(batchName: String) {
        val b = batches.remove(batchName) ?: return
        removePendingForBatch(batchName, b.dimension)
        batchOrder.remove(batchName)
    }

    private fun removePendingForBatch(batchName: String, dimension: RegistryKey<World>) {
        val prefix = batchName + "|" + dimension.value.toString() + "|"
        val it = pendingKeys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (k.startsWith(prefix)) it.remove()
        }
    }

    private fun key(batchName: String, dim: RegistryKey<World>, pos: ChunkPos): String =
        batchName + "|" + dim.value.toString() + "|" + pos.x + "," + pos.z
}
