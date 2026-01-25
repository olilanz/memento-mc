package ch.oliverlanz.memento.application.chunk

import ch.oliverlanz.memento.domain.renewal.BatchWaitingForRenewal
import ch.oliverlanz.memento.domain.renewal.GatePassed
import ch.oliverlanz.memento.domain.renewal.RenewalBatchState
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.stones.StoneTopology
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkStatus
import org.slf4j.LoggerFactory
import java.util.ArrayDeque

/**
 * ChunkLoadDriver performs chunk-load requests over time.
 *
 * Simplified semantics (0.9.6 refinement):
 *
 * - The driver is "inactive" by default.
 * - Any observed chunk load (player/other mods/driver) resets a quiet/settle timer.
 * - If the world has been quiet for [passiveGraceTicks] AND the queue is non-empty,
 *   the driver requests at most one chunk load every [activeLoadIntervalTicks].
 * - The driver requests only ONE load at a time: a request is considered pending until
 *   the engine confirms it via [onChunkLoaded].
 *
 * Note: This class still accepts renewal events directly (Slice 1 compatibility).
 * Later slices will replace this with explicit load-intent from application services.
 */
class ChunkLoadDriver(
    private val activeLoadIntervalTicks: Int,
    private val passiveGraceTicks: Int,
) {

    private val log = LoggerFactory.getLogger("memento")

    private var running: Boolean = false
    private var server: MinecraftServer? = null

    // Driver-local tick counter (increments once per END_SERVER_TICK).
    private var tick: Long = 0L

    // Last tick when ANY chunk load was observed (regardless of origin).
    private var lastObservedChunkLoadTick: Long = 0L

    /**
     * A load request that has been issued to the engine and has not yet been
     * observed via [onChunkLoaded]. This enforces "one request at a time".
     */
    private var pendingLoadRequest: WorkItem? = null

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
        this.tick = 0L
        this.lastObservedChunkLoadTick = 0L
        this.pendingLoadRequest = null
    }

    fun detach() {
        this.running = false
        this.server = null
        queue.clear()
        queuedKeys.clear()
        pendingLoadRequest = null
        tick = 0L
        lastObservedChunkLoadTick = 0L
    }

    /**
     * Must be called for ANY chunk load (player/other mods/driver initiated).
     *
     * This is an activity signal only. The scanner / renewal tracker remain
     * responsible for reacting to the loaded chunk.
     */
    fun onChunkLoaded(dimension: RegistryKey<World>, pos: ChunkPos) {
        if (!running) return

        // Any load activity makes us polite: reset quiet/settle timer.
        lastObservedChunkLoadTick = tick

        // If we initiated a load, consider it fulfilled once the engine confirms the load.
        val pending = pendingLoadRequest
        if (pending != null && pending.dimension == dimension && pending.pos == pos) {
            pendingLoadRequest = null
            log.info(
                "[DRIVER] load confirmed batch='{}' dim='{}' chunk=({}, {})",
                pending.batchName,
                dimension.value.toString(),
                pos.x,
                pos.z
            )
        }

        // Best-effort: if this chunk is in our queue, drop it to avoid redundant loading.
        purgeChunk(dimension, pos)
    }

    /**
     * Slice 1 compatibility: keep event-driven boundary identical to previous scheduler.
     *
     * Later slices will replace this with explicit load-intent from application services.
     */
    fun onRenewalEvent(e: RenewalEvent) {
        when (e) {
            is BatchWaitingForRenewal -> {
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
                    StoneTopology.consume(e.batchName)
                    purgeBatch(e.batchName)
                    log.info("[LOADER] batch='{}' completed -> witherstone consumed", e.batchName)
                }
            }

            else -> Unit
        }
    }

    /**
     * Called once per server tick.
     */
    fun tick() {
        if (!running) return
        val s = server ?: return

        tick++

        if (queue.isEmpty()) return

        // Only one proactive request at a time.
        if (pendingLoadRequest != null) return

        // Respect settle window after any observed chunk load.
        val quietTicks = tick - lastObservedChunkLoadTick
        if (quietTicks < passiveGraceTicks) return

        // Only attempt work every Nth tick while eligible.
        if (activeLoadIntervalTicks <= 0) return
        if (tick % activeLoadIntervalTicks.toLong() != 0L) return

        val item = queue.removeFirstOrNull() ?: return
        queuedKeys.remove(key(item.batchName, item.dimension, item.pos))

        val ok = requestChunkLoadBestEffort(s, item.dimension, item.pos)
        if (ok) {
            pendingLoadRequest = item
            log.info(
                "[DRIVER] proactive load requested batch='{}' dim='{}' chunk=({}, {}) quietTicks={}",
                item.batchName,
                item.dimension.value.toString(),
                item.pos.x,
                item.pos.z,
                quietTicks
            )
        } else {
            // Requeue for later (best effort).
            val k = key(item.batchName, item.dimension, item.pos)
            if (queuedKeys.add(k)) {
                queue.addLast(item)
            }
            // Treat failure as "activity" to avoid tight loops.
            lastObservedChunkLoadTick = tick
        }
    }

    fun debugState(): String {
        val quietTicks = tick - lastObservedChunkLoadTick
        return "tick=$tick quietTicks=$quietTicks pending=${pendingLoadRequest != null} queueSize=${queue.size}"
    }

    private fun purgeBatch(batchName: String) {
        if (queue.isEmpty()) return
        val kept: ArrayDeque<WorkItem> = ArrayDeque(queue.size)
        for (item in queue) {
            if (item.batchName != batchName) {
                kept.addLast(item)
            } else {
                queuedKeys.remove(key(item.batchName, item.dimension, item.pos))
            }
        }
        queue.clear()
        queue.addAll(kept)

        if (pendingLoadRequest?.batchName == batchName) {
            pendingLoadRequest = null
        }
    }

    private fun purgeChunk(dimension: RegistryKey<World>, pos: ChunkPos) {
        if (queue.isEmpty()) return
        val kept: ArrayDeque<WorkItem> = ArrayDeque(queue.size)
        for (item in queue) {
            if (item.dimension == dimension && item.pos == pos) {
                queuedKeys.remove(key(item.batchName, item.dimension, item.pos))
            } else {
                kept.addLast(item)
            }
        }
        queue.clear()
        queue.addAll(kept)

        val pending = pendingLoadRequest
        if (pending != null && pending.dimension == dimension && pending.pos == pos) {
            // Defensive: if we see the requested chunk via purge path, consider it confirmed.
            pendingLoadRequest = null
        }
    }

    private fun requestChunkLoadBestEffort(server: MinecraftServer, dimension: RegistryKey<World>, pos: ChunkPos): Boolean {
        val world = server.getWorld(dimension) ?: return false
        return try {
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

private fun <T> ArrayDeque<T>.removeFirstOrNull(): T? = if (this.isEmpty()) null else this.removeFirst()
