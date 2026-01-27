package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadConsumer
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadProvider
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadRequest
import ch.oliverlanz.memento.domain.renewal.BatchCompleted
import ch.oliverlanz.memento.domain.renewal.BatchWaitingForRenewal
import ch.oliverlanz.memento.domain.renewal.GatePassed
import ch.oliverlanz.memento.domain.renewal.RenewalBatchState
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import net.minecraft.registry.RegistryKey
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.chunk.WorldChunk
import java.util.ArrayDeque

/**
 * Bridges RenewalTracker domain events into declarative chunk-load intent.
 *
 * This component is passive:
 * - It collects work from renewal domain events.
 * - It exposes the *current* desired chunk loads via [desiredChunkLoads].
 * - It observes chunk loads (external or proactive) to avoid redundant requests.
 */
class RenewalChunkLoadProvider : ChunkLoadProvider, ChunkLoadConsumer {

    override val name: String = "renewal"

    private data class BatchQueue(
        val dimension: RegistryKey<World>,
        val remaining: ArrayDeque<ChunkPos>,
    )

    // Keep batch order stable by insertion.
    private val batchOrder: ArrayDeque<String> = ArrayDeque()
    private val batches: MutableMap<String, BatchQueue> = linkedMapOf()

    // Best-effort dedupe across all batches.
    // Keys represent chunks that are still desired.
    private val pendingKeys: MutableSet<String> = mutableSetOf()

    override fun desiredChunkLoads(): Sequence<ChunkLoadRequest> = sequence {
        for (batchName in batchOrder) {
            val b = batches[batchName] ?: continue
            for (pos in b.remaining) {
                val k = key(batchName, b.dimension, pos)
                if (!pendingKeys.contains(k)) continue
                yield(ChunkLoadRequest(label = batchName, dimension = b.dimension, pos = pos))
            }
        }
    }

    fun onRenewalEvent(e: RenewalEvent) {
        when (e) {
            is BatchWaitingForRenewal -> {
                val q = BatchQueue(
                    dimension = e.dimension,
                    remaining = ArrayDeque(e.chunks),
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
     * Observe any chunk load to avoid redundant requests.
     *
     * We do not care whether the chunk was expected or unsolicited.
     */
    override fun onChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        val dimension = world.registryKey
        val pos = chunk.pos

        // Remove all matching pending keys regardless of batch.
        val suffix = "|" + dimension.value.toString() + "|" + pos.x + "," + pos.z
        val toRemove = pendingKeys.filter { it.endsWith(suffix) }
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

    private fun key(batchName: String, dimension: RegistryKey<World>, pos: ChunkPos): String {
        return batchName + "|" + dimension.value.toString() + "|" + pos.x + "," + pos.z
    }
}
