package ch.oliverlanz.memento

/**
 * Centralized tuning and policy constants for Memento.
 *
 * These constants express *intent* and *operational policy*. They must not encode algorithmic
 * mechanics or hidden behavior.
 */
object MementoConstants {

    /* ---------------------------------------------------------------------
     * Chunk loading / driver pacing
     * ------------------------------------------------------------------ */

    /**
     * Minimum delay (ticks) between proactive chunk load requests.
     *
     * This is a hard safety clamp only. The adaptive EWMA is expected to operate well above this
     * value under normal conditions.
     */
    const val CHUNK_LOAD_MIN_DELAY_TICKS: Long = 1L

    /**
     * Maximum delay (ticks) between proactive chunk load requests.
     *
     * Allows the driver to back off aggressively under sustained pressure.
     */
    const val CHUNK_LOAD_MAX_DELAY_TICKS: Long = 1200L

    /**
     * EWMA weight for historical latency samples. Higher values make the driver more stable but
     * slower to react.
     */
    const val CHUNK_LOAD_EWMA_WEIGHT_OLD: Double = 0.7

    /** EWMA weight for new latency samples. */
    const val CHUNK_LOAD_EWMA_WEIGHT_NEW: Double = 0.3

    /**
     * Maximum latency sample (ticks) considered for EWMA. Prevents pathological spikes from
     * dominating pacing.
     */
    const val CHUNK_LOAD_EWMA_MAX_SAMPLE_TICKS: Long = 20_000L

    /* ---------------------------------------------------------------------
     * Re-arming behavior for raced chunk load signals
     * ------------------------------------------------------------------ */

    /** Delay before re-arming a pending load after an early engine signal. */
    const val CHUNK_LOAD_REARM_DELAY_TICKS: Long = 10L

    /** Base backoff between re-arm attempts. Exponential backoff is applied on top. */
    const val CHUNK_LOAD_REARM_BACKOFF_TICKS: Long = 20L

    /** Maximum number of re-arm attempts for a pending load. */
    const val CHUNK_LOAD_REARM_MAX_ATTEMPTS: Int = 3

    /**
     * Maximum number of chunk load forwards per tick. Protects against long tick stalls under
     * bursty conditions.
     */
    const val CHUNK_LOAD_MAX_FORWARDED_PER_TICK: Int = 16

    /**
     * Radius (in chunks) for the driver's temporary tickets.
     *
     * We keep this conservative (1) to remain polite and to avoid loading large regions
     * accidentally. Throughput comes from single-flight + overlap, not radius.
     */
    const val CHUNK_LOAD_TICKET_RADIUS: Int = 1

    /**
     * Hard cap on outstanding DRIVER tickets.
     *
     * Tickets are advisory and live in the scheduler graph. A bounded cap protects against
     * accidental retention and keeps pressure predictable.
     */
    const val CHUNK_LOAD_MAX_OUTSTANDING_TICKETS: Int = 256

    /**
     * State log cadence (ticks) for driver DEBUG state lines.
     *
     * Keep frequent enough to diagnose stalls, but not noisy under normal operation.
     */
    const val CHUNK_LOAD_STATE_LOG_EVERY_TICKS: Long = 100L

    /**
     * Cadence for "awaiting full load" checks.
     *
     * We poll for accessibility on the tick thread to respect engine thread-safety constraints.
     */
    const val CHUNK_LOAD_AWAITING_FULL_LOAD_CHECK_EVERY_TICKS: Long = 10L

    /** Maximum number of awaiting-full-load checks performed per check cycle. */
    const val CHUNK_LOAD_AWAITING_FULL_LOAD_MAX_PER_CYCLE: Int = 100

    /**
     * Maximum time (ticks) a chunk may remain in ENGINE_LOAD_OBSERVED / awaiting-full-load. After
     * this, the entry is reset or evicted to prevent permanent stalls.
     */
    const val CHUNK_LOAD_AWAITING_FULL_LOAD_TIMEOUT_TICKS: Long = 600L

    /**
     * Maximum time (ticks) a chunk may remain in TICKET_ISSUED without ever being observed by the
     * engine.
     *
     * This is the symmetric bounded-wait for the "ticketed but no callback" case.
     */
    const val CHUNK_LOAD_TICKET_ISSUED_TIMEOUT_TICKS: Long = 600L

    /**
     * Window (ticks) where a purely unsolicited engine chunk load is considered "active pressure".
     * While pressure is active, the driver backs off entirely and does not issue new tickets.
     */
    const val CHUNK_LOAD_UNSOLICITED_PRESSURE_WINDOW_TICKS: Long = 100L

    /**
     * Expiry for COMPLETED_PENDING_PRUNE retention (ticks).
     *
     * If this expires, it indicates a logic fault (e.g. scanner never dropping demand). The driver
     * will prune and allow the chunk to re-enter as a new request, with loud observability.
     */
    const val CHUNK_LOAD_COMPLETED_PENDING_PRUNE_EXPIRE_TICKS: Long = 2400L

    // ---------------------------------------------------------------------
    // Infrastructure / Chunk loading & scanning (driver + scanner tuning)
    // ---------------------------------------------------------------------

    /**
     * Minimum OP level required to:
     * - run /memento commands
     * - receive in-game Memento debug messages
     */
    const val REQUIRED_OP_LEVEL: Int = 2

    /** Default radius (in chunks) used by /memento anchor when omitted. */
    const val DEFAULT_CHUNKS_RADIUS: Int = 2

    /** Default days used by /memento anchor forget when omitted. */
    const val DEFAULT_DAYS_TO_MATURITY: Int = 5

    /** Minecraft Overworld day length in ticks. */
    const val OVERWORLD_DAY_TICKS: Long = 24_000L

    /**
     * Conceptual "renewal checkpoint" time-of-night.
     *
     * The current implementation ages anchors by Overworld day index (time / OVERWORLD_DAY_TICKS)
     * to keep behavior predictable under sleep and /time set. This tick is kept as a documented
     * constant for future evolution and for explaining the intended story beat ("renewal at
     * night").
     */
    const val RENEWAL_CHECKPOINT_TICK: Long = 21_000L

    /** Primary persistence file for stones. */
    const val STONE_TOPOLOGY_FILE: String = "memento_stone_topology.json"

    /**
     * Optional seed file for developer testing.
     *
     * If present, this file is used for initial loading instead of the primary persistence file.
     * Normal save behavior still overwrites [STONE_TOPOLOGY_FILE].
     */
    const val STONE_TOPOLOGY_SEED_FILE: String = "memento_stone_topology_seed.json"

    /** Debug/analysis CSV snapshot produced by /memento scan (overwritten each scan). */
    const val MEMENTO_SCAN_CSV_FILE: String = "memento_world_snapshot.csv"

    /**
     * Tracer-bullet throttling: maximum chunk slots processed per server tick while /memento scan
     * is active.
     */
    const val MEMENTO_SCAN_CHUNK_SLOTS_PER_TICK: Int = 2

    /**
     * Active-scan heartbeat cadence (ticks).
     *
     * INFO-level operator telemetry only: bounded progress snapshots for convergence reasoning.
     */
    const val MEMENTO_SCAN_HEARTBEAT_EVERY_TICKS: Long = 100L

    /**
     * Maximum number of externally ingested scan metadata facts applied per server tick.
     *
     * Bounded application keeps map mutation costs predictable while preserving eventual progress.
     */
    const val MEMENTO_SCAN_METADATA_APPLIER_MAX_PER_TICK: Int = 64
}
