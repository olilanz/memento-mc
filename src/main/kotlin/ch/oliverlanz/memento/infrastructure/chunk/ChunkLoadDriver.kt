package ch.oliverlanz.memento.infrastructure.chunk

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkMetadataFact
import ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance
import ch.oliverlanz.memento.domain.worldmap.ChunkSignals
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.lang.Math
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
 * - Providers (RENEWAL) express *desire* only (which chunks would be useful).
 * - The DRIVER is the sole owner of engine subscriptions and ticket pressure.
 * - The engine decides *when* chunks load and whether they ever reach a stable FULLY_LOADED state.
 *
 * Pressure balancing:
 * - The DRIVER arbitrates pressure across two origins: 1) planned renewal demand 2) ambient
 *   engine loads.
 * - Ambient observations are tracked for lifecycle telemetry, while ticket issuance for planned
 *   demand remains bounded by explicit capacity and ticket caps.
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
    private val listeners = mutableListOf<ChunkAvailabilityListener>()
    private var server: MinecraftServer? = null

    @Volatile private var tickCounter: Long = 0

    // Register is the sole bookkeeping structure for chunk load lifecycle.
    private val register = ChunkLoadRegister()

    // Engine unload signals can arrive off-thread; queue them and forward on tick thread.
    private data class UnloadEvent(
            val dim: net.minecraft.registry.RegistryKey<net.minecraft.world.World>,
            val pos: ChunkPos
    )
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

    fun registerConsumer(listener: ChunkAvailabilityListener) {
        listeners += listener
    }

    /* ---------------------------------------------------------------------
     * Driver tick
     * ------------------------------------------------------------------ */

    fun tick() {
        tickCounter++

        val s = server ?: return

        // 1) Recompute demand snapshot.
        register.beginDemandSnapshot()
        markDesiredFromProvider(
                renewalProvider,
                ChunkLoadRegister.RequestSource.RENEWAL,
                nowTick = tickCounter
        )

        // 2) Expire and prune (outside-lock engine operations handled here).
        expireCompletedPendingPrune(s)
        expireTicketIssuedWithoutObservation(s)
        expireAwaitingFullLoad(s)
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
    }

    /* ---------------------------------------------------------------------
     * Engine signals (may be off-thread)
     * ------------------------------------------------------------------ */

    private fun onEngineChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        val ref = ChunkRef(world.registryKey, chunk.pos)
        // Record the observation into the register. No propagation here.
        register.recordEngineLoadObserved(ref, nowTick = tickCounter)

        // IMPORTANT: do not ticket ambient engine loads.
        // Ticketing externally observed loads can amplify engine spillover and cause uncontrolled
        // world growth. Ambient observations are tracked best-effort and may expire without
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
        val renewalRequestedAtExpiryStart = renewalRequestedChunksSnapshot()
        val result =
                register.expireTicketIssuedWithoutObservation(
                        nowTick = tickCounter,
                        expireAfterTicks = MementoConstants.CHUNK_LOAD_TICKET_ISSUED_TIMEOUT_TICKS,
                )

        if (result.expiredEntries.isNotEmpty()) {
            MementoLog.debug(
                    MementoConcept.DRIVER,
                    "expiry batch ticket-issued count={} ticketsRemoved={} source(scanner={} renewal={} ambient={}) ticketStatus(ticketed={} unticketed={})",
                    result.expiredEntries.size,
                    result.ticketsToRemove.size,
                    result.telemetry.source.scanner,
                    result.telemetry.source.renewal,
                    result.telemetry.source.ambient,
                    result.telemetry.ticketStatus.ticketed,
                    result.telemetry.ticketStatus.unticketed,
            )

            if (result.telemetry.source.renewal > 0) {
                MementoLog.info(
                        MementoConcept.RENEWAL,
                        "renewal lifecycle expiry summary phase=ticket-issued count={} ticketsRemoved={} source(scanner={} renewal={} ambient={}) ticketStatus(ticketed={} unticketed={})",
                        result.telemetry.source.renewal,
                        result.ticketsToRemove.size,
                        result.telemetry.source.scanner,
                        result.telemetry.source.renewal,
                        result.telemetry.source.ambient,
                        result.telemetry.ticketStatus.ticketed,
                        result.telemetry.ticketStatus.unticketed,
                )

                for (ref in result.expiredEntries) {
                    if (!renewalRequestedAtExpiryStart.contains(ref)) continue
                    MementoLog.debug(
                            MementoConcept.RENEWAL,
                            "renewal lifecycle expiry detail phase=ticket-issued chunk=({}, {}) world={}",
                            ref.pos.x,
                            ref.pos.z,
                            ref.dimension.value,
                    )
                }
            }
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
        val renewalRequestedAtExpiryStart = renewalRequestedChunksSnapshot()
        val result =
                register.expireAwaitingFullLoad(
                        nowTick = tickCounter,
                        expireAfterTicks =
                                MementoConstants.CHUNK_LOAD_AWAITING_FULL_LOAD_TIMEOUT_TICKS,
                )

        if (result.expiredEntries.isNotEmpty()) {
            MementoLog.debug(
                    MementoConcept.DRIVER,
                    "expiry batch awaiting-full-load count={} ticketsRemoved={} source(scanner={} renewal={} ambient={}) ticketStatus(ticketed={} unticketed={})",
                    result.expiredEntries.size,
                    result.ticketsToRemove.size,
                    result.telemetry.source.scanner,
                    result.telemetry.source.renewal,
                    result.telemetry.source.ambient,
                    result.telemetry.ticketStatus.ticketed,
                    result.telemetry.ticketStatus.unticketed,
            )

            if (result.telemetry.source.renewal > 0) {
                MementoLog.info(
                        MementoConcept.RENEWAL,
                        "renewal lifecycle expiry summary phase=awaiting-full-load count={} ticketsRemoved={} source(scanner={} renewal={} ambient={}) ticketStatus(ticketed={} unticketed={})",
                        result.telemetry.source.renewal,
                        result.ticketsToRemove.size,
                        result.telemetry.source.scanner,
                        result.telemetry.source.renewal,
                        result.telemetry.source.ambient,
                        result.telemetry.ticketStatus.ticketed,
                        result.telemetry.ticketStatus.unticketed,
                )

                for (ref in result.expiredEntries) {
                    if (!renewalRequestedAtExpiryStart.contains(ref)) continue
                    MementoLog.debug(
                            MementoConcept.RENEWAL,
                            "renewal lifecycle expiry detail phase=awaiting-full-load chunk=({}, {}) world={}",
                            ref.pos.x,
                            ref.pos.z,
                            ref.dimension.value,
                    )
                }
            }
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
        var renewalMetadataReadFailures = 0

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
                val desiredSources = register.desiredSources(ref)
                if (desiredSources.contains(ChunkLoadRegister.RequestSource.RENEWAL)) {
                    renewalMetadataReadFailures++
                    MementoLog.debug(
                            MementoConcept.RENEWAL,
                            "renewal metadata-read failure detail phase=propagation chunk=({}, {}) world={} action=requeue-awaiting-full-load",
                            ref.pos.x,
                            ref.pos.z,
                            ref.dimension.value,
                    )
                }

                // Should be rare (we only enqueue once accessible). Re-queue via awaiting full
                // load.
                register.synthesizeLoadObserved(ref, nowTick = tickCounter)
                continue
            }

            // Transition + propagate outside of register lock.
            register.beginPropagation(ref, nowTick = tickCounter)
            val desiredSources = register.desiredSources(ref)
            val source =
                    if (desiredSources.contains(ChunkLoadRegister.RequestSource.RENEWAL))
                            ChunkScanProvenance.ENGINE_FALLBACK
                    else ChunkScanProvenance.ENGINE_AMBIENT
            val fact =
                    ChunkMetadataFact(
                            key =
                                    ChunkKey(
                                            world = world.registryKey,
                                            regionX = Math.floorDiv(chunk.pos.x, 32),
                                            regionZ = Math.floorDiv(chunk.pos.z, 32),
                                            chunkX = chunk.pos.x,
                                            chunkZ = chunk.pos.z,
                                    ),
                            source = source,
                            unresolvedReason = null,
                            signals =
                                    ChunkSignals(
                                            inhabitedTimeTicks = chunk.inhabitedTime,
                                            lastUpdateTicks = null,
                                            surfaceY = null,
                                            biomeId = null,
                                            isSpawnChunk = false,
                                    ),
                            scanTick = world.time,
                    )
            listeners.forEach { it.onChunkMetadata(world, fact) }
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

        if (renewalMetadataReadFailures > 0) {
            MementoLog.info(
                    MementoConcept.RENEWAL,
                    "renewal metadata-read failure summary phase=propagation count={} action=requeue-awaiting-full-load",
                    renewalMetadataReadFailures,
            )
        }
    }

    /* ---------------------------------------------------------------------
     * Ticket issuance (tick thread)
     * ------------------------------------------------------------------ */

    private fun issueTicketsUpToCap(server: MinecraftServer) {
        // Capacity is reserved for *planned* driver work (scanner/renewal). Ambient engine
        // observations must not consume the driver's ticket budget, otherwise external noise can
        // stall scanning.
        val activePlanned = register.countActivePlannedEntries()
        val ticketCount = register.allEntriesSnapshot().count { it.ticketName != null }
        val capacityByEntries = MementoConstants.CHUNK_LOAD_MAX_OUTSTANDING_TICKETS - activePlanned
        val capacityByTickets = MementoConstants.CHUNK_LOAD_MAX_OUTSTANDING_TICKETS - ticketCount
        val capacity = minOf(capacityByEntries, capacityByTickets)
        if (capacity <= 0) return

        // Issue tickets in a single pass.
        // Policy:
        // - Renewal desire is prioritized over scanner desire.
        val candidates = register.takeTicketCandidatesPrioritized(capacity)
        if (candidates.isEmpty()) return

        var issued = 0
        var alreadyLoadedObserved = 0
        for (ref in candidates) {
            val world = server.getWorld(ref.dimension) ?: continue

            // If already loaded, synthesize observation and let the normal pipeline handle it.
            val alreadyLoaded = world.chunkManager.getWorldChunk(ref.pos.x, ref.pos.z, false)
            if (alreadyLoaded != null) {
                register.synthesizeLoadObserved(ref, nowTick = tickCounter)
                alreadyLoadedObserved++
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
                    "ticket issuance batch candidates={} issued={} alreadyLoadedObserved={} outstanding={}",
                    candidates.size,
                    issued,
                    alreadyLoadedObserved,
                    register.allEntriesSnapshot().count { it.ticketName != null }
            )
        } else if (alreadyLoadedObserved > 0) {
            MementoLog.debug(
                    MementoConcept.DRIVER,
                    "ticket issuance batch candidates={} issued=0 alreadyLoadedObserved={} outstanding={}",
                    candidates.size,
                    alreadyLoadedObserved,
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

    fun logStateOnLowPulse() {
        if (!hasObservableWorkInProgress()) return

        logState()
    }

    private fun hasObservableWorkInProgress(): Boolean {
        val s = register.stateSnapshot()
        val activePlanned = register.countActivePlannedEntries()
        return activePlanned > 0 || s.ticketIssued > 0 || s.awaitingFullLoad > 0
    }

    private fun logState() {
        val s = register.stateSnapshot()
        val ticketCount = register.allEntriesSnapshot().count { it.ticketName != null }
        val desiredCount = register.allEntriesSnapshot().count { it.desiredBy.isNotEmpty() }

        MementoLog.trace(
                MementoConcept.DRIVER,
                "tick={} entries={} demand(desired={} queue={}) pressure(tickets={}) pipeline(requested={} loadAbsenceVerified={} ticketed={} observed={} awaitingFullLoad={} observedUntracked={} fullyLoaded={} propagationQueue={} propagating={} completedPendingPrune={} expiredPendingPrune={})",
                tickCounter,
                s.total,
                desiredCount,
                s.propagationQueue,
                ticketCount,
                s.requested,
                s.loadAbsenceVerified,
                s.ticketIssued,
                s.engineLoadObserved,
                s.awaitingFullLoad,
                s.observedUntracked,
                s.fullyLoaded,
                s.propagationQueue,
                s.propagating,
                s.completedPendingPrune,
                completedPendingPruneExpiredCount,
        )
    }

    private fun renewalRequestedChunksSnapshot(): Set<ChunkRef> =
            register
                    .allEntriesSnapshot()
                    .asSequence()
                    .filter { it.requestedBy.contains(ChunkLoadRegister.RequestSource.RENEWAL) }
                    .map { it.chunk }
                    .toSet()
}
