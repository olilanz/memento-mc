package ch.oliverlanz.memento

import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import org.slf4j.LoggerFactory

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

    private val logger = LoggerFactory.getLogger("memento")

    fun info(server: MinecraftServer?, message: String) {
        logger.info(message)
        notifyOps(server, message)
    }

    fun warn(server: MinecraftServer?, message: String) {
        logger.warn(message)
        notifyOps(server, "⚠ $message")
    }

    fun error(server: MinecraftServer?, message: String) {
        logger.error(message)
        notifyOps(server, "✖ $message")
    }

    private fun notifyOps(server: MinecraftServer?, message: String) {
        if (server == null) return

        val text = Text.literal("[Memento] $message")
        for (player in server.playerManager.playerList) {
            if (isOp(player)) {
                // Use chat (not actionbar) so it can be reviewed in the chat history.
                player.sendMessage(text, false)
            }
        }
    }

    private fun isOp(player: ServerPlayerEntity): Boolean {
        return player.hasPermissionLevel(MementoConstants.REQUIRED_OP_LEVEL)
    }
}
