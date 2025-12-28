package ch.oliverlanz.memento.domain.renewal

/**
 * Lifecycle state of a derived RenewalBatch.
 *
 * This enum is purely domain-level.
 * It contains no execution logic and no Minecraft references.
 */
enum class RenewalBatchState {

    /**
     * Group has been derived from a matured stone,
     * but no blocking evaluation has happened yet.
     */
    MARKED,

    /**
     * One or more chunks in the group are still loaded.
     * Regeneration must not start.
     */
    BLOCKED,

    /**
     * All chunks in the group are unloaded.
     * Regeneration may start immediately.
     */
    FREE,

    /**
     * Regeneration is in progress.
     * Chunks are being observed as they renew.
     */
    FORGETTING,

    /**
     * All chunks have renewed.
     * The group is complete and can be discarded.
     */
    RENEWED
}
