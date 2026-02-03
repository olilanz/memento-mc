package ch.oliverlanz.memento.infrastructure.chunk

/**
 * Driver-internal bookkeeping structure.
 *
 * Pure lifecycle ledger:
 * - no engine calls
 * - no Fabric / Minecraft dependencies
 * - no threading assumptions
 *
 * Note: Engine callbacks may record observations; tick thread will snapshot collections
 * before iterating to avoid concurrent modification issues.
 */
internal class ChunkLoadRegister {

    private val entries: MutableMap<ChunkRef, Entry> = mutableMapOf()

    /** Entries where ENGINE_LOAD_OBSERVED happened, and we are waiting for chunk accessibility. */
    private val awaitingAccessibility: LinkedHashSet<Entry> = LinkedHashSet()

    /** Entries that became CHUNK_AVAILABLE and are ready for propagation to subscribers. */
    private val propagationQueue: ArrayDeque<Entry> = ArrayDeque()

    fun clear() {
        entries.clear()
        awaitingAccessibility.clear()
        propagationQueue.clear()
    }

    fun get(chunk: ChunkRef): Entry? =
        entries[chunk]

    fun allEntries(): Collection<Entry> =
        entries.values

    fun remove(entry: Entry) {
        awaitingAccessibility.remove(entry)
        // ArrayDeque has no efficient remove; do best-effort linear remove (rare).
        propagationQueue.remove(entry)
        entries.remove(entry.chunk)
    }

    /**
     * Records that a provider wants this chunk.
     *
     * This does not imply that a ticket exists.
     */
    fun acknowledgeRequest(chunk: ChunkRef, source: RequestSource, nowTick: Long): Entry {
        val entry = entries.getOrPut(chunk) { Entry(chunk, firstSeenTick = nowTick) }
        entry.acknowledge(source, nowTick)
        return entry
    }

    /**
     * Records a load observation coming from the engine callback.
     *
     * We must be tolerant here: engine signals can race with our internal bookkeeping.
     * The goal is to never crash the server from an unexpected ordering.
     */
    fun recordEngineLoadObserved(chunk: ChunkRef, nowTick: Long): EngineObservationResult {
        val entry = entries.getOrPut(chunk) { Entry(chunk, firstSeenTick = nowTick) }
        return entry.recordEngineLoadObserved(nowTick)
    }

    /**
     * Snapshot of entries awaiting accessibility checks. Callers should iterate the returned list only.
     */
    fun snapshotAwaitingAccessibility(): List<Entry> =
        awaitingAccessibility.toList()

    /**
     * Polls the next entry that is ready for propagation, or null if none.
     */
    fun pollPropagationReady(): Entry? =
        propagationQueue.removeFirstOrNull()

    fun entriesWithTickets(): Sequence<Entry> =
        entries.values.asSequence().filter { it.hasTicket() }

    internal enum class EngineObservationResult {
        ACCEPTED,
        DUPLICATE_IGNORED,
        OUT_OF_ORDER_ACCEPTED,
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
         * If an engine observation races and already advanced the state, callers must use
         * [recordEngineLoadObserved] instead.
         */
        fun synthesizeLoadObserved(nowTick: Long) {
            // Synthetic observation bridges the "already loaded" gap.
            // If we have already observed a load, do not re-enter ENGINE_LOAD_OBSERVED
            // or re-enqueue, otherwise we risk infinite re-processing loops.
            if (state != State.REQUESTED) {
                return
            }
            state = State.ENGINE_LOAD_OBSERVED
            loadObservedTick = nowTick
            lastProgressTick = nowTick
            awaitingAccessibility += this
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
            awaitingAccessibility += this
        }

        /**
         * Transition from "load observed" to "chunk accessible".
         *
         * This removes the entry from the waiting set and enqueues it for propagation.
         */
        fun onChunkAvailable(nowTick: Long) {
            ensure(State.ENGINE_LOAD_OBSERVED)
            state = State.CHUNK_AVAILABLE
            lastProgressTick = nowTick
            awaitingAccessibility.remove(this)
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
         * Explicit regression: used only as a recovery when an engine load observation never
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
            awaitingAccessibility.remove(this)
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
                    awaitingAccessibility += this
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

        fun isRequested(): Boolean =
            state == State.REQUESTED

        fun isNotLoadedVerified(): Boolean =
            state == State.NOT_LOADED_VERIFIED

        fun isTicketIssued(): Boolean =
            state == State.TICKET_ISSUED

        fun isCompleted(): Boolean =
            state == State.COMPLETED

        fun isEngineLoadObserved(): Boolean =
            state == State.ENGINE_LOAD_OBSERVED

        fun hasTicket(): Boolean =
            ticketName != null && (state == State.TICKET_ISSUED || state == State.ENGINE_LOAD_OBSERVED || state == State.CHUNK_AVAILABLE || state == State.PROPAGATING)

        fun sources(): Set<RequestSource> =
            requestedBy.toSet()

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
        }    }

    

    private enum class State {
        REQUESTED,
        NOT_LOADED_VERIFIED,
        TICKET_ISSUED,
        ENGINE_LOAD_OBSERVED,
        CHUNK_AVAILABLE,
        PROPAGATING,
        COMPLETED,
    }

internal enum class RequestSource {
        RENEWAL,
        SCANNER,
        UNSOLICITED,
    }
}
