package ch.oliverlanz.memento.infrastructure.chunk

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
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
 * - Politeness: the driver "re-nices" proactive scanning by adapting its pacing based on observed
 *   proactive ticket latency (time from ticket issue → chunk available for forwarding).
 *
 * Important invariant:
 * - Never call into chunk loading APIs (e.g. getChunk / getTopY) inside engine chunk callbacks.
 *   We only enqueue and handle work on subsequent ticks.
 */
class ChunkLoadDriver(
    /** Minimum delay (ticks) between proactive ticket issues (politeness floor). */
    val activeLoadIntervalTicks: Int,
    /** Maximum delay (ticks) between proactive ticket issues (politeness ceiling). */
    val passiveGraceTicks: Int,
    /**
     * If we receive an engine "chunk loaded" signal but cannot yet obtain a [WorldChunk],
     * we may "re-arm" by issuing a short-lived ticket after this delay.
     */
    val rearmDelayTicks: Int = 10,
    /** Base backoff (ticks) between re-arm attempts for the same pending load. */
    val rearmBackoffTicks: Int = 20,
    /** Maximum number of re-arm attempts for a pending load before we stop trying to help. */
    val rearmMaxAttempts: Int = 3,
) {
    companion object {
        // Fabric events are global and cannot be unregistered. We keep a single active instance pointer.
        @Volatile private var activeInstance: ChunkLoadDriver? = null
        @Volatile private var hooksInstalled: Boolean = false

        /**
         * Installs engine event hooks exactly once.
         *
         * The driver remains the single owner of engine chunk lifecycle subscriptions.
         * Memento wires the active instance via [attach]/[detach].
         */
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

    private val providers = mutableListOf<ChunkLoadProvider>()
    private val listeners = mutableListOf<ChunkAvailabilityListener>()
    private var server: MinecraftServer? = null

    private val log = LoggerFactory.getLogger("memento")

    private var tickCounter: Long = 0

    // ---------------------------------------------------------------------
    // Adaptive pacing ("re-nice")
    // ---------------------------------------------------------------------

    /** EWMA of proactive ticket latency (ticks). Updated when a proactively ticketed chunk becomes available. */
    private var latencyEwmaTicks: Double = activeLoadIntervalTicks.toDouble()

    /** Next tick at which we are allowed to issue a proactive ticket. */
    private var nextProactiveIssueTick: Long = 0

    /** EWMA smoothing: ewma = 0.7 * ewma + 0.3 * sample */
    private val ewmaWeightOld: Double = 0.7
    private val ewmaWeightNew: Double = 0.3

    private enum class TicketKind {
        /** Proactive ticket issued to satisfy provider intent (renewal or scanning). */
        PROACTIVE,
        /** Ticket issued only to help materialize a chunk after an observed engine load signal. */
        REARM,
    }

    /** Track the single outstanding ticket (one per chunk ref). */
    private data class OutstandingTicket(
        val issuedAtTick: Long,
        val kind: TicketKind,
    )

    /** Single-flight across all providers: at most one ticket is held at any time. */
    private val tickets = mutableMapOf<ChunkRef, OutstandingTicket>()

    private data class PendingLoad(
        /** When we first observed the engine load signal for this chunk. */
        val firstSeenTick: Long,
        /** Next tick at which we should attempt to "re-arm" by issuing a short-lived ticket. */
        var nextRearmTick: Long,
        /** How many re-arm attempts we've made so far for this pending load. */
        var rearmAttempts: Int = 0,
        /** Last tick we emitted a diagnostic log about this pending item (rate limiting). */
        var lastLogTick: Long = 0,
    )

    /**
     * Deferred engine load signals.
     *
     * We treat engine callbacks as **signals only**. In [tick], we try to resolve whether the chunk
     * is actually present and only then forward it to listeners.
     */
    private val pendingLoads: LinkedHashMap<ChunkRef, PendingLoad> = LinkedHashMap()

    /** Deferred unload notifications. */
    private val pendingUnloads: ArrayDeque<Pair<ServerWorld, ChunkPos>> = ArrayDeque()

    // Observability / rate limiting
    private var lastStateLogTick: Long = 0
    private var lastForwardingLogTick: Long = 0

    /** Avoid spending unbounded time forwarding large load bursts in a single tick. */
    private val maxForwardedLoadsPerTick: Int = 8

    fun attach(server: MinecraftServer) {
        this.server = server

        // Become the active recipient of engine signals.
        activeInstance = this

        // Reset runtime state (safe on server start).
        tickCounter = 0
        latencyEwmaTicks = activeLoadIntervalTicks.toDouble()
        nextProactiveIssueTick = 0

        tickets.clear()
        pendingLoads.clear()
        pendingUnloads.clear()

        lastStateLogTick = 0
        lastForwardingLogTick = 0
    }

    fun detach() {
        // Stop receiving engine signals.
        if (activeInstance === this) {
            activeInstance = null
        }
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
        if (activeInstance === this) activeInstance = null

        tickets.clear()
        pendingLoads.clear()
        pendingUnloads.clear()

        // Reset adaptive pacing state.
        tickCounter = 0
        latencyEwmaTicks = activeLoadIntervalTicks.toDouble()
        nextProactiveIssueTick = 0
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

        // We no longer distinguish between "external" and "internal" loads.
        // All observed loads are simply opportunities to forward availability and harvest metadata.
        log.debug("[DRIVER] observed load signal dim={} pos={}", ref.dimension.value, ref.pos)

        // Defer resolution + forwarding (prevents re-entrancy / lock hazards).
        // Deduplicate: the engine may signal the same chunk multiple times.
        pendingLoads.putIfAbsent(
            ref,
            PendingLoad(
                firstSeenTick = tickCounter,
                nextRearmTick = tickCounter + rearmDelayTicks,
            ),
        )
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
     * 1) Resolve + forward pending load signals (loads/unloads) safely outside engine callbacks.
     *    (If a chunk is not yet retrievable, we keep it pending and retry.)
     * 2) Consider issuing a new proactive ticket based on provider intent.
     */
    fun tick() {
        val s = server ?: return
        tickCounter++

        logState()

        // 1) Forward engine signals to listeners (outside engine callbacks).
        forwardPendingLoads(s)
        forwardPendingUnloads()

        // 2) Proactive ticket issuance (polite, adaptive pacing).
        //    - single-flight across all providers
        //    - respect nextProactiveIssueTick (computed from EWMA latency)
        if (tickets.isNotEmpty()) return
        if (tickCounter < nextProactiveIssueTick) return

        issueNextTicketIfAny(s)
    }

    private fun logState() {
        // State log (rate limited).
        if ((tickCounter - lastStateLogTick) < 100) return
        lastStateLogTick = tickCounter

        log.debug(
            "[DRIVER] tick={} outstandingTickets={} pendingLoads={} nextIssueInTicks={} latencyEwmaTicks={} minDelayTicks={} maxDelayTicks={}",
            tickCounter,
            tickets.size,
            pendingLoads.size,
            kotlin.math.max(0, nextProactiveIssueTick - tickCounter),
            String.format("%.1f", latencyEwmaTicks),
            activeLoadIntervalTicks,
            passiveGraceTicks
        )
    }

    private fun forwardPendingLoads(server: MinecraftServer) {
        if (pendingLoads.isEmpty()) return

        var forwarded = 0
        val toRemove = mutableListOf<ChunkRef>()

        val iter = pendingLoads.entries.iterator()
        while (iter.hasNext() && forwarded < maxForwardedLoadsPerTick) {
            val (ref, pending) = iter.next()
            val world = server.getWorld(ref.dimension)

            if (world == null) {
                // If a world is missing, we can't ever resolve this chunk.
                if ((tickCounter - pending.lastLogTick) >= 100) {
                    pending.lastLogTick = tickCounter
                    log.warn("[DRIVER] pending load dropped; world missing dim={} pos={}", ref.dimension.value, ref.pos)
                }
                toRemove += ref
                continue
            }

            // IMPORTANT: presence check only. Never force-load here.
            val chunk = world.chunkManager.getWorldChunk(ref.pos.x, ref.pos.z, /* create = */ false)
            if (chunk == null) {
                val hasTicket = tickets.containsKey(ref)
                val age = tickCounter - pending.firstSeenTick

                // "Re-arm" load signals that race ahead of chunk presence.
                // If a chunk should be loaded, issuing a short-lived ticket helps the engine
                // actually materialize it so we can forward the load event deterministically.
                if (!hasTicket && tickCounter >= pending.nextRearmTick && pending.rearmAttempts < rearmMaxAttempts) {
                    world.chunkManager.addTicket(ChunkTicketType.UNKNOWN, ref.pos, 1)
                    tickets.putIfAbsent(ref, OutstandingTicket(issuedAtTick = tickCounter, kind = TicketKind.REARM))

                    pending.rearmAttempts++
                    val backoffFactor = 1L shl (pending.rearmAttempts - 1)
                    pending.nextRearmTick = tickCounter + (rearmBackoffTicks * backoffFactor).toInt().coerceAtLeast(1)

                    log.debug(
                        "[DRIVER] re-armed pending load via ticket dim={} pos={} ageTicks={} attempt={}/{} nextRearmTick={}",
                        ref.dimension.value,
                        ref.pos,
                        age,
                        pending.rearmAttempts,
                        rearmMaxAttempts,
                        pending.nextRearmTick
                    )
                }

                // Keep pending indefinitely (no time-based expiry). Periodically explain why we are waiting.
                if ((tickCounter - pending.lastLogTick) >= 100) {
                    pending.lastLogTick = tickCounter
                    log.debug(
                        "[DRIVER] pending load not yet present; will retry dim={} pos={} ageTicks={} hasTicket={} rearmAttempts={}/{} nextRearmTick={}",
                        ref.dimension.value,
                        ref.pos,
                        age,
                        tickets.containsKey(ref),
                        pending.rearmAttempts,
                        rearmMaxAttempts,
                        pending.nextRearmTick
                    )
                }

                continue
            }

            // Chunk is present: forward to listeners.
            listeners.forEach { it.onChunkLoaded(world, chunk) }
            forwarded++
            toRemove += ref

            // If this chunk has an outstanding ticket, release it *after* forwarding.
            val ticket = tickets[ref]
            if (ticket != null) {
                world.chunkManager.removeTicket(ChunkTicketType.UNKNOWN, ref.pos, /* radius */ 1)
                tickets.remove(ref)

                if (ticket.kind == TicketKind.PROACTIVE) {
                    val observedLatencyTicks = tickCounter - ticket.issuedAtTick

                    // Update adaptive pacing based on observed latency.
                    updateLatencyEwma(observedLatencyTicks)
                    val nextDelayTicks = computeNextDelayTicks()
                    nextProactiveIssueTick = tickCounter + nextDelayTicks

                    log.debug(
                        "[DRIVER] released proactive ticket after forward dim={} pos={} latencyTicks={} ewmaTicks={} nextDelayTicks={}",
                        ref.dimension.value,
                        ref.pos,
                        observedLatencyTicks,
                        String.format("%.1f", latencyEwmaTicks),
                        nextDelayTicks
                    )
                } else {
                    log.debug(
                        "[DRIVER] released rearm ticket after forward dim={} pos={}",
                        ref.dimension.value,
                        ref.pos,
                    )
                }
            }
        }

        // Remove successfully forwarded / dropped entries.
        toRemove.forEach { pendingLoads.remove(it) }

        if ((tickCounter - lastForwardingLogTick) >= 100) {
            lastForwardingLogTick = tickCounter
            if (pendingLoads.isNotEmpty()) {
                log.debug(
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

    // ---------------------------------------------------------------------
    // Adaptive pacing helpers
    // ---------------------------------------------------------------------

    private fun updateLatencyEwma(sampleTicks: Long) {
        // Clamp to avoid numeric blow-ups if something goes deeply wrong.
        // We bound to a multiple of the configured maximum delay to keep EWMA stable.
        val clampMax = (passiveGraceTicks.coerceAtLeast(activeLoadIntervalTicks).toLong() * 10L).coerceAtLeast(1L)
        val clampedSample = sampleTicks.coerceAtLeast(1L).coerceAtMost(clampMax)
        latencyEwmaTicks = (ewmaWeightOld * latencyEwmaTicks) + (ewmaWeightNew * clampedSample.toDouble())
    }

    private fun computeNextDelayTicks(): Long {
        val minDelay = activeLoadIntervalTicks.coerceAtLeast(1)
        val maxDelay = passiveGraceTicks.coerceAtLeast(minDelay)
        val proposed = kotlin.math.round(latencyEwmaTicks).toLong().coerceAtLeast(1)
        return proposed.coerceIn(minDelay.toLong(), maxDelay.toLong())
    }

    private fun issueNextTicketIfAny(server: MinecraftServer) {
        // Provider precedence is registration order (renewal first, scanner last).
        val next: ChunkRef = providers.asSequence()
            .flatMap { it.desiredChunks() }
            .firstOrNull()
            ?: return

        // Single-flight: if we already have a ticket for this chunk, do nothing (should not happen).
        if (tickets.containsKey(next)) return

        val world = server.getWorld(next.dimension) ?: return

        // If already loaded, enqueue an "artificial" load signal (no ticket, no pacing impact).
        val alreadyLoaded = world.chunkManager.getWorldChunk(next.pos.x, next.pos.z, /* create = */ false)
        if (alreadyLoaded != null) {
            log.debug("[DRIVER] chunk already loaded; enqueue artificial availability dim={} pos={}", next.dimension.value, next.pos)
            pendingLoads.putIfAbsent(
                next,
                PendingLoad(
                    firstSeenTick = tickCounter,
                    nextRearmTick = tickCounter + rearmDelayTicks,
                ),
            )
            return
        }

        // Issue ticket. We will release it after we observe the load signal and forward availability.
        world.chunkManager.addTicket(ChunkTicketType.UNKNOWN, next.pos, /* radius */ 1)
        tickets[next] = OutstandingTicket(issuedAtTick = tickCounter, kind = TicketKind.PROACTIVE)

        log.debug("[DRIVER] proactive ticket issued dim={} pos={}", next.dimension.value, next.pos)
    }

}
