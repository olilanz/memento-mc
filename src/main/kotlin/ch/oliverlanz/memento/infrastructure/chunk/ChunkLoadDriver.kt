package ch.oliverlanz.memento.infrastructure.chunk

import ch.oliverlanz.memento.MementoConstants
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ChunkTicketType
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.LinkedHashMap
import kotlin.math.round

/**
 * Infrastructure-owned chunk load driver.
 *
 * This is the single integration point between Memento and the engine's
 * chunk lifecycle. Engine callbacks are treated as signals only; all
 * coordination happens during [tick].
 *
 * Key properties:
 * - Single-flight proactive loading (one ticket at a time)
 * - Renewal-first via explicit source selection (renewal -> scan)
 * - Reactive correctness (signal != availability)
 * - No ticket expiry
 * - Polite, adaptive pressure via EWMA only
 */
class ChunkLoadDriver {

    companion object {
        @Volatile private var activeInstance: ChunkLoadDriver? = null
        @Volatile private var hooksInstalled: Boolean = false

        fun installEngineHooks() {
            if (hooksInstalled) return
            hooksInstalled = true

            ServerChunkEvents.CHUNK_LOAD.register { world, chunk ->
                activeInstance?.onEngineChunkLoaded(world, chunk)
            }
            ServerChunkEvents.CHUNK_UNLOAD.register { world, chunk ->
                activeInstance?.onEngineChunkUnloaded(world, chunk.pos)
            }
        }
    }

    private val log = LoggerFactory.getLogger("memento")

    private var renewalProvider: ChunkLoadProvider? = null
    private var scanProvider: ChunkLoadProvider? = null
    private val listeners = mutableListOf<ChunkAvailabilityListener>()
    private var server: MinecraftServer? = null

    private var tickCounter: Long = 0

    /* ---------------------------------------------------------------------
     * Adaptive pacing (EWMA-only)
     * ------------------------------------------------------------------ */

    private var latencyEwmaTicks: Double =
        MementoConstants.CHUNK_LOAD_MIN_DELAY_TICKS.toDouble()

    private var nextProactiveIssueTick: Long = 0

    private data class OutstandingTicket(
        val issuedAtTick: Long,
        val countsForPacing: Boolean,
    )

    private val tickets: MutableMap<ChunkRef, OutstandingTicket> = mutableMapOf()

    /* ---------------------------------------------------------------------
     * Pending load tracking
     * ------------------------------------------------------------------ */

    private data class PendingLoad(
        val firstSeenTick: Long,
        var nextRearmTick: Long,
        var rearmAttempts: Int = 0,
        var lastLogTick: Long = 0,
    )

    private val pendingLoads: LinkedHashMap<ChunkRef, PendingLoad> = LinkedHashMap()
    private val pendingUnloads: ArrayDeque<Pair<ServerWorld, ChunkPos>> = ArrayDeque()

    private var lastStateLogTick: Long = 0

    /* ---------------------------------------------------------------------
     * Lifecycle
     * ------------------------------------------------------------------ */

    fun attach(server: MinecraftServer) {
        this.server = server
        activeInstance = this

        tickCounter = 0
        latencyEwmaTicks = MementoConstants.CHUNK_LOAD_MIN_DELAY_TICKS.toDouble()
        nextProactiveIssueTick = 0

        tickets.clear()
        pendingLoads.clear()
        pendingUnloads.clear()
    }

    fun detach() {
        if (activeInstance === this) activeInstance = null

        val s = server
        if (s != null) {
            tickets.keys.forEach { ref ->
                s.getWorld(ref.dimension)
                    ?.chunkManager
                    ?.removeTicket(ChunkTicketType.FORCED, ref.pos, MementoConstants.CHUNK_LOAD_TICKET_RADIUS)
            }
        }

        server = null
        tickets.clear()
        pendingLoads.clear()
        pendingUnloads.clear()
    }

    /**
     * Register the renewal request source.
     *
     * Renewal must be able to operate independently of scanning.
     */
    fun registerRenewalProvider(provider: ChunkLoadProvider) {
        renewalProvider = provider
    }

    /**
     * Register the scanner request source.
     *
     * Scanning must be able to operate independently of renewal.
     */
    fun registerScanProvider(provider: ChunkLoadProvider) {
        scanProvider = provider
    }

    fun registerConsumer(listener: ChunkAvailabilityListener) {
        listeners += listener
    }

    /* ---------------------------------------------------------------------
     * Driver tick
     * ------------------------------------------------------------------ */

    fun tick() {
        tickCounter++

        val s = server ?: return

        forwardPendingLoads(s)
        forwardPendingUnloads()

        if (tickets.isNotEmpty()) {
            logState()
            return
        }

        if (tickCounter < nextProactiveIssueTick) {
            logState()
            return
        }

        issueNextProactiveTicketIfAny(s)
        logState()
    }

    /* ---------------------------------------------------------------------
     * Engine signals
     * ------------------------------------------------------------------ */

    private fun onEngineChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        pendingLoads.putIfAbsent(
            ChunkRef(world.registryKey, chunk.pos),
            PendingLoad(
                firstSeenTick = tickCounter,
                nextRearmTick = tickCounter + MementoConstants.CHUNK_LOAD_REARM_DELAY_TICKS
            )
        )
    }

    private fun onEngineChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
        pendingUnloads.addLast(world to pos)
    }

    /* ---------------------------------------------------------------------
     * Forwarding
     * ------------------------------------------------------------------ */

    private fun forwardPendingLoads(server: MinecraftServer) {
        if (pendingLoads.isEmpty()) return

        val toRemove = mutableListOf<ChunkRef>()
        var forwarded = 0

        // IMPORTANT: Engine chunk-load callbacks may be invoked while we're inside the
        // driver tick (e.g. as side effects of listener logic). Those callbacks mutate
        // [pendingLoads]. Iterating the map directly would therefore be vulnerable to
        // ConcurrentModificationException, even on a single thread.
        //
        // We iterate over a stable snapshot to ensure mutations are applied safely.
        val snapshot = pendingLoads.entries.toList()

        for ((ref, pending) in snapshot) {
            if (forwarded >= MementoConstants.CHUNK_LOAD_MAX_FORWARDED_PER_TICK) break

            val world = server.getWorld(ref.dimension)
            if (world == null) {
                log.warn(
                    "[DRIVER] pending load dropped; world missing dim={} pos={}",
                    ref.dimension.value,
                    ref.pos
                )
                toRemove += ref
                continue
            }

            val chunk = world.chunkManager.getWorldChunk(ref.pos.x, ref.pos.z, false)
            if (chunk == null) {
                val age = tickCounter - pending.firstSeenTick
                val hasTicket = tickets.containsKey(ref)

                if (!hasTicket &&
                    tickCounter >= pending.nextRearmTick &&
                    pending.rearmAttempts < MementoConstants.CHUNK_LOAD_REARM_MAX_ATTEMPTS
                ) {
                    world.chunkManager.addTicket(ChunkTicketType.FORCED, ref.pos, MementoConstants.CHUNK_LOAD_TICKET_RADIUS)
                    tickets[ref] = OutstandingTicket(tickCounter, countsForPacing = false)

                    pending.rearmAttempts++
                    val backoff =
                        MementoConstants.CHUNK_LOAD_REARM_BACKOFF_TICKS *
                            (1 shl (pending.rearmAttempts - 1))
                    pending.nextRearmTick = tickCounter + backoff

                    log.debug(
                        "[DRIVER] re-armed pending load dim={} pos={} ageTicks={} attempt={}/{}",
                        ref.dimension.value,
                        ref.pos,
                        age,
                        pending.rearmAttempts,
                        MementoConstants.CHUNK_LOAD_REARM_MAX_ATTEMPTS
                    )
                }
                continue
            }

            listeners.forEach { it.onChunkLoaded(world, chunk) }
            forwarded++
            toRemove += ref

            val ticket = tickets.remove(ref)
            if (ticket != null) {
                val latency = tickCounter - ticket.issuedAtTick
                if (ticket.countsForPacing) {
                    updateLatencyEwma(latency)
                    nextProactiveIssueTick = tickCounter + computeNextDelayTicks()
                }
                world.chunkManager.removeTicket(ChunkTicketType.FORCED, ref.pos, MementoConstants.CHUNK_LOAD_TICKET_RADIUS)
            }
        }

        toRemove.forEach { pendingLoads.remove(it) }
    }

    private fun forwardPendingUnloads() {
        while (pendingUnloads.isNotEmpty()) {
            val (world, pos) = pendingUnloads.removeFirst()
            listeners.forEach { it.onChunkUnloaded(world, pos) }
        }
    }

    /* ---------------------------------------------------------------------
     * Proactive issuance
     * ------------------------------------------------------------------ */

    private fun issueNextProactiveTicketIfAny(server: MinecraftServer) {
        val next =
            renewalProvider?.desiredChunks()?.firstOrNull()
                ?: scanProvider?.desiredChunks()?.firstOrNull()
                ?: return

        val world = server.getWorld(next.dimension) ?: return

        val alreadyLoaded =
            world.chunkManager.getWorldChunk(next.pos.x, next.pos.z, false)

        if (alreadyLoaded != null) {
            pendingLoads.putIfAbsent(
                next,
                PendingLoad(
                    firstSeenTick = tickCounter,
                    nextRearmTick =
                        tickCounter + MementoConstants.CHUNK_LOAD_REARM_DELAY_TICKS
                )
            )
            return
        }

        world.chunkManager.addTicket(ChunkTicketType.FORCED, next.pos, MementoConstants.CHUNK_LOAD_TICKET_RADIUS)
        tickets[next] = OutstandingTicket(tickCounter, countsForPacing = true)

        log.debug(
            "[DRIVER] ticket issued dim={} pos={}",
            next.dimension.value,
            next.pos
        )
    }

    /* ---------------------------------------------------------------------
     * Adaptive pacing helpers
     * ------------------------------------------------------------------ */

    private fun updateLatencyEwma(sampleTicks: Long) {
        val clamped = sampleTicks
            .coerceAtLeast(MementoConstants.CHUNK_LOAD_MIN_DELAY_TICKS)
            .coerceAtMost(MementoConstants.CHUNK_LOAD_EWMA_MAX_SAMPLE_TICKS)

        latencyEwmaTicks =
            MementoConstants.CHUNK_LOAD_EWMA_WEIGHT_OLD * latencyEwmaTicks +
            MementoConstants.CHUNK_LOAD_EWMA_WEIGHT_NEW * clamped.toDouble()
    }

    private fun computeNextDelayTicks(): Long {
        val proposed = round(latencyEwmaTicks).toLong()
        return proposed.coerceIn(
            MementoConstants.CHUNK_LOAD_MIN_DELAY_TICKS,
            MementoConstants.CHUNK_LOAD_MAX_DELAY_TICKS
        )
    }

    /* ---------------------------------------------------------------------
     * Observability
     * ------------------------------------------------------------------ */

    private fun logState() {
        if ((tickCounter - lastStateLogTick) < MementoConstants.CHUNK_LOAD_STATE_LOG_EVERY_TICKS) return
        lastStateLogTick = tickCounter

        log.debug(
            "[DRIVER] tick={} outstandingTickets={} pendingLoads={} nextProactiveIssueTick={} ewmaTicks={}",
            tickCounter,
            tickets.size,
            pendingLoads.size,
            nextProactiveIssueTick,
            String.format("%.1f", latencyEwmaTicks)
        )
    }
}
