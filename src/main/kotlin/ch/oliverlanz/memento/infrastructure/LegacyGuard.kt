package ch.oliverlanz.memento.infrastructure

/**
 * Guard rails used during the authority handover from legacy -> new implementation.
 *
 * Any call into legacy lifecycle code should be treated as a wiring bug.
 *
 * This is intentionally loud (throws) so we discover accidental legacy usage early.
 */
object LegacyGuard {

    fun fail(path: String): Nothing {
        error("LEGACY PATH INVOKED: $path. This indicates incorrect wiring; the new implementation must be authoritative.")
    }
}
