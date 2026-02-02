package ch.oliverlanz.memento.infrastructure.observability

import ch.oliverlanz.memento.MementoConstants
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text

/**
 * Operator-facing message channel.
 *
 * - Sends natural language messages to online ops.
 * - Mirrors the same message into INFO logs under the [OPERATOR] concept.
 */
object OperatorMessages {

    fun info(server: MinecraftServer?, message: String) {
        MementoLog.info(MementoConcept.OPERATOR, message)
        notifyOps(server, message)
    }

    fun warn(server: MinecraftServer?, message: String) {
        MementoLog.warn(MementoConcept.OPERATOR, message)
        notifyOps(server, "⚠ $message")
    }

    fun error(server: MinecraftServer?, message: String, t: Throwable? = null) {
        if (t == null) {
            MementoLog.error(MementoConcept.OPERATOR, message)
        } else {
            MementoLog.error(MementoConcept.OPERATOR, message, t)
        }
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
