package ch.oliverlanz.memento

/**
 * Centralized tuning and policy constants for Memento.
 *
 * These constants express *intent* and *operational policy*.
 * They must not encode algorithmic mechanics or hidden behavior.
 */
object MementoConstants {

    /* ---------------------------------------------------------------------
     * Chunk loading / driver pacing
     * ------------------------------------------------------------------ */

    /**
     * Minimum delay (ticks) between proactive chunk load requests.
     *
     * This is a hard safety clamp only. The adaptive EWMA is expected
     * to operate well above this value under normal conditions.
     */
    const val CHUNK_LOAD_MIN_DELAY_TICKS: Long = 1L

    /**
     * Maximum delay (ticks) between proactive chunk load requests.
     *
     * Allows the driver to back off aggressively under sustained pressure.
     */
    const val CHUNK_LOAD_MAX_DELAY_TICKS: Long = 1200L

    /**
     * EWMA weight for historical latency samples.
     * Higher values make the driver more stable but slower to react.
     */
    const val CHUNK_LOAD_EWMA_WEIGHT_OLD: Double = 0.7

    /**
     * EWMA weight for new latency samples.
     */
    const val CHUNK_LOAD_EWMA_WEIGHT_NEW: Double = 0.3

    /**
     * Maximum latency sample (ticks) considered for EWMA.
     * Prevents pathological spikes from dominating pacing.
     */
    const val CHUNK_LOAD_EWMA_MAX_SAMPLE_TICKS: Long = 20_000L

    /* ---------------------------------------------------------------------
     * Re-arming behavior for raced chunk load signals
     * ------------------------------------------------------------------ */

    /**
     * Delay before re-arming a pending load after an early engine signal.
     */
    const val CHUNK_LOAD_REARM_DELAY_TICKS: Long = 10L

    /**
     * Base backoff between re-arm attempts.
     * Exponential backoff is applied on top.
     */
    const val CHUNK_LOAD_REARM_BACKOFF_TICKS: Long = 20L

    /**
     * Maximum number of re-arm attempts for a pending load.
     */
    const val CHUNK_LOAD_REARM_MAX_ATTEMPTS: Int = 3

    /**
     * Maximum number of chunk load forwards per tick.
     * Protects against long tick stalls under bursty conditions.
     */
    const val CHUNK_LOAD_MAX_FORWARDED_PER_TICK: Int = 32

    /**
     * Radius (in chunks) for the driver's temporary tickets.
     *
     * We keep this conservative (1) to remain polite and to avoid loading large
     * regions accidentally. Throughput comes from single-flight + overlap, not radius.
     */
    const val CHUNK_LOAD_TICKET_RADIUS: Int = 1

    /**
     * State log cadence (ticks) for driver DEBUG state lines.
     *
     * Keep frequent enough to diagnose stalls, but not noisy under normal operation.
     */
    const val CHUNK_LOAD_STATE_LOG_EVERY_TICKS: Long = 100L
}

