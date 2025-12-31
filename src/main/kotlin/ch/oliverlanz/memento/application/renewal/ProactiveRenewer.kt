package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.renewal.BatchQueuedForRenewal
import ch.oliverlanz.memento.domain.renewal.GatePassed
import ch.oliverlanz.memento.domain.renewal.RenewalBatchState
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.stones.StoneRegister
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkStatus
import org.slf4j.LoggerFactory
import java.util.ArrayDeque

/**
 * ProactiveRenewer performs renewal work over time.
 *
 * Responsibilities (locked):
 * - React to RenewalTracker's execution boundary events (no polling)
 * - Own the work queue and pacing
 * - Request chunk loads (best-effort) to accelerate renewal
 *
 * Non-responsibilities:
 * - It does NOT own the batch lifecycle or correctness
 * - It does NOT infer completion; that is observed via chunk-load events by RenewalTracker
 */
class ProactiveRenewer(
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

    fun onRenewalEvent(e: RenewalEvent) {
        when (e) {
            is BatchQueuedForRenewal -> {
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
                    log.info("[RENEW] batch='{}' queuedForRenewal chunksAdded={} queueSize={}", e.batchName, added, queue.size)
                }
            }

            is GatePassed -> {
                if (e.to == RenewalBatchState.RENEWAL_COMPLETE) {
                    // Finalization belongs here (application boundary).
                    StoneRegister.consume(e.batchName)
                    // Drop any leftover queued work for this batch (best-effort cleanup).
                    purgeBatch(e.batchName)
                    log.info("[RENEW] batch='{}' completed -> witherstone consumed", e.batchName)
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
            log.info("[RENEW] requested load dim='{}' chunk=({}, {})", world.registryKey.value, pos.x, pos.z)
            true
        } catch (t: Throwable) {
            log.warn(
                "[RENEW] failed to request chunk load dim='{}' chunk=({}, {}) err={}",
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
