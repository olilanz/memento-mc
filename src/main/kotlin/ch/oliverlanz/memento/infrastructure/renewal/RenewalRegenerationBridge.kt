package ch.oliverlanz.memento.infrastructure.renewal

import ch.oliverlanz.memento.domain.renewal.BatchCompleted
import ch.oliverlanz.memento.domain.renewal.BatchRemoved
import ch.oliverlanz.memento.domain.renewal.BatchWaitingForRenewal
import ch.oliverlanz.memento.domain.renewal.ChunkObserved
import ch.oliverlanz.memento.domain.renewal.RenewalBatchState
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.renewal.RenewalTrigger
import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Infrastructure bridge between:
 *  - RenewalTracker's *observed* lifecycle (domain events), and
 *  - the mixin that intercepts chunk NBT reads.
 *
 * Why this exists:
 *  - The NBT read path may run off-thread.
 *  - RenewalTracker is intentionally not thread-safe.
 *
 * Therefore we keep a small, thread-safe "chunks pending renewal" index here,
 * updated from RenewalTracker events (which are emitted on the server thread).
 */
object RenewalRegenerationBridge {

    private val log = LoggerFactory.getLogger("memento")

    /** dimension-id -> (chunkLong -> true) */
    private val pendingByDimension: ConcurrentHashMap<String, ConcurrentHashMap<Long, Boolean>> = ConcurrentHashMap()

    /** batchName -> (dimension-id, chunkLongs) */
    private val batchIndex: ConcurrentHashMap<String, Pair<String, Set<Long>>> = ConcurrentHashMap()

    fun clear() {
        pendingByDimension.clear()
        batchIndex.clear()
    }

    fun onRenewalEvent(e: RenewalEvent) {
        when (e) {
            is BatchWaitingForRenewal -> onBatchWaitingForRenewal(e)
            is ChunkObserved -> onChunkObserved(e)
            is BatchCompleted -> onBatchTerminal(e.batchName)
            is BatchRemoved -> onBatchTerminal(e.batchName)
            else -> Unit
        }
    }

    /**
     * Called from the mixin (possibly off-thread).
     */
    fun shouldRegenerate(dimension: RegistryKey<World>, pos: ChunkPos): Boolean {
        val dim = dimension.value.toString()
        val map = pendingByDimension[dim] ?: return false
        return map.containsKey(pos.toLong())
    }

    private fun onBatchWaitingForRenewal(e: BatchWaitingForRenewal) {
        val dim = e.dimension.value.toString()
        val chunkLongs = e.chunks.map { it.toLong() }.toSet()

        // Replace any previous index for this batch.
        onBatchTerminal(e.batchName)

        batchIndex[e.batchName] = dim to chunkLongs
        val dimMap = pendingByDimension.computeIfAbsent(dim) { ConcurrentHashMap() }
        for (l in chunkLongs) dimMap[l] = true

        log.info("[BRIDGE] regeneration armed batch='{}' dim='{}' chunks={}", e.batchName, dim, chunkLongs.size)
    }

    private fun onChunkObserved(e: ChunkObserved) {
        if (e.trigger != RenewalTrigger.CHUNK_LOAD) return
        if (e.state != RenewalBatchState.WAITING_FOR_RENEWAL) return

        // First post-unload load is treated as "renewal evidence" for this chunk.
        val idx = batchIndex[e.batchName] ?: return
        val dim = idx.first
        pendingByDimension[dim]?.remove(e.chunk.toLong())
    }

    private fun onBatchTerminal(batchName: String) {
        val idx = batchIndex.remove(batchName) ?: return
        val dim = idx.first
        val chunkLongs = idx.second

        val dimMap = pendingByDimension[dim] ?: return
        for (l in chunkLongs) dimMap.remove(l)
        if (dimMap.isEmpty()) pendingByDimension.remove(dim)
    }
}
