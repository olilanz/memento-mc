package ch.oliverlanz.memento.application.land

/**
 * Legacy artifact retained for reference while the new domain renewal tracker becomes authoritative.
 *
 * This class is intentionally non-functional in the current branch.
 * If it is ever invoked, that indicates legacy wiring is still active and should be removed.
 */
object RenewalBatchForgetting {

    @JvmStatic
    fun legacyInvoked(): Nothing {
        error("Legacy RenewalBatchForgetting invoked. New domain implementation should be authoritative.")
    }
}
