package ch.oliverlanz.memento.application.chunk

import ch.oliverlanz.memento.domain.renewal.BatchWaitingForRenewal
import ch.oliverlanz.memento.domain.renewal.GatePassed
import ch.oliverlanz.memento.domain.renewal.RenewalBatchState
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.stones.StoneTopology
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkStatus
import org.slf4j.LoggerFactory
import java.util.ArrayDeque

/**
 * ChunkLoadDriver performs chunk-load requests over time.
 *
 * Slice 1 (0.9.6): This is a mechanical extraction of the previous renewal-specific
 * ChunkLoadScheduler. Behaviour is intentionally preserved.
 *
 * Later slices will:
 * - add active/passive (idle-aware) pacing
 * - accept load-intent from multiple clients (renewal + scanning)
 * - decouple from domain events
 */
class ChunkLoadDriver(
    private val chunksPerTick: Int,
) {

    private val log = LoggerFactory.getLogger("memento")

    private var running: Boolean = false
    private var server: MinecraftServer? = null

    private data class WorkItem(
        val batchName: String,
        val dimension: RegistryKey<World>,
        val pos: ChunkPos,
    )

    private val queue: ArrayDeque<WorkItem> = ArrayDeque()

    // Used to prevent runaway duplicate queueing.
    private val queuedKeys: MutableSet<String> = mutableSetOf()

    fun attach(server: MinecraftServer) {
        this.server = server
        this.running = true
    }

    fun detach() {
        this.running = false
        this.server = null
        queue.clear()
        queuedKeys.clear()
    }

    /**
     * Slice 1: keep event-driven boundary identical to previous scheduler.
     *
     * Later slices will replace this with explicit load-intent from application services.
     */
    fun onRenewalEvent(e: RenewalEvent) {
        when (e) {
            is BatchWaitingForRenewal -> {
                // Event-driven boundary: build our own work queue from the chunk set.
                var added = 0
                for (pos in e.chunks) {
                    val key = key(e.batchName, e.dimension, pos)
                    if (queuedKeys.add(key)) {
                        queue.addLast(WorkItem(e.batchName, e.dimension, pos))
                        added++
                    }
                }
                if (added > 0) {
                    log.debug(
                        "[LOADER] batch='{}' waitingForRenewal chunksAdded={} queueSize={}",
                        e.batchName,
                        added,
                        queue.size
                    )
                }
            }

            is GatePassed -> {
                if (e.to == RenewalBatchState.RENEWAL_COMPLETE) {
                    // Finalization belongs here (application boundary).
                    StoneTopology.consume(e.batchName)
                    // Drop any leftover queued work for this batch (best-effort cleanup).
                    purgeBatch(e.batchName)
                    log.info("[LOADER] batch='{}' completed -> witherstone consumed", e.batchName)
                }
            }

            else -> Unit
        }
    }

    fun tick() {
        if (!running) return
        val s = server ?: return

        var budget = chunksPerTick
        while (budget > 0 && queue.isNotEmpty()) {
            val item = queue.removeFirst()
            queuedKeys.remove(key(item.batchName, item.dimension, item.pos))

            val ok = requestChunkLoadBestEffort(s, item.dimension, item.pos)
            if (!ok) {
                // simple requeue: try again later
                val k = key(item.batchName, item.dimension, item.pos)
                if (queuedKeys.add(k)) {
                    queue.addLast(item)
                }
            }

            budget--
        }
    }

    private fun purgeBatch(batchName: String) {
        if (queue.isEmpty()) return
        val kept: ArrayDeque<WorkItem> = ArrayDeque(queue.size)
        for (item in queue) {
            if (item.batchName != batchName) kept.addLast(item)
            else queuedKeys.remove(key(item.batchName, item.dimension, item.pos))
        }
        queue.clear()
        queue.addAll(kept)
    }

    private fun requestChunkLoadBestEffort(server: MinecraftServer, dimension: RegistryKey<World>, pos: ChunkPos): Boolean {
        val world = server.getWorld(dimension) ?: return false
        return try {
            // Force a load/generation. The tracker will observe the load event and mark renewal evidence.
            world.getChunk(pos.x, pos.z, ChunkStatus.FULL, true)
            log.debug("[LOADER] requested load dim='{}' chunk=({}, {})", world.registryKey.value, pos.x, pos.z)
            true
        } catch (t: Throwable) {
            log.warn(
                "[LOADER] failed to request chunk load dim='{}' chunk=({}, {}) err={}",
                world.registryKey.value,
                pos.x,
                pos.z,
                t.toString()
            )
            false
        }
    }

    private fun key(batchName: String, dim: RegistryKey<World>, pos: ChunkPos): String =
        batchName + "|" + dim.value.toString() + "|" + pos.x + "," + pos.z
}

private fun MinecraftServer.getWorld(key: RegistryKey<World>): ServerWorld? =
    this.getWorld(key) as? ServerWorld
