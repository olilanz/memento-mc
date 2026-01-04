package ch.oliverlanz.memento.domain.renewal

/**
 * Why did a renewal batch advance or get created?
 *
 * This is used for observability + decision tracing only.
 */
enum class RenewalTrigger {
    /** System-driven (topology reconciliation, automatic derivation, internal maintenance). */
    SYSTEM,
    NIGHTLY_TICK,
    CHUNK_UNLOAD,
    CHUNK_LOAD,
    PROACTIVE_RENEWAL_TICK,
    MANUAL,
    INITIAL_SNAPSHOT
}
