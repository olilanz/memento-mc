package ch.oliverlanz.memento.infrastructure.chunk

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Driver-internal bookkeeping structure.
 *
 * Pure lifecycle ledger:
 * - no engine calls
 * - no Fabric / Minecraft dependencies
 *
 * Concurrency:
 * - Engine callbacks may call into this register from non-tick threads.
 * - All register-owned state is protected by a writer lock.
 *
 * IMPORTANT:
 * - This lock must never be held while calling engine APIs or subscriber code. The register is a
 * ledger, not an executor.
 */
internal class ChunkLoadRegister {

    private val lock = ReentrantLock()

    private val entries: MutableMap<ChunkRef, Entry> = mutableMapOf()

    /** Chunks where ENGINE_LOAD_OBSERVED happened, and we are waiting for full chunk load. */
    private val awaitingFullLoad: LinkedHashSet<ChunkRef> = LinkedHashSet()

    /** Chunks that became FULLY_LOADED and are ready for propagation to subscribers. */
    private val propagationQueue: ArrayDeque<ChunkRef> = ArrayDeque()

    /** Demand snapshot epoch; advanced once per driver tick when demand is recomputed. */
    private var demandEpoch: Long = 0L

    fun clear() =
            lock.withLock {
                entries.clear()
                awaitingFullLoad.clear()
                propagationQueue.clear()
                demandEpoch = 0L
            }

    fun allEntriesSnapshot(): List<EntryView> = lock.withLock { entries.values.map { it.view() } }

    /** Removes an entry and performs queue hygiene. */
    fun remove(chunk: ChunkRef) =
            lock.withLock {
                awaitingFullLoad.remove(chunk)
                propagationQueue.remove(chunk)
                entries.remove(chunk)
            }

    /* =====================================================================
     * Demand snapshot
     * ===================================================================== */

    /**
     * Begins a new demand snapshot cycle.
     *
     * The driver calls this once per tick before marking desired chunks.
     */
    fun beginDemandSnapshot() =
            lock.withLock {
                demandEpoch++
                for (e in entries.values) {
                    e.desiredEpoch = demandEpoch
                    e.desiredBy.clear()
                }
            }

    /** Marks this chunk as desired by a provider in the current demand snapshot. */
    fun markDesired(chunk: ChunkRef, source: RequestSource, nowTick: Long): EntryView =
            lock.withLock {
                val entry =
                        entries.getOrPut(chunk) { Entry(chunk = chunk, firstSeenTick = nowTick) }
                entry.desiredBy += source
                entry.markProgress(nowTick)
                entry.view()
            }

    /**
     * Prunes entries that are no longer desired.
     *
     * Returns a list of chunks where tickets should be removed by the driver (outside the lock).
     */
    fun pruneUndesired(nowTick: Long): PruneResult =
            lock.withLock {
                val toRemoveTickets = mutableListOf<ChunkRef>()
                val toRemoveEntries = mutableListOf<ChunkRef>()

                for ((chunk, entry) in entries) {
                    val desired = entry.desiredBy.isNotEmpty()

                    // Completed entries are inert; remove once no longer desired.
                    if (entry.isCompletedPendingPrune() && !desired) {
                        // Ticket can be removed as part of pruning.
                        if (entry.ticketName != null) toRemoveTickets += chunk
                        toRemoveEntries += chunk
                        continue
                    }

                    // If something is no longer desired before we have ticketed it, drop it.
                    if (!desired && (entry.isRequested() || entry.isNotLoadedVerified())) {
                        toRemoveEntries += chunk
                        continue
                    }

                    // If we have a standing ticket for something that is no longer desired, cancel
                    // it.
                    if (!desired && entry.hasTicket()) {
                        toRemoveTickets += chunk
                        toRemoveEntries += chunk
                    }
                }

                // Apply removals (queue hygiene).
                for (chunk in toRemoveEntries) {
                    awaitingFullLoad.remove(chunk)
                    propagationQueue.remove(chunk)
                    entries.remove(chunk)
                }

                PruneResult(
                        removedEntries = toRemoveEntries,
                        ticketsToRemove = toRemoveTickets.distinct(),
                )
            }

    /**
     * Expires COMPLETED_PENDING_PRUNE entries that remain desired for too long.
     *
     * This should be rare and indicates a logic fault (e.g. scanner never dropping demand). The
     * driver should make this visible in aggregated observability.
     *
     * Returns chunks where tickets should be removed by the driver (outside the lock).
     */
    fun expireCompletedPendingPrune(nowTick: Long, expireAfterTicks: Long): ExpireResult =
            lock.withLock {
                if (expireAfterTicks <= 0) return@withLock ExpireResult(emptyList(), emptyList())

                val expired = mutableListOf<ChunkRef>()
                val ticketsToRemove = mutableListOf<ChunkRef>()

                for ((chunk, entry) in entries) {
                    if (!entry.isCompletedPendingPrune()) continue
                    val completedAt = entry.completedTick ?: continue
                    if ((nowTick - completedAt) <= expireAfterTicks) continue

                    // Expire: drop the entry, allowing it to re-enter as a fresh request if still
                    // desired next tick.
                    if (entry.ticketName != null) ticketsToRemove += chunk
                    expired += chunk
                }

                for (chunk in expired) {
                    awaitingFullLoad.remove(chunk)
                    propagationQueue.remove(chunk)
                    entries.remove(chunk)
                }

                ExpireResult(
                        expiredEntries = expired,
                        ticketsToRemove = ticketsToRemove.distinct(),
                )
            }

    /**
     * Prevent permanent stalls in ENGINE_LOAD_OBSERVED / awaiting-full-load.
     *
     * Semantics:
     * - Bounded effort only: the driver may attach tickets to increase the probability of reaching
     * a stable FULLY_LOADED state, but this is never guaranteed.
     * - On expiry we *always* release pressure and drop the entry from the register, allowing the
     * chunk to re-enter as a fresh request if (and only if) demand still exists.
     */
    fun expireAwaitingFullLoad(nowTick: Long, expireAfterTicks: Long): ExpireResult =
            lock.withLock {
                val expired = ArrayList<ChunkRef>()
                val ticketsToRemove = ArrayList<ChunkRef>()
                val sourceCounts = ExpirySourceCounts()
                var ticketedAtExpiry = 0
                var unticketedAtExpiry = 0

                val it = awaitingFullLoad.iterator()
                while (it.hasNext()) {
                    val chunk = it.next()
                    val entry = entries[chunk] ?: continue
                    val observedAt = entry.loadObservedTick ?: continue
                    if ((nowTick - observedAt) <= expireAfterTicks) continue

                    expired.add(chunk)
                    it.remove()
                    propagationQueue.remove(chunk)

                    when (entry.telemetrySourceBucket()) {
                        RequestSource.SCANNER -> sourceCounts.scanner++
                        RequestSource.RENEWAL -> sourceCounts.renewal++
                        RequestSource.UNSOLICITED -> sourceCounts.unsolicited++
                    }

                    if (entry.ticketName != null) {
                        ticketsToRemove.add(chunk)
                        ticketedAtExpiry++
                    } else {
                        unticketedAtExpiry++
                    }

                    // Drop the entry entirely. Demand (if still present) will re-create it on a
                    // later tick.
                    entries.remove(chunk)
                }

                ExpireResult(
                        expiredEntries = expired,
                        ticketsToRemove = ticketsToRemove.distinct(),
                        telemetry =
                                ExpiryTelemetry(
                                        source = sourceCounts.toView(),
                                        ticketStatus =
                                                ExpiryTicketStatus(
                                                        ticketed = ticketedAtExpiry,
                                                        unticketed = unticketedAtExpiry,
                                                ),
                                ),
                )
            }

    /**
     * Prevent permanent stalls in TICKET_ISSUED (ticketed but never observed by the engine).
     *
     * Semantics:
     * - Bounded effort only: the driver may attach tickets to request a load, but the engine may
     * defer or never materialize the corresponding CHUNK_LOAD callback.
     * - On expiry we *always* release pressure and drop the entry from the register, allowing the
     * chunk to re-enter as a fresh request if (and only if) demand still exists.
     */
    fun expireTicketIssuedWithoutObservation(nowTick: Long, expireAfterTicks: Long): ExpireResult =
            lock.withLock {
                val expired = ArrayList<ChunkRef>()
                val ticketsToRemove = ArrayList<ChunkRef>()
                val sourceCounts = ExpirySourceCounts()
                var ticketedAtExpiry = 0
                var unticketedAtExpiry = 0

                val it = entries.iterator()
                while (it.hasNext()) {
                    val (chunk, entry) = it.next()
                    if (!entry.isTicketIssued()) continue
                    val issuedAt = entry.lastProgressTick
                    if ((nowTick - issuedAt) <= expireAfterTicks) continue

                    expired.add(chunk)
                    when (entry.telemetrySourceBucket()) {
                        RequestSource.SCANNER -> sourceCounts.scanner++
                        RequestSource.RENEWAL -> sourceCounts.renewal++
                        RequestSource.UNSOLICITED -> sourceCounts.unsolicited++
                    }

                    if (entry.ticketName != null) {
                        ticketsToRemove.add(chunk)
                        ticketedAtExpiry++
                    } else {
                        unticketedAtExpiry++
                    }
                    it.remove()
                }

                ExpireResult(
                        expiredEntries = expired,
                        ticketsToRemove = ticketsToRemove.distinct(),
                        telemetry =
                                ExpiryTelemetry(
                                        source = sourceCounts.toView(),
                                        ticketStatus =
                                                ExpiryTicketStatus(
                                                        ticketed = ticketedAtExpiry,
                                                        unticketed = unticketedAtExpiry,
                                                ),
                                ),
                )
            }
    /* =====================================================================
     * Engine observations
     * ===================================================================== */

    /**
     * Records a load observation coming from the engine callback.
     *
     * We must be tolerant here: engine signals can race with our internal bookkeeping. The goal is
     * to never crash the server from an unexpected ordering.
     */
    fun recordEngineLoadObserved(chunk: ChunkRef, nowTick: Long): EngineLoadObservation =
            lock.withLock {
                val existed = entries.containsKey(chunk)
                val entry = entries.getOrPut(chunk) { Entry(chunk, firstSeenTick = nowTick) }

                // If this is a purely unsolicited observation (no demand known yet), tag it for
                // observability.
                val unsolicited = (!existed && entry.desiredBy.isEmpty())
                if (unsolicited) {
                    entry.requestedBy += RequestSource.UNSOLICITED
                }

                val result = entry.recordEngineLoadObserved(nowTick)

                // Bridge engine observation -> full-load polling:
                // Once we have observed ENGINE_LOAD_OBSERVED, we must start polling for a
                // fully-loaded WorldChunk
                // on the tick thread. Without this, the driver will observe loads but never
                // progress to propagation.
                if (entry.isEngineLoadObserved()) {
                    awaitingFullLoad.add(chunk)
                }

                EngineLoadObservation(result = result, isUnsolicited = unsolicited)
            }

    /**
     * Records a request acknowledgement and returns the entry view.
     *
     * This does not imply that a ticket exists.
     */
    fun acknowledgeRequest(chunk: ChunkRef, source: RequestSource, nowTick: Long): EntryView =
            lock.withLock {
                val entry = entries.getOrPut(chunk) { Entry(chunk, firstSeenTick = nowTick) }
                entry.requestedBy += source
                entry.markProgress(nowTick)
                entry.view()
            }

    /* =====================================================================
     * Driver actions (tick thread only; engine calls outside lock)
     * ===================================================================== */

    /**
     * Takes a bounded batch of chunks awaiting full load checks.
     *
     * The batch is rotated to provide fairness under throttling: chunks that are not yet ready are
     * re-appended to the end of the set order.
     */
    fun takeAwaitingFullLoadBatch(max: Int): List<ChunkRef> =
            lock.withLock {
                if (max <= 0 || awaitingFullLoad.isEmpty()) return@withLock emptyList()

                val n = minOf(max, awaitingFullLoad.size)
                val batch = ArrayList<ChunkRef>(n)

                repeat(n) {
                    val it = awaitingFullLoad.iterator()
                    if (!it.hasNext()) return@repeat
                    val chunk = it.next()
                    // Rotate: move to end so we don't starve later chunks.
                    it.remove()
                    awaitingFullLoad.add(chunk)
                    batch.add(chunk)
                }

                batch
            }

    /** Marks a previously observed chunk as now fully available and enqueues it for propagation. */
    fun onChunkAvailable(chunk: ChunkRef, nowTick: Long) =
            lock.withLock {
                val entry = entries[chunk] ?: return@withLock
                entry.onChunkAvailable(nowTick)
                awaitingFullLoad.remove(chunk)
                propagationQueue.addLast(chunk)
            }

    /**
     * Polls the next chunk that is ready for propagation, or null if none.
     *
     * NOTE: The driver must perform propagation outside of the register lock.
     */
    fun pollPropagationReady(): ChunkRef? = lock.withLock { propagationQueue.removeFirstOrNull() }

    fun beginPropagation(chunk: ChunkRef, nowTick: Long) =
            lock.withLock { entries[chunk]?.beginPropagation(nowTick) }

    /**
     * Completes propagation and moves the entry into COMPLETED_PENDING_PRUNE.
     *
     * The entry remains inert until demand no longer includes it, at which point it can be pruned.
     */
    fun completePropagation(chunk: ChunkRef, nowTick: Long) =
            lock.withLock { entries[chunk]?.completePropagation(nowTick) }

    fun setNotLoadedVerified(chunk: ChunkRef, nowTick: Long) =
            lock.withLock { entries[chunk]?.verifyNotLoaded(nowTick) }

    fun synthesizeLoadObserved(chunk: ChunkRef, nowTick: Long) =
            lock.withLock {
                val entry = entries[chunk] ?: return@withLock
                entry.synthesizeLoadObserved(nowTick)
                if (entry.isEngineLoadObserved()) {
                    awaitingFullLoad.add(chunk)
                }
            }

    fun issueTicket(chunk: ChunkRef, ticketName: String, nowTick: Long) =
            lock.withLock { entries[chunk]?.issueTicket(ticketName, nowTick) }

    fun ticketName(chunk: ChunkRef): String? = lock.withLock { entries[chunk]?.ticketName }

    /** True when the register already contains an entry for [chunk] (any origin). */
    fun containsEntry(chunk: ChunkRef): Boolean = lock.withLock { entries.containsKey(chunk) }

    /**
     * Counts lifecycle entries that originated from *solicited* driver demand (scanner/renewal)
     * and are still active (i.e., not COMPLETED_PENDING_PRUNE).
     *
     * IMPORTANT: Unsolicited observations may also create entries, but those must not consume
     * driver capacity for issuing new tickets.
     */
    fun countActiveSolicitedEntries(): Int =
            lock.withLock { entries.values.count { it.isSolicited() && !it.isCompletedPendingPrune() } }

    /**
     * True if the driver currently tracks a lifecycle entry for [chunk] that originated from
     * driver demand (scanner or renewal).
     *
     * This is intentionally *not* equivalent to "entry exists": the register may also contain
     * entries adopted from unsolicited engine loads.
     */
    fun hasSolicitedEntry(chunk: ChunkRef): Boolean =
            lock.withLock { entries[chunk]?.isSolicited() == true }

    /**
     * Takes up to [max] ticket candidates.
     *
     * Candidates are chunks that are currently desired, in REQUESTED state, and not already
     * ticketed.
     */
    fun takeTicketCandidates(max: Int): List<ChunkRef> =
            lock.withLock {
                if (max <= 0) return@withLock emptyList()
                val result = ArrayList<ChunkRef>(minOf(max, entries.size))
                for ((chunk, entry) in entries) {
                    if (result.size >= max) break
                    if (!entry.isRequested()) continue
                    if (entry.desiredBy.isEmpty()) continue
                    if (entry.ticketName != null) continue
                    result.add(chunk)
                }
                result
            }

    /** Returns the desired-by sources for a chunk in the current demand snapshot. */
    fun desiredSources(chunk: ChunkRef): Set<RequestSource> =
            lock.withLock { entries[chunk]?.desiredBy?.toSet() ?: emptySet() }

    /**
     * Takes up to [max] ticket candidates, prioritizing renewal desire over scanner desire.
     *
     * The driver remains the sole policy owner for pressure balancing; providers only express
     * desire.
     */
    fun takeTicketCandidatesPrioritized(max: Int): List<ChunkRef> =
            lock.withLock {
                if (max <= 0) return@withLock emptyList()

                val renewalFirst = ArrayList<ChunkRef>(minOf(max, entries.size))
                val scannerNext = ArrayList<ChunkRef>(minOf(max, entries.size))

                for ((chunk, entry) in entries) {
                    if (!entry.isRequested()) continue
                    if (entry.desiredBy.isEmpty()) continue
                    if (entry.ticketName != null) continue

                    if (entry.desiredBy.contains(RequestSource.RENEWAL)) {
                        renewalFirst.add(chunk)
                    } else if (entry.desiredBy.contains(RequestSource.SCANNER)) {
                        scannerNext.add(chunk)
                    }
                }

                val result = ArrayList<ChunkRef>(minOf(max, renewalFirst.size + scannerNext.size))
                for (c in renewalFirst) {
                    if (result.size >= max) break
                    result.add(c)
                }
                for (c in scannerNext) {
                    if (result.size >= max) break
                    result.add(c)
                }
                result
            }

    /** Returns true if the chunk is desired by any provider in the current demand snapshot. */
    fun isDesired(chunk: ChunkRef): Boolean =
            lock.withLock { entries[chunk]?.desiredBy?.isNotEmpty() == true }

    fun stateSnapshot(): StateInventory =
            lock.withLock {
                var requested = 0
                var verified = 0
                var ticketed = 0
                var observed = 0
                var observedUntracked = 0
                var available = 0
                var propagating = 0
                var completedPendingPrune = 0

                for ((chunk, e) in entries) {
                    when (e.stateIndexForCounting()) {
                        0 -> requested++
                        1 -> verified++
                        2 -> ticketed++
                        3 -> {
                            observed++
                            if (!awaitingFullLoad.contains(chunk)) observedUntracked++
                        }
                        4 -> available++
                        5 -> propagating++
                        6 -> completedPendingPrune++
                    }
                }

                StateInventory(
                        total = entries.size,
                        requested = requested,
                        loadAbsenceVerified = verified,
                        ticketIssued = ticketed,
                        engineLoadObserved = observed,
                        fullyLoaded = available,
                        propagating = propagating,
                        completedPendingPrune = completedPendingPrune,
                        awaitingFullLoad = awaitingFullLoad.size,
                        observedUntracked = observedUntracked,
                        propagationQueue = propagationQueue.size,
                )
            }

    /* =====================================================================
     * Types
     * ===================================================================== */

    internal enum class EngineObservationResult {
        ACCEPTED,
        DUPLICATE_IGNORED,
        OUT_OF_ORDER_ACCEPTED,
    }

    internal data class EngineLoadObservation(
            val result: EngineObservationResult,
            val isUnsolicited: Boolean,
    )

    internal enum class RequestSource {
        RENEWAL,
        SCANNER,
        UNSOLICITED,
    }

    internal data class PruneResult(
            val removedEntries: List<ChunkRef>,
            val ticketsToRemove: List<ChunkRef>,
    )

    internal data class ExpireResult(
            val expiredEntries: List<ChunkRef>,
            val ticketsToRemove: List<ChunkRef>,
            val telemetry: ExpiryTelemetry =
                    ExpiryTelemetry(
                            source = ExpirySourceCountsView(0, 0, 0),
                            ticketStatus = ExpiryTicketStatus(ticketed = 0, unticketed = 0),
                    ),
    )

    internal data class ExpiryTelemetry(
            val source: ExpirySourceCountsView,
            val ticketStatus: ExpiryTicketStatus,
    )

    internal data class ExpirySourceCountsView(
            val scanner: Int,
            val renewal: Int,
            val unsolicited: Int,
    )

    internal data class ExpiryTicketStatus(
            val ticketed: Int,
            val unticketed: Int,
    )

    private data class ExpirySourceCounts(
            var scanner: Int = 0,
            var renewal: Int = 0,
            var unsolicited: Int = 0,
    ) {
        fun toView(): ExpirySourceCountsView =
                ExpirySourceCountsView(
                        scanner = scanner,
                        renewal = renewal,
                        unsolicited = unsolicited,
                )
    }

    internal /**
     * StateInventory represents a continuously maintained accounting of
     * chunk lifecycle states within the register.
     *
     * It is NOT a point-in-time snapshot but a structural inventory derived
     * from authoritative internal state.
     */
data class StateInventory(
            val total: Int,
            val requested: Int,
            val loadAbsenceVerified: Int,
            val ticketIssued: Int,
            val engineLoadObserved: Int,
            val fullyLoaded: Int,
            val propagating: Int,
            val completedPendingPrune: Int,
            val awaitingFullLoad: Int,
            val observedUntracked: Int,
            val propagationQueue: Int,
    )

    internal data class EntryView(
            val chunk: ChunkRef,
            val state: String,
            val ticketName: String?,
            val desiredBy: Set<RequestSource>,
            val requestedBy: Set<RequestSource>,
            val firstSeenTick: Long,
            val lastProgressTick: Long,
            val loadObservedTick: Long?,
            val completedTick: Long?,
    )

    /* =========================
     * Register entry
     * ========================= */

    internal inner class Entry(
            val chunk: ChunkRef,
            val firstSeenTick: Long,
    ) {
        private val lifecycle = ChunkLifecycleMachine(chunk = chunk, firstSeenTick = firstSeenTick)

        val desiredBy: MutableSet<RequestSource> = mutableSetOf()
        var desiredEpoch: Long = 0L

        val requestedBy: MutableSet<RequestSource> = mutableSetOf()

        val ticketName: String?
            get() = lifecycle.ticketName

        val lastProgressTick: Long
            get() = lifecycle.lastProgressTick

        val loadObservedTick: Long?
            get() = lifecycle.loadObservedTick

        val completedTick: Long?
            get() = lifecycle.completedTick

        fun markProgress(nowTick: Long) {
            lifecycle.markProgress(nowTick)
        }

        fun verifyNotLoaded(nowTick: Long) {
            lifecycle.verifyNotLoaded(nowTick)
        }

        /** Synthetic observation for "already loaded" checks (tick thread). */
        fun synthesizeLoadObserved(nowTick: Long) {
            lifecycle.synthesizeLoadObserved(nowTick)
        }

        fun issueTicket(ticket: String, nowTick: Long) {
            lifecycle.issueTicket(ticket, nowTick)
        }

        fun onChunkAvailable(nowTick: Long) {
            lifecycle.onChunkAvailable(nowTick)
        }

        fun beginPropagation(nowTick: Long) {
            lifecycle.beginPropagation(nowTick)
        }

        fun completePropagation(nowTick: Long) {
            lifecycle.completePropagation(nowTick)
        }

        /** Engine observation tolerant path. */
        fun recordEngineLoadObserved(nowTick: Long): EngineObservationResult =
                lifecycle.recordEngineLoadObserved(nowTick)

        fun hasTicket(): Boolean = lifecycle.hasTicket()

        /**
         * True if this entry has ever been requested by a driver demand provider (scanner or
         * renewal).
         *
         * NOTE: The register may also contain entries adopted from unsolicited engine loads.
         * Those must not be treated as driver-demand origins for pressure attribution.
         */
        fun isSolicited(): Boolean =
                requestedBy.contains(RequestSource.RENEWAL) ||
                        requestedBy.contains(RequestSource.SCANNER)

        /**
         * Telemetry source bucket for bounded expiry composition summaries.
         *
         * Precedence is explicit and deterministic so source counts form a partition:
         * RENEWAL > SCANNER > UNSOLICITED.
         */
        fun telemetrySourceBucket(): RequestSource =
                when {
                    requestedBy.contains(RequestSource.RENEWAL) -> RequestSource.RENEWAL
                    requestedBy.contains(RequestSource.SCANNER) -> RequestSource.SCANNER
                    else -> RequestSource.UNSOLICITED
                }

        fun view(): EntryView =
                EntryView(
                        chunk = chunk,
                        state = lifecycle.stateName(),
                        ticketName = ticketName,
                        desiredBy = desiredBy.toSet(),
                        requestedBy = requestedBy.toSet(),
                        firstSeenTick = firstSeenTick,
                        lastProgressTick = lastProgressTick,
                        loadObservedTick = loadObservedTick,
                        completedTick = completedTick,
                )

        // NOTE: These helpers exist so the outer register does not access the private state
        // directly.
        fun isRequested(): Boolean = lifecycle.isRequested()
        fun isNotLoadedVerified(): Boolean = lifecycle.isNotLoadedVerified()
        fun isTicketIssued(): Boolean = lifecycle.isTicketIssued()
        fun isEngineLoadObserved(): Boolean = lifecycle.isEngineLoadObserved()
        fun isChunkAvailable(): Boolean = lifecycle.isChunkAvailable()
        fun isPropagating(): Boolean = lifecycle.isPropagating()
        fun isCompletedPendingPrune(): Boolean = lifecycle.isCompletedPendingPrune()

        fun stateIndexForCounting(): Int = lifecycle.stateIndexForCounting()
    }

    /**
     * Internal per-chunk lifecycle machine.
     *
     * Owns transition authority and lifecycle metadata for exactly one chunk entry.
     */
    private class ChunkLifecycleMachine(
            private val chunk: ChunkRef,
            firstSeenTick: Long,
    ) {
        private var state: State = State.REQUESTED

        var ticketName: String? = null
            private set

        var lastProgressTick: Long = firstSeenTick
            private set

        var loadObservedTick: Long? = null
            private set

        var completedTick: Long? = null
            private set

        fun markProgress(nowTick: Long) {
            lastProgressTick = nowTick
        }

        fun verifyNotLoaded(nowTick: Long) {
            ensure(State.REQUESTED)
            state = State.NOT_LOADED_VERIFIED
            lastProgressTick = nowTick
        }

        fun synthesizeLoadObserved(nowTick: Long) {
            if (state != State.REQUESTED) return
            state = State.ENGINE_LOAD_OBSERVED
            loadObservedTick = nowTick
            lastProgressTick = nowTick
        }

        fun issueTicket(ticket: String, nowTick: Long) {
            ensure(State.NOT_LOADED_VERIFIED)
            ticketName = ticket
            state = State.TICKET_ISSUED
            lastProgressTick = nowTick
        }

        fun onChunkAvailable(nowTick: Long) {
            ensure(State.ENGINE_LOAD_OBSERVED)
            state = State.FULLY_LOADED
            lastProgressTick = nowTick
        }

        fun beginPropagation(nowTick: Long) {
            ensure(State.FULLY_LOADED)
            state = State.PROPAGATING
            lastProgressTick = nowTick
        }

        fun completePropagation(nowTick: Long) {
            ensure(State.PROPAGATING)
            state = State.COMPLETED_PENDING_PRUNE
            completedTick = nowTick
            lastProgressTick = nowTick
        }

        private fun onEngineLoadObserved(nowTick: Long) {
            ensure(State.TICKET_ISSUED)
            state = State.ENGINE_LOAD_OBSERVED
            loadObservedTick = nowTick
            lastProgressTick = nowTick
        }

        /** Engine observation tolerant path. */
        fun recordEngineLoadObserved(nowTick: Long): EngineObservationResult {
            return when (state) {
                State.TICKET_ISSUED -> {
                    onEngineLoadObserved(nowTick)
                    EngineObservationResult.ACCEPTED
                }
                State.NOT_LOADED_VERIFIED -> {
                    // Out-of-order but survivable: engine signaled load completion while we were
                    // still between "verify not loaded" and "issue ticket" on the tick thread.
                    // We must not lose this observation; otherwise the entry can stall in
                    // TICKET_ISSUED (no further engine callbacks) and never progress to full-load
                    // polling.
                    state = State.ENGINE_LOAD_OBSERVED
                    loadObservedTick = nowTick
                    lastProgressTick = nowTick
                    EngineObservationResult.OUT_OF_ORDER_ACCEPTED
                }
                State.REQUESTED -> {
                    // Unexpected but survivable: we have an engine signal before we finished our
                    // local steps.
                    state = State.ENGINE_LOAD_OBSERVED
                    loadObservedTick = nowTick
                    lastProgressTick = nowTick
                    EngineObservationResult.OUT_OF_ORDER_ACCEPTED
                }
                State.ENGINE_LOAD_OBSERVED,
                State.FULLY_LOADED,
                State.PROPAGATING,
                State.COMPLETED_PENDING_PRUNE -> EngineObservationResult.DUPLICATE_IGNORED
            }
        }

        fun hasTicket(): Boolean =
                ticketName != null &&
                        (state == State.TICKET_ISSUED ||
                                state == State.ENGINE_LOAD_OBSERVED ||
                                state == State.FULLY_LOADED ||
                                state == State.PROPAGATING ||
                                state == State.COMPLETED_PENDING_PRUNE)

        fun stateName(): String = state.name

        fun isRequested(): Boolean = state == State.REQUESTED
        fun isNotLoadedVerified(): Boolean = state == State.NOT_LOADED_VERIFIED
        fun isTicketIssued(): Boolean = state == State.TICKET_ISSUED
        fun isEngineLoadObserved(): Boolean = state == State.ENGINE_LOAD_OBSERVED
        fun isChunkAvailable(): Boolean = state == State.FULLY_LOADED
        fun isPropagating(): Boolean = state == State.PROPAGATING
        fun isCompletedPendingPrune(): Boolean = state == State.COMPLETED_PENDING_PRUNE

        fun stateIndexForCounting(): Int =
                when (state) {
                    State.REQUESTED -> 0
                    State.NOT_LOADED_VERIFIED -> 1
                    State.TICKET_ISSUED -> 2
                    State.ENGINE_LOAD_OBSERVED -> 3
                    State.FULLY_LOADED -> 4
                    State.PROPAGATING -> 5
                    State.COMPLETED_PENDING_PRUNE -> 6
                }

        private fun ensure(expected: State) {
            if (state != expected) {
                throw IllegalStateException(
                        "Illegal transition for chunk=$chunk: expected=$expected actual=$state"
                )
            }
        }

        private enum class State {
            REQUESTED,
            NOT_LOADED_VERIFIED,
            TICKET_ISSUED,
            ENGINE_LOAD_OBSERVED,
            FULLY_LOADED,

            /**
             * The chunk is physically present and fully accessible in memory.
             * This reflects Minecraft engine reality only.
             * It does NOT imply propagation has started.
             */
            PROPAGATING,
            COMPLETED_PENDING_PRUNE,
        }
    }
}
