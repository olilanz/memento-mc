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
 * - This lock must never be held while calling engine APIs or subscriber code.
 *   The register is a ledger, not an executor.
 */
internal class ChunkLoadRegister {

    private val lock = ReentrantLock()

    private val entries: MutableMap<ChunkRef, Entry> = mutableMapOf()

    /** Entries where ENGINE_LOAD_OBSERVED happened, and we are waiting for full chunk load. */
    private val awaitingFullLoad: LinkedHashSet<Entry> = LinkedHashSet()

    /** Entries that became CHUNK_AVAILABLE and are ready for propagation to subscribers. */
    private val propagationQueue: ArrayDeque<Entry> = ArrayDeque()

    fun clear() = lock.withLock {
        entries.clear()
        awaitingFullLoad.clear()
        propagationQueue.clear()
    }

    fun get(chunk: ChunkRef): Entry? = lock.withLock {
        entries[chunk]
    }

    fun allEntriesSnapshot(): List<Entry> = lock.withLock {
        entries.values.toList()
    }

    fun remove(entry: Entry) = lock.withLock {
        awaitingFullLoad.remove(entry)
        // ArrayDeque has no efficient remove; do best-effort linear remove (rare).
        propagationQueue.remove(entry)
        entries.remove(entry.chunk)
    }

    /**
     * Records that a provider wants this chunk.
     *
     * This does not imply that a ticket exists.
     */
    fun acknowledgeRequest(chunk: ChunkRef, source: RequestSource, nowTick: Long): Entry = lock.withLock {
        val entry = entries.getOrPut(chunk) { Entry(chunk, firstSeenTick = nowTick) }
        entry.acknowledge(source, nowTick)
        entry
    }

    /**
     * Records a load observation coming from the engine callback.
     *
     * We must be tolerant here: engine signals can race with our internal bookkeeping.
     * The goal is to never crash the server from an unexpected ordering.
     */
    fun recordEngineLoadObserved(chunk: ChunkRef, nowTick: Long): EngineObservationResult = lock.withLock {
        val entry = entries.getOrPut(chunk) { Entry(chunk, firstSeenTick = nowTick) }
        entry.recordEngineLoadObserved(nowTick)
    }

    /**
     * Takes a bounded batch of entries awaiting full load checks.
     *
     * The batch is rotated to provide fairness under throttling:
     * entries that are not yet ready are re-appended to the end of the set order.
     */
    fun takeAwaitingFullLoadBatch(max: Int): List<Entry> = lock.withLock {
        if (max <= 0 || awaitingFullLoad.isEmpty()) return@withLock emptyList()

        val n = minOf(max, awaitingFullLoad.size)
        val batch = ArrayList<Entry>(n)

        repeat(n) {
            val it = awaitingFullLoad.iterator()
            if (!it.hasNext()) return@repeat
            val entry = it.next()
            // Rotate: move to end so we don't starve later entries.
            it.remove()
            awaitingFullLoad.add(entry)
            batch.add(entry)
        }

        batch
    }

    /**
     * Polls the next entry that is ready for propagation, or null if none.
     *
     * NOTE: The driver must perform propagation outside of the register lock.
     */
    fun pollPropagationReady(): Entry? = lock.withLock {
        propagationQueue.removeFirstOrNull()
    }

    fun entriesWithTicketsSnapshot(): List<Entry> = lock.withLock {
        entries.values.filter { it.hasTicket() }
    }

    internal enum class EngineObservationResult {
        ACCEPTED,
        DUPLICATE_IGNORED,
        OUT_OF_ORDER_ACCEPTED,
    }

    internal enum class RequestSource {
        RENEWAL,
        SCANNER,
        UNSOLICITED,
    }

    /* =========================
     * Register entry
     * ========================= */

    internal inner class Entry(
        val chunk: ChunkRef,
        val firstSeenTick: Long,
    ) {

        private var state: State = State.REQUESTED

        private val requestedBy: MutableSet<RequestSource> = mutableSetOf()

        var ticketName: String? = null
            private set

        var lastProgressTick: Long = firstSeenTick
            private set

        var loadObservedTick: Long? = null
            private set

        /* -------------------------
         * State transitions
         *
         * IMPORTANT:
         * These assume the register lock is held by the caller.
         * ------------------------- */

        fun acknowledge(source: RequestSource, nowTick: Long) {
            // A request may arrive after the lifecycle has already advanced (e.g. engine signal races).
            // We treat this as additive metadata, not a state transition.
            if (state != State.COMPLETED) {
                requestedBy += source
                lastProgressTick = nowTick
            }
        }

        fun verifyNotLoaded(nowTick: Long) {
            ensure(State.REQUESTED)
            state = State.NOT_LOADED_VERIFIED
            lastProgressTick = nowTick
        }

        /**
         * Synthetic observation for "already loaded" checks (tick thread).
         *
         * This is intentionally strict: only legal from REQUESTED.
         */
        fun synthesizeLoadObserved(nowTick: Long) {
            if (state != State.REQUESTED) return

            state = State.ENGINE_LOAD_OBSERVED
            loadObservedTick = nowTick
            lastProgressTick = nowTick
            awaitingFullLoad += this
        }

        fun issueTicket(ticket: String, nowTick: Long) {
            ensure(State.NOT_LOADED_VERIFIED)
            ticketName = ticket
            state = State.TICKET_ISSUED
            lastProgressTick = nowTick
        }

        fun onEngineLoadObserved(nowTick: Long) {
            ensure(State.TICKET_ISSUED)
            state = State.ENGINE_LOAD_OBSERVED
            loadObservedTick = nowTick
            lastProgressTick = nowTick
            awaitingFullLoad += this
        }

        /**
         * Transition from "load observed" to "chunk available".
         *
         * This removes the entry from the waiting set and enqueues it for propagation.
         */
        fun onChunkAvailable(nowTick: Long) {
            ensure(State.ENGINE_LOAD_OBSERVED)
            state = State.CHUNK_AVAILABLE
            lastProgressTick = nowTick
            awaitingFullLoad.remove(this)
            propagationQueue.addLast(this)
        }

        fun beginPropagation(nowTick: Long) {
            ensure(State.CHUNK_AVAILABLE)
            state = State.PROPAGATING
            lastProgressTick = nowTick
        }

        fun completePropagation(nowTick: Long) {
            ensure(State.PROPAGATING)
            state = State.COMPLETED
            lastProgressTick = nowTick
        }

        /**
         * Explicit regression (with ticket): used only as a recovery when an engine load observation never
         * becomes accessible on tick thread.
         */
        fun resetToTicketIssued(nowTick: Long) {
            if (state != State.ENGINE_LOAD_OBSERVED) {
                throw IllegalStateException("Illegal reset for chunk=$chunk: expected=ENGINE_LOAD_OBSERVED actual=$state")
            }
            if (ticketName == null) {
                throw IllegalStateException("Illegal reset for chunk=$chunk: no ticket present")
            }
            state = State.TICKET_ISSUED
            loadObservedTick = null
            lastProgressTick = nowTick
            awaitingFullLoad.remove(this)
        }

        /**
         * Explicit regression (no ticket): used when a synthetic/out-of-order engine observation never
         * becomes accessible and we need to re-evaluate from REQUESTED.
         */
        fun resetToRequested(nowTick: Long) {
            if (state != State.ENGINE_LOAD_OBSERVED) {
                throw IllegalStateException("Illegal reset for chunk=$chunk: expected=ENGINE_LOAD_OBSERVED actual=$state")
            }
            if (ticketName != null) {
                throw IllegalStateException("Illegal reset for chunk=$chunk: ticket present")
            }
            state = State.REQUESTED
            loadObservedTick = null
            lastProgressTick = nowTick
            awaitingFullLoad.remove(this)
        }

        fun clearTicket(nowTick: Long) {
            ensure(State.COMPLETED)
            ticketName = null
            lastProgressTick = nowTick
        }

        /* -------------------------
         * Engine observation tolerant path
         * ------------------------- */

        fun recordEngineLoadObserved(nowTick: Long): EngineObservationResult {
            return when (state) {
                State.TICKET_ISSUED -> {
                    onEngineLoadObserved(nowTick)
                    EngineObservationResult.ACCEPTED
                }
                State.REQUESTED -> {
                    // Unexpected but survivable: we have an engine signal before we finished our local steps.
                    state = State.ENGINE_LOAD_OBSERVED
                    loadObservedTick = nowTick
                    lastProgressTick = nowTick
                    awaitingFullLoad += this
                    EngineObservationResult.OUT_OF_ORDER_ACCEPTED
                }
                State.ENGINE_LOAD_OBSERVED,
                State.CHUNK_AVAILABLE,
                State.PROPAGATING,
                State.COMPLETED,
                State.NOT_LOADED_VERIFIED -> {
                    // Duplicate or raced signal; keep the strongest known state.
                    EngineObservationResult.DUPLICATE_IGNORED
                }
            }
        }

        /* -------------------------
         * Queries
         * ------------------------- */

        fun isRequested(): Boolean = state == State.REQUESTED
        fun isNotLoadedVerified(): Boolean = state == State.NOT_LOADED_VERIFIED
        fun isTicketIssued(): Boolean = state == State.TICKET_ISSUED
        fun isEngineLoadObserved(): Boolean = state == State.ENGINE_LOAD_OBSERVED
        fun isCompleted(): Boolean = state == State.COMPLETED

        fun hasTicket(): Boolean =
            ticketName != null && (
                state == State.TICKET_ISSUED ||
                    state == State.ENGINE_LOAD_OBSERVED ||
                    state == State.CHUNK_AVAILABLE ||
                    state == State.PROPAGATING
                )

        fun sources(): Set<RequestSource> = requestedBy.toSet()

        fun observedAgeTicks(nowTick: Long): Long? =
            loadObservedTick?.let { nowTick - it }

        /* -------------------------
         * Guard
         * ------------------------- */

        private fun ensure(expected: State) {
            if (state != expected) {
                throw IllegalStateException(
                    "Illegal transition for chunk=$chunk: expected=$expected actual=$state"
                )
            }
        }
    }

    private enum class State {
        REQUESTED,
        NOT_LOADED_VERIFIED,
        TICKET_ISSUED,
        ENGINE_LOAD_OBSERVED,
        CHUNK_AVAILABLE,
        PROPAGATING,
        COMPLETED,
    }
}
