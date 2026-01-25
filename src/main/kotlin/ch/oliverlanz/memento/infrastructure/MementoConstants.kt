package ch.oliverlanz.memento.infrastructure

/**
 * Central constants for Memento.
 *
 * Keeping operational knobs in one place avoids magic numbers scattered
 * across commands and runtime logic, and makes future tuning explicit.
 */
object MementoConstants {

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

    /** Regeneration pacing: process one chunk every N server ticks. Mincraft does 20 ticks per second. */
    const val REGENERATION_CHUNK_INTERVAL_TICKS: Int = 8

    /** Chunk load driver: while ACTIVE, request at most one chunk load every N server ticks. */
    const val CHUNK_LOAD_ACTIVE_INTERVAL_TICKS: Int = REGENERATION_CHUNK_INTERVAL_TICKS

    /** Chunk load driver: after any chunk load is observed, remain PASSIVE for N ticks before becoming ACTIVE again. */
    const val CHUNK_LOAD_PASSIVE_GRACE_TICKS: Int = 60

    /**
     * Conceptual "renewal checkpoint" time-of-night.
     *
     * The current implementation ages anchors by Overworld day index
     * (time / OVERWORLD_DAY_TICKS) to keep behavior predictable under sleep
     * and /time set. This tick is kept as a documented constant for future
     * evolution and for explaining the intended story beat ("renewal at night").
     */
    const val RENEWAL_CHECKPOINT_TICK: Long = 21_000L

    /** Primary persistence file for stones. */
    const val STONE_TOPOLOGY_FILE: String = "memento_stone_topology.json"

    /**
     * Optional seed file for developer testing.
     *
     * If present, this file is used for initial loading instead of the
     * primary persistence file. Normal save behavior still overwrites
     * [STONE_TOPOLOGY_FILE].
     */
    const val STONE_TOPOLOGY_SEED_FILE: String = "memento_stone_topology_seed.json"

    /** Debug/analysis CSV snapshot produced by /memento run (overwritten each run). */
    const val MEMENTO_RUN_CSV_FILE: String = "memento_world_snapshot.csv"

    /** Tracer-bullet throttling: maximum chunk slots processed per server tick while /memento run is active. */
    const val MEMENTO_RUN_CHUNK_SLOTS_PER_TICK: Int = 2
}
