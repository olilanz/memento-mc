package ch.oliverlanz.memento.infrastructure

import net.minecraft.server.MinecraftServer

/**
 * Centralized debug / lifecycle notification channel.
 *
 * Rationale:
 * - Server logs are useful, but during in-game testing it is much easier to follow
 *   the lifecycle by surfacing high-signal events directly to operators.
 * - We intentionally keep this channel *sparse* to avoid chat spam.
 *
 * This helper always logs to the server log and additionally mirrors messages to
 * online OPs with permission level >= [MementoConstants.REQUIRED_OP_LEVEL].
 */
object MementoDebug {

    /**
     * Backwards-compatible wrapper. Prefer [OperatorMessages] for new call sites.
     */
    fun info(server: MinecraftServer?, message: String) {
        ch.oliverlanz.memento.infrastructure.observability.OperatorMessages.info(server, message)
    }

    fun warn(server: MinecraftServer?, message: String) {
        ch.oliverlanz.memento.infrastructure.observability.OperatorMessages.warn(server, message)
    }

    fun error(server: MinecraftServer?, message: String) {
        ch.oliverlanz.memento.infrastructure.observability.OperatorMessages.error(server, message)
    }
}
