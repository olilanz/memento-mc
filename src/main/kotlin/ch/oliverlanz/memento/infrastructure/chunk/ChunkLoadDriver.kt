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
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Infrastructure-owned chunk load driver.
 *
 * Broker contract:
 * - RENEWAL and SCANNER express *interest* (desired chunks).
 * - The DRIVER translates interest into temporary tickets (bounded + prioritized).
 * - The engine decides *when* chunks load.
 * - When a chunk becomes safely accessible on the tick thread, the DRIVER propagates it to consumers.
 *
 * Threading:
 * - Engine callbacks may arrive on non-tick threads.
 * - The DRIVER never propagates chunk availability on an engine callback thread.
 * - All propagation and engine access happens on the tick thread.
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

        private const val DRIVER_TICKET_NAME: String = "memento_driver"
    }

    private var renewalProvider: ChunkLoadProvider? = null
    private var scanProvider: ChunkLoadProvider? = null
    private val listeners = mutableListOf<ChunkAvailabilityListener>()
    private var server: MinecraftServer? = null

    @Volatile private var tickCounter: Long = 0
    private var lastStateLogTick: Long = 0

    // Register is the sole bookkeeping structure for chunk load lifecycle.
    private val register = ChunkLoadRegister()

    // Engine unload signals can arrive off-thread; queue them and forward on tick thread.
    private data class UnloadEvent(val dim: net.minecraft.registry.RegistryKey<net.minecraft.world.World>, val pos: ChunkPos)
    private val pendingUnloads = ConcurrentLinkedQueue<UnloadEvent>()

    // Aggregated observability
    private var completedPendingPruneExpiredCount: Long = 0

    /* ---------------------------------------------------------------------
     * Lifecycle
     * ------------------------------------------------------------------ */

    fun attach(server: MinecraftServer) {
        this.server = server
        activeInstance = this

        tickCounter = 0
        lastStateLogTick = 0
        completedPendingPruneExpiredCount = 0

        register.clear()
        pendingUnloads.clear()
    }

    fun detach() {
        if (activeInstance === this) activeInstance = null

        val s = server
        if (s != null) {
            // Remove any remaining tickets we own.
            val views = register.allEntriesSnapshot()
            for (v in views) {
                val ticket = v.ticketName ?: continue
                val world = s.getWorld(v.chunk.dimension) ?: continue
                world.chunkManager.removeTicket(ChunkTicketType.FORCED, v.chunk.pos, MementoConstants.CHUNK_LOAD_TICKET_RADIUS)
            }
        }

        server = null
        register.clear()
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

        // 1) Recompute demand snapshot (renewal first, then scanner).
        register.beginDemandSnapshot()
        markDesiredFromProvider(renewalProvider, ChunkLoadRegister.RequestSource.RENEWAL, nowTick = tickCounter)
        markDesiredFromProvider(scanProvider, ChunkLoadRegister.RequestSource.SCANNER, nowTick = tickCounter)

        // 2) Expire and prune (outside-lock engine operations handled here).
        expireCompletedPendingPrune(s)
        pruneUndesired(s)

        // 3) Poll for "awaiting full load" chunks (throttled).
        if (tickCounter % MementoConstants.CHUNK_LOAD_AWAITING_FULL_LOAD_CHECK_EVERY_TICKS == 0L) {
            checkAwaitingFullLoad(s)
        }

        // 4) Propagate ready chunks (bounded) on tick thread.
        propagateReadyChunks(s)

        // 5) Issue tickets up to cap.
        issueTicketsUpToCap(s)

        // 6) Forward unload events (tick thread).
        forwardPendingUnloads(s)

        logState()
    }

    /* ---------------------------------------------------------------------
     * Engine signals (may be off-thread)
     * ------------------------------------------------------------------ */

    private fun onEngineChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        // Record the observation into the register. No propagation here.
        register.recordEngineLoadObserved(
            ChunkRef(world.registryKey, chunk.pos),
            nowTick = tickCounter,
        )
    }

    private fun onEngineChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
        pendingUnloads.add(UnloadEvent(world.registryKey, pos))
    }

    /* ---------------------------------------------------------------------
     * Demand
     * ------------------------------------------------------------------ */

    private fun markDesiredFromProvider(provider: ChunkLoadProvider?, source: ChunkLoadRegister.RequestSource, nowTick: Long) {
        if (provider == null) return
        // Provider implementations are expected to be bounded.
        for (ref in provider.desiredChunks()) {
            register.markDesired(ref, source, nowTick)
            register.acknowledgeRequest(ref, source, nowTick)
        }
    }

    /* ---------------------------------------------------------------------
     * Pruning / expiry
     * ------------------------------------------------------------------ */

    private fun pruneUndesired(server: MinecraftServer) {
        val result = register.pruneUndesired(nowTick = tickCounter)

        for (ref in result.ticketsToRemove) {
            removeTicket(server, ref)
        }
    }

    private fun expireCompletedPendingPrune(server: MinecraftServer) {
        val result = register.expireCompletedPendingPrune(
            nowTick = tickCounter,
            expireAfterTicks = MementoConstants.CHUNK_LOAD_COMPLETED_PENDING_PRUNE_EXPIRE_TICKS,
        )
        if (result.expiredEntries.isNotEmpty()) {
            completedPendingPruneExpiredCount += result.expiredEntries.size.toLong()
        }
        for (ref in result.ticketsToRemove) {
            removeTicket(server, ref)
        }
    }

    private fun removeTicket(server: MinecraftServer, ref: ChunkRef) {
        val world = server.getWorld(ref.dimension) ?: return
        world.chunkManager.removeTicket(ChunkTicketType.FORCED, ref.pos, MementoConstants.CHUNK_LOAD_TICKET_RADIUS)
    }

    /* ---------------------------------------------------------------------
     * Awaiting full load checks (tick thread)
     * ------------------------------------------------------------------ */

    private fun checkAwaitingFullLoad(server: MinecraftServer) {
        val batch = register.takeAwaitingFullLoadBatch(MementoConstants.CHUNK_LOAD_AWAITING_FULL_LOAD_MAX_PER_CYCLE)
        for (ref in batch) {
            val world = server.getWorld(ref.dimension) ?: continue
            val chunk = world.chunkManager.getWorldChunk(ref.pos.x, ref.pos.z, false) ?: continue
            // If accessible, mark as available for propagation.
            register.onChunkAvailable(ref, nowTick = tickCounter)
        }
    }

    /* ---------------------------------------------------------------------
     * Propagation (tick thread)
     * ------------------------------------------------------------------ */

    private fun propagateReadyChunks(server: MinecraftServer) {
        var forwarded = 0

        while (forwarded < MementoConstants.CHUNK_LOAD_MAX_FORWARDED_PER_TICK) {
            val ref = register.pollPropagationReady() ?: break
            val world = server.getWorld(ref.dimension) ?: run {
                register.remove(ref)
                continue
            }

            val chunk = world.chunkManager.getWorldChunk(ref.pos.x, ref.pos.z, false)
            if (chunk == null) {
                // Should be rare (we only enqueue once accessible). Re-queue via awaiting full load.
                register.synthesizeLoadObserved(ref, nowTick = tickCounter)
                continue
            }

            // Transition + propagate outside of register lock.
            register.beginPropagation(ref, nowTick = tickCounter)
            listeners.forEach { it.onChunkLoaded(world, chunk) }
            register.completePropagation(ref, nowTick = tickCounter)
            forwarded++

            // Immediate reconcile: if not desired in current snapshot, prune now.
            if (!register.isDesired(ref)) {
                val ticket = register.ticketName(ref)
                if (ticket != null) {
                    world.chunkManager.removeTicket(ChunkTicketType.FORCED, ref.pos, MementoConstants.CHUNK_LOAD_TICKET_RADIUS)
                }
                register.remove(ref)
            }
        }
    }

    /* ---------------------------------------------------------------------
     * Ticket issuance (tick thread)
     * ------------------------------------------------------------------ */

    private fun issueTicketsUpToCap(server: MinecraftServer) {
        val ticketCount = register.allEntriesSnapshot().count { it.ticketName != null }
        val capacity = MementoConstants.CHUNK_LOAD_MAX_OUTSTANDING_TICKETS - ticketCount
        if (capacity <= 0) return

        // Issue tickets in a single pass; register internally filters to REQUESTED + desired.
        val candidates = register.takeTicketCandidates(capacity)
        if (candidates.isEmpty()) return

        var issued = 0
        for (ref in candidates) {
            val world = server.getWorld(ref.dimension) ?: continue

            // If already loaded, synthesize observation and let the normal pipeline handle it.
            val alreadyLoaded = world.chunkManager.getWorldChunk(ref.pos.x, ref.pos.z, false)
            if (alreadyLoaded != null) {
                register.synthesizeLoadObserved(ref, nowTick = tickCounter)
                continue
            }

            // Normal ticket path.
            register.setNotLoadedVerified(ref, nowTick = tickCounter)
            world.chunkManager.addTicket(ChunkTicketType.FORCED, ref.pos, MementoConstants.CHUNK_LOAD_TICKET_RADIUS)
            register.issueTicket(ref, DRIVER_TICKET_NAME, nowTick = tickCounter)
            issued++
        }

        if (issued > 0) {
            MementoLog.debug(
                MementoConcept.DRIVER,
                "tickets issued count={} outstanding={}",
                issued,
                register.allEntriesSnapshot().count { it.ticketName != null }
            )
        }
    }

    /* ---------------------------------------------------------------------
     * Unloads (tick thread)
     * ------------------------------------------------------------------ */

    private fun forwardPendingUnloads(server: MinecraftServer) {
        while (true) {
            val ev = pendingUnloads.poll() ?: break
            val world = server.getWorld(ev.dim) ?: continue
            listeners.forEach { it.onChunkUnloaded(world, ev.pos) }
        }
    }

    /* ---------------------------------------------------------------------
     * Observability
     * ------------------------------------------------------------------ */

    private fun logState() {
        if ((tickCounter - lastStateLogTick) < MementoConstants.CHUNK_LOAD_STATE_LOG_EVERY_TICKS) return
        lastStateLogTick = tickCounter

        val s = register.stateSnapshot()
        MementoLog.debug(
            MementoConcept.DRIVER,
            "tick={} total={} desiredAwaiting={} queue={} tickets={} states=requested:{} verified:{} ticketed:{} observed:{} observedNotAwaiting:{} available:{} propagating:{} completedPendingPrune:{} expiredCompletedPendingPrune={}",
            tickCounter,
            s.total,
            s.awaitingFullLoad,
            s.propagationQueue,
            register.allEntriesSnapshot().count { it.ticketName != null },
            s.requested,
            s.verifiedNotLoaded,
            s.ticketIssued,
            s.engineLoadObserved,
            s.observedNotAwaiting,
            s.chunkAvailable,
            s.propagating,
            s.completedPendingPrune,
            completedPendingPruneExpiredCount,
        )
    }
}
