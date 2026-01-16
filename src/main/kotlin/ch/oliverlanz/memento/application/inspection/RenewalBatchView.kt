package ch.oliverlanz.memento.application.inspection

/**
 * Application-facing alias for the domain RenewalBatchView.
 *
 * Inspection and visualization consume this read-only view, while the domain
 * remains authoritative for lifecycle and execution semantics.
 */
typealias RenewalBatchView = ch.oliverlanz.memento.domain.renewal.RenewalBatchView
