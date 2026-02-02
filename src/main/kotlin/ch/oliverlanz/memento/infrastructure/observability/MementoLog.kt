package ch.oliverlanz.memento.infrastructure.observability

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Centralized logging facade to enforce:
 * - fixed module prefix: [MEMENTO]
 * - exactly one canonical concept tag: [STONE], [DRIVER], ...
 */
object MementoLog {

    private val logger: Logger = LoggerFactory.getLogger("memento")

    private fun prefix(concept: MementoConcept): String = "[MEMENTO][${concept.name}]"

    fun info(concept: MementoConcept, message: String, vararg args: Any?) {
        logger.info("${prefix(concept)} $message", *args)
    }

    fun warn(concept: MementoConcept, message: String, vararg args: Any?) {
        logger.warn("${prefix(concept)} $message", *args)
    }

    fun warn(concept: MementoConcept, message: String, throwable: Throwable) {
        logger.warn("${prefix(concept)} $message", throwable)
    }

    fun error(concept: MementoConcept, message: String, vararg args: Any?) {
        logger.error("${prefix(concept)} $message", *args)
    }

    fun error(concept: MementoConcept, message: String, t: Throwable, vararg args: Any?) {
        logger.error("${prefix(concept)} $message", *args, t)
    }

    fun debug(concept: MementoConcept, message: String, vararg args: Any?) {
        logger.debug("${prefix(concept)} $message", *args)
    }

    fun trace(concept: MementoConcept, message: String, vararg args: Any?) {
        logger.trace("${prefix(concept)} $message", *args)
    }
}
