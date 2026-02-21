package ch.oliverlanz.memento.domain.renewal.projection

/**
 * Lifecycle of renewal-map analysis.
 *
 * Contract:
 * - COMPUTING: incremental local recomputation from fact changes.
 * - STABILIZING: global liveliness threshold pass + decision materialization.
 * - STABLE: externally readable snapshot/decision; CSV/commands may consume it.
 */
enum class RenewalAnalysisState {
    COMPUTING,
    STABILIZING,
    STABLE,
}
