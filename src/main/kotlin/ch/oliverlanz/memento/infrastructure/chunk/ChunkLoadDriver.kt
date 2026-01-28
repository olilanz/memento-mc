package ch.oliverlanz.memento.infrastructure.chunk

import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ChunkTicketType
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk
import org.slf4j.LoggerFactory
import java.util.ArrayDeque

/**
 * Infrastructure-owned chunk load driver.
 *
 * This class is the *single* integration point between Memento and the engine's chunk lifecycle:
 * - Engine callbacks (chunk load/unload) are treated as **signals only**.
 * - All real work (including notifying listeners) is deferred to [tick] to avoid re-entrancy and lock hazards.
 *
 * High-level behavior:
 * - Providers declare *intent* (desired chunks) via [ChunkLoadProvider.desiredChunks].
 * - The driver decides *when* to request loads by issuing and releasing chunk tickets.
 * - Politeness: when we observe chunk loads that were not caused by our tickets, we **yield** for a grace period.
 *
 * Important invariant:
 * - Never call into chunk loading APIs (e.g. getChunk / getTopY) inside engine chunk callbacks.
 *   We only enqueue and handle work on subsequent ticks.
 */
class ChunkLoadDriver(
    /** In driving mode, attempt at most one proactive load every N ticks. */
    val activeLoadIntervalTicks: Int,
    /** After observing an external load, yield for this many ticks. */
    val passiveGraceTicks: Int,
    /** Safety valve for tickets that never result in a load callback. */
    val ticketMaxAgeTicks: Int
) {
    private val providers = mutableListOf<ChunkLoadProvider>()
    private val listeners = mutableListOf<ChunkAvailabilityListener>()
    private var server: MinecraftServer? = null

    private val log = LoggerFactory.getLogger("memento")

    private var tickCounter: Long = 0

    /**
     * The most recent tick when we observed an *external* chunk load, i.e. a load that did not correspond
     * to any outstanding driver-issued ticket.
     *
     * Null means: "no external load observed yet since attach" → not yielding by default.
     */
    private var lastExternalLoadTick: Long? = null

    /** Track outstanding proactive load tickets (one per chunk ref). */
    private data class OutstandingTicket(val issuedAtTick: Long)

    private val tickets = mutableMapOf<ChunkRef, OutstandingTicket>()

    /**
     * Deferred engine signals.
     *
     * We collect chunk-load signals in [onEngineChunkLoaded] and forward them to listeners in [tick],
     * when we are no longer inside engine locks.
     */
    private val pendingLoads: ArrayDeque<ChunkRef> = ArrayDeque()

    /** Chunk refs for which we must release a proactive ticket after forwarding availability to listeners. */
    private val pendingTicketReleases: ArrayDeque<ChunkRef> = ArrayDeque()

    /** Deferred unload notifications. */
    private val pendingUnloads: ArrayDeque<Pair<ServerWorld, ChunkPos>> = ArrayDeque()

    // Observability / rate limiting
    private var lastStateLogTick: Long = 0
    private var lastSuppressionLogTick: Long = 0
    private var lastForwardingLogTick: Long = 0

    /** Avoid spending unbounded time forwarding external load bursts in a single tick. */
    private val maxForwardedLoadsPerTick: Int = 8

    fun attach(server: MinecraftServer) {
        this.server = server

        // Reset runtime state (safe on server start).
        tickCounter = 0
        lastExternalLoadTick = null

        tickets.clear()
        pendingLoads.clear()
        pendingTicketReleases.clear()
        pendingUnloads.clear()

        lastStateLogTick = 0
        lastSuppressionLogTick = 0
        lastForwardingLogTick = 0
    }

    fun detach() {
        val s = server
        if (s != null) {
            // Best-effort cleanup: release any remaining tickets.
            tickets.keys.forEach { ref ->
                val world = s.getWorld(ref.dimension) ?: return@forEach
                world.chunkManager.removeTicket(ChunkTicketType.UNKNOWN, ref.pos, /* radius */ 1)
            }
        }

        providers.clear()
        listeners.clear()
        server = null

        tickets.clear()
        pendingLoads.clear()
        pendingTicketReleases.clear()
        pendingUnloads.clear()

        lastExternalLoadTick = null
    }

    fun registerProvider(provider: ChunkLoadProvider) {
        providers += provider
    }

    fun registerConsumer(listener: ChunkAvailabilityListener) {
        listeners += listener
    }

    /**
     * Sole engine entrypoint for chunk load events.
     *
     * IMPORTANT: This method must remain **signal-only**. Do not do any chunk queries or heavy work here.
     */
    fun onEngineChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        val ref = ChunkRef(world.registryKey, chunk.pos)

        // Determine whether this load was caused by an outstanding driver ticket.
        val matchedTicket = tickets.remove(ref)
        if (matchedTicket != null) {
            // Defer ticket release until *after* we have forwarded availability to listeners in tick().
            pendingTicketReleases.addLast(ref)
            log.info(
                "[DRIVER] observed proactive load dim={} pos={} ticketAgeTicks={}",
                ref.dimension.value,
                ref.pos,
                (tickCounter - matchedTicket.issuedAtTick)
            )
        } else {
            // External load: enter yielding mode for a grace window.
            lastExternalLoadTick = tickCounter
            log.info("[DRIVER] observed external load dim={} pos={}", ref.dimension.value, ref.pos)
        }

        // Defer listener notifications (prevents re-entrancy / lock hazards).
        pendingLoads.addLast(ref)
    }

    /**
     * Sole engine entrypoint for chunk unload events.
     *
     * We forward unload notifications on the next tick to keep symmetry and avoid doing work in callbacks.
     */
    fun onEngineChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
        pendingUnloads.addLast(world to pos)
    }

    /**
     * Driver heartbeat (called once per server tick).
     *
     * Order of operations:
     * 1) Forward engine signals (loads/unloads) safely outside engine callbacks.
     * 2) Release fulfilled tickets (after listeners saw the chunk).
     * 3) Expire stuck tickets (safety valve).
     * 4) If not yielding, consider issuing a new proactive ticket based on provider intent.
     */
    fun tick() {
        val s = server ?: return
        tickCounter++

        val externalAge: Long? = lastExternalLoadTick?.let { tickCounter - it }
        val isYielding: Boolean = externalAge?.let { it <= passiveGraceTicks } ?: false

        logState(isYielding, externalAge)

        // 1) Forward engine signals to listeners (outside engine callbacks).
        forwardPendingLoads(s)
        forwardPendingUnloads()

        // 2) Release tickets for proactive loads (after forwarding to listeners).
        releasePendingTickets(s)

        // 3) Expire stuck tickets (safety valve).
        expireTickets(s)

        // 4) If yielding, suppress proactive ticket issuance.
        if (isYielding) {
            logSuppressionIfThereIsIntent(externalAge)
            return
        }

        // Driving mode: request at most one proactive load per interval, and keep ticket count low.
        if ((tickCounter % activeLoadIntervalTicks.toLong()) != 0L) return
        if (tickets.isNotEmpty()) return

        issueNextTicketIfAny(s)
    }

    private fun logState(isYielding: Boolean, externalAge: Long?) {
        // State log (rate limited).
        if ((tickCounter - lastStateLogTick) < 100) return
        lastStateLogTick = tickCounter

        log.info(
            "[DRIVER] yielding={} tick={} externalAge={} graceTicks={} outstandingTickets={} pendingLoads={}",
            isYielding,
            tickCounter,
            externalEnsurePrintable(externalAge),
            passiveGraceTicks,
            tickets.size,
            pendingLoads.size
        )
    }

    private fun logSuppressionIfThereIsIntent(externalAge: Long?) {
        // If providers want work but we are yielding, explain it (rate limited).
        if ((tickCounter - lastSuppressionLogTick) < 100) return

        val hasIntent = providers.asSequence().flatMap { it.desiredChunks() }.iterator().hasNext()
        if (!hasIntent) return

        lastSuppressionLogTick = tickCounter
        log.info(
            "[DRIVER] suppressing proactive loads reason=YIELDING externalAge={} graceTicks={}",
            externalEnsurePrintable(externalAge),
            passiveGraceTicks
        )
    }

    private fun forwardPendingLoads(server: MinecraftServer) {
        if (pendingLoads.isEmpty()) return

        var forwarded = 0
        while (forwarded < maxForwardedLoadsPerTick && pendingLoads.isNotEmpty()) {
            val ref = pendingLoads.removeFirst()
            val world = server.getWorld(ref.dimension)

            if (world == null) {
                log.warn("[DRIVER] cannot forward load; world missing dim={} pos={}", ref.dimension.value, ref.pos)
                // Still release ticket if we were holding one (best-effort).
                // (releasePendingTickets handles ticket release separately.)
                forwarded++
                continue
            }

            val chunk = world.chunkManager.getWorldChunk(ref.pos.x, ref.pos.z, /* create = */ false)
            if (chunk == null) {
                log.warn("[DRIVER] cannot forward load; chunk not present after load signal dim={} pos={}", ref.dimension.value, ref.pos)
                forwarded++
                continue
            }

            listeners.forEach { it.onChunkLoaded(world, chunk) }
            forwarded++
        }

        if ((tickCounter - lastForwardingLogTick) >= 100) {
            lastForwardingLogTick = tickCounter
            if (pendingLoads.isNotEmpty()) {
                log.info(
                    "[DRIVER] forwardedLoads={} remainingPendingLoads={}",
                    forwarded,
                    pendingLoads.size
                )
            }
        }
    }

    private fun forwardPendingUnloads() {
        if (pendingUnloads.isEmpty()) return
        while (pendingUnloads.isNotEmpty()) {
            val (world, pos) = pendingUnloads.removeFirst()
            listeners.forEach { it.onChunkUnloaded(world, pos) }
        }
    }

    private fun releasePendingTickets(server: MinecraftServer) {
        if (pendingTicketReleases.isEmpty()) return
        while (pendingTicketReleases.isNotEmpty()) {
            val ref = pendingTicketReleases.removeFirst()
            val world = server.getWorld(ref.dimension) ?: continue
            world.chunkManager.removeTicket(ChunkTicketType.UNKNOWN, ref.pos, /* radius */ 1)
            log.info("[DRIVER] released ticket dim={} pos={}", ref.dimension.value, ref.pos)
        }
    }

    private fun expireTickets(server: MinecraftServer) {
        if (tickets.isEmpty()) return

        val now = tickCounter
        val expired = tickets.entries
            .filter { (_, t) -> (now - t.issuedAtTick) >= ticketMaxAgeTicks }
            .map { it.key }

        if (expired.isEmpty()) return

        for (ref in expired) {
            val world = server.getWorld(ref.dimension) ?: continue
            val issuedAt = tickets[ref]?.issuedAtTick ?: now
            world.chunkManager.removeTicket(ChunkTicketType.UNKNOWN, ref.pos, /* radius */ 1)
            tickets.remove(ref)
            log.warn(
                "[DRIVER] ticket expired dim={} pos={} ageTicks={} maxAgeTicks={}",
                ref.dimension.value,
                ref.pos,
                now - issuedAt,
                ticketMaxAgeTicks
            )
        }
    }

    private fun issueNextTicketIfAny(server: MinecraftServer) {
        // Provider precedence is registration order (renewal first, scanner last).
        val next: ChunkRef = providers.asSequence()
            .flatMap { it.desiredChunks() }
            .firstOrNull()
            ?: return

        // If we already have a ticket for this chunk, do nothing (should not happen because we keep tickets low).
        if (tickets.containsKey(next)) return

        val world = server.getWorld(next.dimension) ?: return

        // If already loaded, emit an "artificial" load signal by enqueueing it (do NOT count as external).
        val alreadyLoaded = world.chunkManager.getWorldChunk(next.pos.x, next.pos.z, /* create = */ false)
        if (alreadyLoaded != null) {
            log.info("[DRIVER] chunk already loaded; enqueue artificial availability dim={} pos={}", next.dimension.value, next.pos)
            pendingLoads.addLast(next)
            return
        }

        // Issue ticket. We will release it after we observe the load signal and forward availability.
        world.chunkManager.addTicket(ChunkTicketType.UNKNOWN, next.pos, /* radius */ 1)
        tickets[next] = OutstandingTicket(issuedAtTick = tickCounter)

        log.info("[DRIVER] ticket issued dim={} pos={}", next.dimension.value, next.pos)
    }

    private fun externalEnsurePrintable(externalAge: Long?): Any =
        externalAge ?: "none"
}
