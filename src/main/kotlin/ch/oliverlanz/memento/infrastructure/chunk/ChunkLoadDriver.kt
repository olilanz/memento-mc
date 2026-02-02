package ch.oliverlanz.memento.infrastructure.chunk

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ChunkTicketType
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk
import java.util.ArrayDeque
import java.util.LinkedHashMap

/**
 * Infrastructure-owned chunk load driver.
 *
 * Current contract: the DRIVER is a *broker*.
 *
 * - RENEWAL and SCANNER express *interest* (desired chunks).
 * - The DRIVER translates interest into temporary tickets (bounded + prioritized).
 * - The engine decides *when* chunks load.
 * - When a chunk becomes available, the DRIVER forwards it to consumers and removes its ticket.
 *
 * The DRIVER does not attempt to "force" chunk availability beyond providing tickets.
 * It avoids spam by enforcing a hard cap on outstanding tickets and by prioritizing renewal.
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

    private enum class TicketSource { RENEWAL, SCAN }

    private var renewalProvider: ChunkLoadProvider? = null
    private var scanProvider: ChunkLoadProvider? = null
    private val listeners = mutableListOf<ChunkAvailabilityListener>()
    private var server: MinecraftServer? = null

    private var tickCounter: Long = 0
    private var lastStateLogTick: Long = 0

    private data class TicketMeta(
        val source: TicketSource,
        val issuedAtTick: Long,
    )

    /**
     * Outstanding tickets currently registered by the DRIVER.
     *
     * Note: tickets are advisory; we keep them alive until the chunk is observed as usable,
     * then remove them immediately to avoid retention and scheduler state growth.
     */
    private val tickets: MutableMap<ChunkRef, TicketMeta> = LinkedHashMap()

    private data class PendingLoad(
        val firstSeenTick: Long,
    )

    /**
     * Chunks that have emitted an engine load signal (or were already available),
     * but have not yet been forwarded to consumers (bounded per tick).
     */
    private val pendingLoads: LinkedHashMap<ChunkRef, PendingLoad> = LinkedHashMap()

    private val pendingUnloads: ArrayDeque<Pair<ServerWorld, ChunkPos>> = ArrayDeque()

    /* ---------------------------------------------------------------------
     * Lifecycle
     * ------------------------------------------------------------------ */

    fun attach(server: MinecraftServer) {
        this.server = server
        activeInstance = this

        tickCounter = 0
        lastStateLogTick = 0

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

    fun registerRenewalProvider(provider: ChunkLoadProvider) {
        renewalProvider = provider
    }

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

        // Refill tickets up to cap (renewal first; scan only when renewal has no desired chunks).
        replenishTickets(s)

        logState()
    }

    /* ---------------------------------------------------------------------
     * Engine signals
     * ------------------------------------------------------------------ */

    private fun onEngineChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        pendingLoads.putIfAbsent(
            ChunkRef(world.registryKey, chunk.pos),
            PendingLoad(firstSeenTick = tickCounter),
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

        // Engine callbacks may mutate [pendingLoads] while we are ticking; iterate over a snapshot.
        val snapshot = pendingLoads.entries.toList()

        for ((ref, pending) in snapshot) {
            if (forwarded >= MementoConstants.CHUNK_LOAD_MAX_FORWARDED_PER_TICK) break

            val world = server.getWorld(ref.dimension)
            if (world == null) {
                MementoLog.warn(MementoConcept.DRIVER,
                    "pending load dropped; world missing dim={} pos={}",
                    ref.dimension.value,
                    ref.pos
                )
                toRemove += ref
                continue
            }

            val chunk = world.chunkManager.getWorldChunk(ref.pos.x, ref.pos.z, false)
            if (chunk == null) {
                // Engine signalled a load, but the chunk is not yet usable as a WorldChunk.
                // Keep waiting; the ticket (if any) remains as our standing interest.
                val age = tickCounter - pending.firstSeenTick
                
                continue
            }

            listeners.forEach { it.onChunkLoaded(world, chunk) }
            forwarded++
            toRemove += ref

            // If this chunk was ticketed by the DRIVER, release our interest immediately.
            if (tickets.remove(ref) != null) {
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
     * Ticket replenishment
     * ------------------------------------------------------------------ */

    private fun replenishTickets(server: MinecraftServer) {
        val capacity = MementoConstants.CHUNK_LOAD_MAX_OUTSTANDING_TICKETS - tickets.size
        if (capacity <= 0) return

        // Determine whether renewal has any remaining intent.
        val renewalHasIntent = renewalProvider?.desiredChunks()?.iterator()?.hasNext() == true

        val provider = if (renewalHasIntent) renewalProvider else scanProvider
        val source = if (renewalHasIntent) TicketSource.RENEWAL else TicketSource.SCAN

        if (provider == null) return

        var issued = 0

        val it = provider.desiredChunks().iterator()
        while (it.hasNext() && issued < capacity) {
            val ref = it.next()

            // Avoid re-issuing tickets and avoid duplicating pending work.
            if (tickets.containsKey(ref)) continue
            if (pendingLoads.containsKey(ref)) continue

            val world = server.getWorld(ref.dimension) ?: continue

            // If already available, push it through the same forwarding pipeline (no ticket).
            val alreadyLoaded = world.chunkManager.getWorldChunk(ref.pos.x, ref.pos.z, false)
            if (alreadyLoaded != null) {
                pendingLoads.putIfAbsent(ref, PendingLoad(firstSeenTick = tickCounter))
                continue
            }

            world.chunkManager.addTicket(ChunkTicketType.FORCED, ref.pos, MementoConstants.CHUNK_LOAD_TICKET_RADIUS)
            tickets[ref] = TicketMeta(source = source, issuedAtTick = tickCounter)
            issued++
        }

        if (issued > 0) {
            MementoLog.debug(MementoConcept.DRIVER,
                "tickets issued source={} count={} outstanding={}",
                source,
                issued,
                tickets.size
            )
        }
    }

    /* ---------------------------------------------------------------------
     * Observability
     * ------------------------------------------------------------------ */

    private fun logState() {
        if ((tickCounter - lastStateLogTick) < MementoConstants.CHUNK_LOAD_STATE_LOG_EVERY_TICKS) return
        lastStateLogTick = tickCounter

        MementoLog.debug(MementoConcept.DRIVER,
            "tick={} outstandingTickets={} pendingLoads={} headPending={}",
            tickCounter,
            tickets.size,
            pendingLoads.size,
            pendingLoads.entries.firstOrNull()?.key
        )
    }
}
