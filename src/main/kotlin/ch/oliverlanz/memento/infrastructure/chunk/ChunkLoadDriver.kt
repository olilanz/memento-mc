package ch.oliverlanz.memento.infrastructure.chunk

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.util.concurrent.ConcurrentLinkedQueue
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ChunkTicketType
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk

/**
 * Infrastructure-owned chunk load driver.
 *
 * Responsibility boundaries:
 * - Providers (RENEWAL, SCANNER) express *desire* only (which chunks would be useful).
 * - The DRIVER is the sole owner of engine subscriptions and ticket pressure.
 * - The engine decides *when* chunks load and whether they ever reach a stable FULLY_LOADED state.
 *
 * Pressure balancing:
 * - The DRIVER arbitrates pressure across three origins: 1) renewal desire 2) scanner desire 3)
 * unsolicited engine loads (external pressure)
 * - Renewal is always prioritized over scanning.
 * - Under active unsolicited pressure (unsolicited engine chunk loads), the driver backs off
 * entirely and does not issue new tickets. Already-issued tickets remain in place.
 *
 * Outcomes:
 * - When a chunk becomes safely accessible on the tick thread, the DRIVER propagates it.
 * - If bounded effort expires before accessibility, the DRIVER emits an expiry outcome (no chunk
 * attached) and releases all pressure for that attempt.
 *
 * Threading:
 * - Engine callbacks may arrive on non-tick threads.
 * - The DRIVER never propagates outcomes on an engine callback thread.
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

    /** Last tick where a purely unsolicited engine chunk load was observed. */
    @Volatile private var lastUnsolicitedLoadObservedTick: Long = -1

    /** Cached state to emit a single log line when back-off engages or releases. */
    private var wasAmbientPressureActive: Boolean = false

    // Register is the sole bookkeeping structure for chunk load lifecycle.
    private val register = ChunkLoadRegister()

    // Engine unload signals can arrive off-thread; queue them and forward on tick thread.
    private data class UnloadEvent(
            val dim: net.minecraft.registry.RegistryKey<net.minecraft.world.World>,
            val pos: ChunkPos
    )
    private val pendingUnloads = ConcurrentLinkedQueue<UnloadEvent>()

    // Engine load signals can arrive off-thread; if we observe a chunk without a ticket,
    // we adopt it by attaching a ticket on the tick thread to increase probability of FULLY_LOADED.
    private val pendingAdoptions = ConcurrentLinkedQueue<ChunkRef>()

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
                world.chunkManager.removeTicket(
                        ChunkTicketType.FORCED,
                        v.chunk.pos,
                        MementoConstants.CHUNK_LOAD_TICKET_RADIUS
                )
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
        markDesiredFromProvider(
                renewalProvider,
                ChunkLoadRegister.RequestSource.RENEWAL,
                nowTick = tickCounter
        )
        markDesiredFromProvider(
                scanProvider,
                ChunkLoadRegister.RequestSource.SCANNER,
                nowTick = tickCounter
        )

        // 2) Expire and prune (outside-lock engine operations handled here).
        expireCompletedPendingPrune(s)
        expireTicketIssuedWithoutObservation(s)
        expireAwaitingFullLoad(s)
        pruneUndesired(s)

        // 3) Adopt observed engine loads (attach tickets) to avoid stalls.
        adoptObservedLoads(s)

        // 4) Poll for "awaiting full load" chunks (throttled).
        if (tickCounter % MementoConstants.CHUNK_LOAD_AWAITING_FULL_LOAD_CHECK_EVERY_TICKS == 0L) {
            checkAwaitingFullLoad(s)
        }

        // 5) Propagate ready chunks (bounded) on tick thread.
        propagateReadyChunks(s)

        // 6) Issue tickets up to cap.
        issueTicketsUpToCap(s)

        // 7) Forward unload events (tick thread).
        forwardPendingUnloads(s)

        logState()
    }

    /* ---------------------------------------------------------------------
     * Engine signals (may be off-thread)
     * ------------------------------------------------------------------ */

    private fun onEngineChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        val ref = ChunkRef(world.registryKey, chunk.pos)
        // Record the observation into the register. No propagation here.
        // We capture whether an entry existed before this callback so we can attribute pressure
        // only to *new* external disturbances (and not keep refreshing the back-off window due
        // to engine ripples around already-adopted unsolicited entries).
        val existedBefore = register.containsEntry(ref)
        val obs = register.recordEngineLoadObserved(ref, nowTick = tickCounter)
        // Pressure attribution:
        // The engine may load neighboring chunks as a side effect of driver demand (scanner or
        // renewal). Such spillover must not be counted as external pressure.
        val spilloverFromSolicitedDemand = isAdjacentToSolicitedEntry(ref)
        if (obs.isUnsolicited && !spilloverFromSolicitedDemand && !existedBefore) {
            lastUnsolicitedLoadObservedTick = tickCounter
        }

        // IMPORTANT: do not ticket unsolicited engine loads.
        // Ticketing externally observed loads can amplify engine spillover and cause uncontrolled
        // world growth. Unsolicited observations are tracked best-effort and may expire without
        // ever reaching FULLY_LOADED.
    }

    private fun onEngineChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
        pendingUnloads.add(UnloadEvent(world.registryKey, pos))
    }

    /* ---------------------------------------------------------------------
     * Demand
     * ------------------------------------------------------------------ */

    private fun markDesiredFromProvider(
            provider: ChunkLoadProvider?,
            source: ChunkLoadRegister.RequestSource,
            nowTick: Long
    ) {
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
        val result =
                register.expireCompletedPendingPrune(
                        nowTick = tickCounter,
                        expireAfterTicks =
                                MementoConstants.CHUNK_LOAD_COMPLETED_PENDING_PRUNE_EXPIRE_TICKS,
                )
        if (result.expiredEntries.isNotEmpty()) {
            completedPendingPruneExpiredCount += result.expiredEntries.size.toLong()
        }
        for (ref in result.ticketsToRemove) {
            removeTicket(server, ref)
        }
    }

    private fun expireTicketIssuedWithoutObservation(server: MinecraftServer) {
        val result =
                register.expireTicketIssuedWithoutObservation(
                        nowTick = tickCounter,
                        expireAfterTicks = MementoConstants.CHUNK_LOAD_TICKET_ISSUED_TIMEOUT_TICKS,
                )

        if (result.expiredEntries.isNotEmpty()) {
            MementoLog.debug(
                    MementoConcept.DRIVER,
                    "ticket-issued expired count={}",
                    result.expiredEntries.size,
            )
        }

        for (ref in result.ticketsToRemove) {
            removeTicket(server, ref)
        }

        // Propagate best-effort expiry outcomes (no chunk attached).
        // Consumers (notably the scanner) can mark scanTick and move forward.
        for (ref in result.expiredEntries) {
            val world = server.getWorld(ref.dimension) ?: continue
            listeners.forEach { it.onChunkLoadExpired(world, ref.pos) }
        }
    }

    private fun expireAwaitingFullLoad(server: MinecraftServer) {
        val result =
                register.expireAwaitingFullLoad(
                        nowTick = tickCounter,
                        expireAfterTicks =
                                MementoConstants.CHUNK_LOAD_AWAITING_FULL_LOAD_TIMEOUT_TICKS,
                )

        if (result.expiredEntries.isNotEmpty()) {
            MementoLog.debug(
                    MementoConcept.DRIVER,
                    "awaiting-full-load expired count={}",
                    result.expiredEntries.size,
            )
        }
        for (ref in result.ticketsToRemove) {
            removeTicket(server, ref)
        }

        // Propagate best-effort expiry outcomes (no chunk attached).
        // This allows consumers (notably the scanner) to make forward progress even when
        // the engine never reaches a stable FULLY_LOADED state for this load attempt.
        for (ref in result.expiredEntries) {
            val world = server.getWorld(ref.dimension) ?: continue
            listeners.forEach { it.onChunkLoadExpired(world, ref.pos) }
        }
    }

    private fun removeTicket(server: MinecraftServer, ref: ChunkRef) {
        val world = server.getWorld(ref.dimension) ?: return
        world.chunkManager.removeTicket(
                ChunkTicketType.FORCED,
                ref.pos,
                MementoConstants.CHUNK_LOAD_TICKET_RADIUS
        )
    }

    /* ---------------------------------------------------------------------
     * Adoption (tick thread)
     * ------------------------------------------------------------------ */

    private fun adoptObservedLoads(server: MinecraftServer) {
        val ticketCount = register.allEntriesSnapshot().count { it.ticketName != null }
        var capacity = MementoConstants.CHUNK_LOAD_MAX_OUTSTANDING_TICKETS - ticketCount
        if (capacity <= 0) return

        while (capacity > 0) {
            val ref = pendingAdoptions.poll() ?: break
            if (register.hasTicket(ref)) continue
            val world = server.getWorld(ref.dimension) ?: continue

            // Attach a ticket to improve probability the load reaches FULLY_LOADED.
            world.chunkManager.addTicket(
                    ChunkTicketType.FORCED,
                    ref.pos,
                    MementoConstants.CHUNK_LOAD_TICKET_RADIUS
            )
            register.attachObservedTicket(ref, DRIVER_TICKET_NAME, nowTick = tickCounter)
            capacity--
        }
    }
    /* ---------------------------------------------------------------------
     * Awaiting full load checks (tick thread)
     * ------------------------------------------------------------------ */

    private fun checkAwaitingFullLoad(server: MinecraftServer) {
        val batch =
                register.takeAwaitingFullLoadBatch(
                        MementoConstants.CHUNK_LOAD_AWAITING_FULL_LOAD_MAX_PER_CYCLE
                )
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
            val world =
                    server.getWorld(ref.dimension)
                            ?: run {
                                register.remove(ref)
                                continue
                            }

            val chunk = world.chunkManager.getWorldChunk(ref.pos.x, ref.pos.z, false)
            if (chunk == null) {
                // Should be rare (we only enqueue once accessible). Re-queue via awaiting full
                // load.
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
                    world.chunkManager.removeTicket(
                            ChunkTicketType.FORCED,
                            ref.pos,
                            MementoConstants.CHUNK_LOAD_TICKET_RADIUS
                    )
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

        val ambientPressureActive =
                (lastUnsolicitedLoadObservedTick >= 0 &&
                        (tickCounter - lastUnsolicitedLoadObservedTick) <=
                                MementoConstants.CHUNK_LOAD_UNSOLICITED_PRESSURE_WINDOW_TICKS)

        if (ambientPressureActive != wasAmbientPressureActive) {
            wasAmbientPressureActive = ambientPressureActive
            if (ambientPressureActive) {
                MementoLog.info(
                        MementoConcept.DRIVER,
                        "back-off engaged: unsolicited engine pressure observed; ticketing paused lastUnsolicitedTick={} windowTicks={}",
                        lastUnsolicitedLoadObservedTick,
                        MementoConstants.CHUNK_LOAD_UNSOLICITED_PRESSURE_WINDOW_TICKS,
                )
            } else {
                MementoLog.info(
                        MementoConcept.DRIVER,
                        "back-off released: unsolicited pressure quiet; ticketing resumed quietForTicks={}",
                        (tickCounter - lastUnsolicitedLoadObservedTick),
                )
            }
        }

        // Full back-off under ambient pressure:
        // unsolicited engine chunk loads indicate external demand (players, other systems, etc.).
        // In that situation, the driver must not compete by issuing new tickets. External pressure
        // is as
        // good as driver-induced pressure for scan convergence.
        if (ambientPressureActive) return

        // Issue tickets in a single pass.
        // Policy:
        // - Renewal desire is prioritized over scanner desire.
        val candidates = register.takeTicketCandidatesPrioritized(capacity)
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
            world.chunkManager.addTicket(
                    ChunkTicketType.FORCED,
                    ref.pos,
                    MementoConstants.CHUNK_LOAD_TICKET_RADIUS
            )
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
        if ((tickCounter - lastStateLogTick) < MementoConstants.CHUNK_LOAD_STATE_LOG_EVERY_TICKS)
                return
        lastStateLogTick = tickCounter

        val s = register.stateSnapshot()
        val ticketCount = register.allEntriesSnapshot().count { it.ticketName != null }
        val desiredCount = register.allEntriesSnapshot().count { it.desiredBy.isNotEmpty() }
        val ambientPressureActive =
                (lastUnsolicitedLoadObservedTick >= 0 &&
                        (tickCounter - lastUnsolicitedLoadObservedTick) <=
                                MementoConstants.CHUNK_LOAD_UNSOLICITED_PRESSURE_WINDOW_TICKS)
        val quietFor =
                if (lastUnsolicitedLoadObservedTick >= 0)
                        (tickCounter - lastUnsolicitedLoadObservedTick)
                else -1

        MementoLog.debug(
                MementoConcept.DRIVER,
                "tick={} entries={} demand(desired={} queue={}) pressure(tickets={} backOff={} lastUnsolicitedTick={} quietForTicks={}) pipeline(requested={} verified={} ticketed={} observed={} awaitingFullLoad={} observedNotAwaiting={} ready={} readyQueue={} propagating={} completedPendingPrune={} expiredPendingPrune={})",
                tickCounter,
                s.total,
                desiredCount,
                s.propagationQueue,
                ticketCount,
                ambientPressureActive,
                lastUnsolicitedLoadObservedTick,
                quietFor,
                s.requested,
                s.verifiedNotLoaded,
                s.ticketIssued,
                s.engineLoadObserved,
                s.awaitingFullLoad,
                s.observedNotAwaiting,
                s.chunkAvailable,
                s.propagationQueue,
                s.propagating,
                s.completedPendingPrune,
                completedPendingPruneExpiredCount,
        )
    }

    /**
     * Returns true if this engine load is plausibly spillover from *solicited* driver demand.
     *
     * The driver expresses demand (scanner/renewal) for individual chunks. The engine may load
     * neighboring chunks as a side effect of fulfilling that demand.
     *
     * These spillover loads are still causally induced by driver demand and must not be counted as
     * external/unsolicited pressure (back-off signal).
     *
     * IMPORTANT:
     * - We must not base this on "ticket present": tickets can be attached/released while the
     *   engine is still delivering neighbor loads.
     * - We must not base this on "entry exists": the register can also contain entries adopted
     *   from unsolicited engine loads.
     */
    private fun isAdjacentToSolicitedEntry(ref: ChunkRef): Boolean {
        if (register.hasSolicitedEntry(ref)) return true
        val cx = ref.pos.x
        val cz = ref.pos.z
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val n = ChunkRef(ref.dimension, ChunkPos(cx + dx, cz + dz))
                if (register.hasSolicitedEntry(n)) return true
            }
        }
        return false
    }
}
