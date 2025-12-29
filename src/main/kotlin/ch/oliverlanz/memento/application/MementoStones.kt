package ch.oliverlanz.memento.application

/**
 * Legacy compatibility surface.
 *
 * The old implementation used a much larger MementoStones model.
 * The new implementation (domain/stones) is authoritative.
 *
 * We keep this type only because the command grammar historically used it
 * (e.g. /memento list witherstone|lorestone). Nothing in the new code should
 * depend on legacy stone behaviour.
 */
object MementoStones {

    enum class Kind {
        FORGET,
        REMEMBER
    }

    fun legacyApi(): Nothing {
        error("Legacy MementoStones API must not be used")
    }
}
