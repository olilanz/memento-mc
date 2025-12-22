package ch.oliverlanz.memento.application.stone

/**
 * Documents *why* stone maturity was evaluated.
 * This is purely observational and must not affect behavior.
 */
enum class StoneMaturityTrigger {
    SERVER_START,
    NIGHTLY_TICK,
    OP_COMMAND
}
