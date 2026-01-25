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
 * Slice 2 (0.9.6): Introduce active/passive pacing.
 *
 * - PASSIVE whenever the server is already loading chunks (players/other mods/etc.)
 * - After the last observed chunk load, remain PASSIVE for [passiveGraceTicks]
 * - When there is pending work and grace has elapsed, enter ACTIVE
 * - In ACTIVE, request at most one chunk load every [activeLoadIntervalTicks]
 * - When the queue is empty, enter IDLE
 *
 * Note: This class still accepts renewal events directly (Slice 1 compatibility).
 * Later slices will replace this with explicit load-intent from application services.
 */
class ChunkLoadDriver(
    private val activeLoadIntervalTicks: Int,
    private val passiveGraceTicks: Int,
) {

    private val log = LoggerFactory.getLogger("memento")

    private enum class Mode {
        IDLE,
        PASSIVE,
        ACTIVE,
    }

    private var running: Boolean = false
    private var server: MinecraftServer? = null

    // Driver-local tick counter (increments once per END_SERVER_TICK).
    private var tick: Long = 0L

    // Any chunk load (regardless of origin) pushes us into PASSIVE.
    private var passiveUntilTick: Long = 0L

    // Prevent multiple proactive loads in flight.
    private var loadInFlight: WorkItem? = null

    private var mode: Mode = Mode.IDLE

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
        this.passiveUntilTick = 0L
        this.loadInFlight = null
        this.mode = Mode.IDLE
    }

    fun detach() {
        this.running = false
        this.server = null
        queue.clear()
        queuedKeys.clear()
        loadInFlight = null
        mode = Mode.IDLE
        tick = 0L
        passiveUntilTick = 0L
    }

    /**
     * Must be called for ANY chunk load (player/other mods/driver initiated).
     *
     * This is an activity signal only. The scanner / renewal tracker remain
     * responsible for reacting to the loaded chunk.
     */
    fun onChunkLoaded(dimension: RegistryKey<World>, pos: ChunkPos) {
        if (!running) return

        // Any load activity makes us polite.
        passiveUntilTick = tick + passiveGraceTicks

        // If we initiated a load, consider it fulfilled once the engine confirms the load.
        val inflight = loadInFlight
        if (inflight != null && inflight.dimension == dimension && inflight.pos == pos) {
            loadInFlight = null
        }

        // Best-effort: if this chunk is in our queue, drop it to avoid redundant loading.
        // (This is conservative; later slices will unify keys across multiple requesters.)
        // Remove all items for the same dimension+pos.
        purgeChunk(dimension, pos)

        mode = if (queue.isEmpty()) Mode.IDLE else Mode.PASSIVE
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
                // New work keeps us from being idle; remain passive for grace.
                if (mode == Mode.IDLE) {
                    mode = Mode.PASSIVE
                    passiveUntilTick = maxOf(passiveUntilTick, tick + passiveGraceTicks)
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

        // State update.
        if (queue.isEmpty()) {
            mode = Mode.IDLE
            return
        }

        // Any in-flight load blocks further proactive loads.
        if (loadInFlight != null) {
            mode = Mode.PASSIVE
            return
        }

        // Respect passive window after any observed chunk load.
        if (tick < passiveUntilTick) {
            mode = Mode.PASSIVE
            return
        }

        // Only attempt work every Nth tick while active.
        if (activeLoadIntervalTicks <= 0) return
        if (tick % activeLoadIntervalTicks.toLong() != 0L) {
            mode = Mode.ACTIVE
            return
        }

        mode = Mode.ACTIVE
        val item = queue.removeFirstOrNull() ?: run {
            mode = Mode.IDLE
            return
        }
        queuedKeys.remove(key(item.batchName, item.dimension, item.pos))

        val ok = requestChunkLoadBestEffort(s, item.dimension, item.pos)
        if (ok) {
            loadInFlight = item
        } else {
            // Requeue for later.
            val k = key(item.batchName, item.dimension, item.pos)
            if (queuedKeys.add(k)) {
                queue.addLast(item)
            }
            // Failed attempt should not cause a tight loop; treat it as activity and back off.
            passiveUntilTick = tick + passiveGraceTicks
            mode = Mode.PASSIVE
        }
    }

    fun debugState(): String {
        return "mode=$mode tick=$tick passiveUntil=$passiveUntilTick inflight=${loadInFlight != null} queueSize=${queue.size}"
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
        if (loadInFlight?.batchName == batchName) {
            loadInFlight = null
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
        val inflight = loadInFlight
        if (inflight != null && inflight.dimension == dimension && inflight.pos == pos) {
            loadInFlight = null
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
