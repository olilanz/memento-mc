package ch.oliverlanz.memento.domain.renewal.projection

/**
 * Deterministic projection cycle boundary payloads for testability seams.
 *
 * Boundary-only observability contract:
 * - Exposes execution-cycle phases.
 * - Does not transfer projection policy authority.
 * - Carries committed immutable snapshot at commit completion.
 */
data class ProjectionDispatchStart(
    val generation: Long,
    val reason: String,
    val sourceDirtySeed: List<RegionKey>,
    val fullSnapshotRecovery: Boolean,
    val startedAtMs: Long,
)

data class ProjectionCommitCompleted(
    val generation: Long,
    val reason: String,
    val durationMs: Long,
    val processedAffectedRegionWindow: List<RegionKey>,
    val overflowRemainder: List<RegionKey>,
    val committedView: RenewalPublishedView,
)

