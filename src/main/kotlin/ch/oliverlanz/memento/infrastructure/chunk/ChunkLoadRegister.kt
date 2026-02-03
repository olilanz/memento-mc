package ch.oliverlanz.memento.infrastructure.chunk

/**
 * Driver-internal bookkeeping structure.
 *
 * This class deliberately contains:
 *  - no engine calls
 *  - no Fabric / Minecraft dependencies
 *  - no threading assumptions
 *
 * It is a pure lifecycle ledger.
 */
internal class ChunkLoadRegister {

    private val entries: MutableMap<ChunkRef, Entry> = mutableMapOf()

    /* =========================
     * Public API (driver-facing)
     * ========================= */

    fun acknowledgeRequest(
        chunk: ChunkRef,
        source: RequestSource
    ): Entry =
        entries.getOrPut(chunk) {
            Entry(chunk).also { it.acknowledge(source) }
        }

    fun get(chunk: ChunkRef): Entry? =
        entries[chunk]

    fun allEntries(): Collection<Entry> =
        entries.values

    fun removeCompleted() {
        entries.entries.removeIf { it.value.isCompleted() }
    }

    /* =========================
     * Register entry
     * ========================= */

    internal class Entry(
        val chunk: ChunkRef
    ) {

        private var state: State = State.REQUESTED

        private val requestedBy: MutableSet<RequestSource> = mutableSetOf()

        var ticketName: String? = null
            private set

        /* -------------------------
         * State transitions
         * ------------------------- */

        fun acknowledge(source: RequestSource) {
            ensure(State.REQUESTED)
            requestedBy += source
        }

        fun verifyNotLoaded() {
            ensure(State.REQUESTED)
            state = State.NOT_LOADED_VERIFIED
        }

        fun synthesizeLoadObserved() {
            ensure(State.REQUESTED)
            state = State.ENGINE_LOAD_OBSERVED
        }

        fun issueTicket(ticket: String) {
            ensure(State.NOT_LOADED_VERIFIED)
            ticketName = ticket
            state = State.TICKET_ISSUED
        }

        fun onEngineLoadObserved() {
            ensure(State.TICKET_ISSUED)
            state = State.ENGINE_LOAD_OBSERVED
        }

        fun onChunkAvailable() {
            ensure(State.ENGINE_LOAD_OBSERVED)
            state = State.CHUNK_AVAILABLE
        }

        fun beginPropagation() {
            ensure(State.CHUNK_AVAILABLE)
            state = State.PROPAGATING
        }

        fun completePropagation() {
            ensure(State.PROPAGATING)
            state = State.COMPLETED
        }

        /* -------------------------
         * Queries
         * ------------------------- */

        fun isCompleted(): Boolean =
            state == State.COMPLETED

        fun sources(): Set<RequestSource> =
            requestedBy.toSet()

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

        /* -------------------------
         * Lifecycle states
         * ------------------------- */

        private enum class State {
            REQUESTED,
            NOT_LOADED_VERIFIED,
            TICKET_ISSUED,
            ENGINE_LOAD_OBSERVED,
            CHUNK_AVAILABLE,
            PROPAGATING,
            COMPLETED
        }
    }

    /* =========================
     * Request source
     * ========================= */

    internal enum class RequestSource {
        RENEWAL,
        SCANNER,
        UNSOLICITED
    }
}
