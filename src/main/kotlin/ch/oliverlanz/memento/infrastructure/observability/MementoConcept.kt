package ch.oliverlanz.memento.infrastructure.observability

/**
 * Canonical concept vocabulary for observability.
 *
 * These are the only allowed log tags in server logs.
 */
enum class MementoConcept {
    STONE,
    RENEWAL,
    SCANNER,
    DRIVER,
    OPERATOR,
    WORLD,
    STORAGE,
}
