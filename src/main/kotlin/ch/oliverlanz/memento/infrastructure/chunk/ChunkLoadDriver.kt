package ch.oliverlanz.memento.infrastructure.chunk

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
 * This class is the *single* integration point between Memento and the engine's
 * chunk lifecycle. Engine callbacks are treated as signals only; all real work
 * is deferred to [tick].
 *
 * Key properties:
 * - Single-flight: at most one proactive ticket at any time.
 * - Renewal-first: provider registration order defines priority.
 * - Reactive correctness: a load signal does not imply availability; we poll
 *   until the chunk is actually retrievable.
 * - No expiry: tickets are held until forwarded, not time-boxed.
 * - Polite pacing: EWMA over observed proactive load latency.
 */
class ChunkLoadDriver(
    /** Minimum delay (ticks) between proactive ticket issues (politeness floor). */
    val activeLoadIntervalTicks: Int,
    /** Maximum delay (ticks) between proactive ticket issues (politeness ceiling). */
    val passiveGraceTicks: Int,
    /** Delay before re-arming a pending load that raced ahead of materialization. */
    val rearmDelayTicks: Int = 10,
    /** Base backoff (ticks) between re-arm attempts. */
    val rearmBackoffTicks: Int = 20,
    /** Maximum number of re-arm attempts for a pending load. */
    val rearmMaxAttempts: Int = 3,
) {

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

    private val providers = mutableListOf<ChunkLoadProvider>()
    private val listeners = mutableListOf<ChunkAvailabilityListener>()
    private var server: MinecraftServer? = null

    private var tickCounter: Long = 0

    // --- adaptive pacing --------------------------------------------------

    private var latencyEwmaTicks: Double = activeLoadIntervalTicks.toDouble()
    private var nextProactiveIssueTick: Long = 0

    private val ewmaWeightOld = 0.7
    private val ewmaWeightNew = 0.3

    private data class OutstandingTicket(
        val issuedAtTick: Long,
        val countsForPacing: Boolean,
    )

    private val tickets: MutableMap<ChunkRef, OutstandingTicket> = mutableMapOf()

    private data class PendingLoad(
        val firstSeenTick: Long,
        var nextRearmTick: Long,
        var rearmAttempts: Int = 0,
        var lastLogTick: Long = 0,
    )

    private val pendingLoads: LinkedHashMap<ChunkRef, PendingLoad> = LinkedHashMap()
    private val pendingUnloads: ArrayDeque<Pair<ServerWorld, ChunkPos>> = ArrayDeque()

    private var lastStateLogTick: Long = 0

    // ---------------------------------------------------------------------

    fun attach(server: MinecraftServer) {
        this.server = server
        activeInstance = this

        tickCounter = 0
        latencyEwmaTicks = activeLoadIntervalTicks.toDouble()
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
                s.getWorld(ref.dimension)?.chunkManager
                    ?.removeTicket(ChunkTicketType.FORCED, ref.pos, 1)
            }
        }

        server = null
        tickets.clear()
        pendingLoads.clear()
        pendingUnloads.clear()
    }

    fun registerProvider(provider: ChunkLoadProvider) {
        providers += provider
    }

    fun registerConsumer(listener: ChunkAvailabilityListener) {
        listeners += listener
    }

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

    // --- engine signals ---------------------------------------------------

    private fun onEngineChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        pendingLoads.putIfAbsent(
            ChunkRef(world.registryKey, chunk.pos),
            PendingLoad(
                firstSeenTick = tickCounter,
                nextRearmTick = tickCounter + rearmDelayTicks,
            )
        )
    }

    private fun onEngineChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
        pendingUnloads.addLast(world to pos)
    }

    // --- forwarding -------------------------------------------------------

    private fun forwardPendingLoads(server: MinecraftServer) {
        if (pendingLoads.isEmpty()) return

        val toRemove = mutableListOf<ChunkRef>()

        for ((ref, pending) in pendingLoads) {
            val world = server.getWorld(ref.dimension)
            if (world == null) {
                log.warn("[DRIVER] pending load dropped; world missing dim={} pos={}", ref.dimension.value, ref.pos)
                toRemove += ref
                continue
            }

            val chunk = world.chunkManager.getWorldChunk(ref.pos.x, ref.pos.z, false)
            if (chunk == null) {
                val age = tickCounter - pending.firstSeenTick
                val hasTicket = tickets.containsKey(ref)

                if (!hasTicket && tickCounter >= pending.nextRearmTick && pending.rearmAttempts < rearmMaxAttempts) {
                    world.chunkManager.addTicket(ChunkTicketType.FORCED, ref.pos, 1)
                    tickets[ref] = OutstandingTicket(tickCounter, countsForPacing = false)

                    pending.rearmAttempts++
                    val backoff = rearmBackoffTicks * (1 shl (pending.rearmAttempts - 1))
                    pending.nextRearmTick = tickCounter + backoff

                    log.debug(
                        "[DRIVER] re-armed pending load dim={} pos={} ageTicks={} attempt={}/{}",
                        ref.dimension.value,
                        ref.pos,
                        age,
                        pending.rearmAttempts,
                        rearmMaxAttempts
                    )
                }
                continue
            }

            listeners.forEach { it.onChunkLoaded(world, chunk) }
            toRemove += ref

            val ticket = tickets.remove(ref)
            if (ticket != null) {
                val latency = tickCounter - ticket.issuedAtTick
                if (ticket.countsForPacing) {
                    updateLatencyEwma(latency)
                    nextProactiveIssueTick = tickCounter + computeNextDelayTicks()
                }
                world.chunkManager.removeTicket(ChunkTicketType.FORCED, ref.pos, 1)
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

    // --- proactive issuance ----------------------------------------------

    private fun issueNextProactiveTicketIfAny(server: MinecraftServer) {
        val next = providers.asSequence()
            .flatMap { it.desiredChunks() }
            .firstOrNull()
            ?: return

        val world = server.getWorld(next.dimension) ?: return

        val alreadyLoaded = world.chunkManager.getWorldChunk(next.pos.x, next.pos.z, false)
        if (alreadyLoaded != null) {
            pendingLoads.putIfAbsent(
                next,
                PendingLoad(tickCounter, tickCounter + rearmDelayTicks)
            )
            return
        }

        world.chunkManager.addTicket(ChunkTicketType.FORCED, next.pos, 1)
        tickets[next] = OutstandingTicket(tickCounter, countsForPacing = true)

        log.debug("[DRIVER] ticket issued dim={} pos={}", next.dimension.value, next.pos)
    }

    // --- pacing helpers ---------------------------------------------------

    private fun updateLatencyEwma(sampleTicks: Long) {
        val clamped = sampleTicks.coerceAtLeast(1).coerceAtMost(20_000)
        latencyEwmaTicks =
            ewmaWeightOld * latencyEwmaTicks +
            ewmaWeightNew * clamped.toDouble()
    }

    private fun computeNextDelayTicks(): Long {
        val minDelay = activeLoadIntervalTicks.coerceAtLeast(1)
        val maxDelay = passiveGraceTicks.coerceAtLeast(minDelay)
        val proposed = round(latencyEwmaTicks).toLong().coerceAtLeast(1)
        return proposed.coerceIn(minDelay.toLong(), maxDelay.toLong())
    }

    // --- observability ----------------------------------------------------

    private fun logState() {
        if ((tickCounter - lastStateLogTick) < 100) return
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
