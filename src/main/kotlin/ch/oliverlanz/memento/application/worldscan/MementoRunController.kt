package ch.oliverlanz.memento.application.worldscan

import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text

/**
 * /memento run controller.
 *
 * Responsibility boundaries:
 * - Orchestrates start/stop for operator commands.
 * - Owns no demand logic.
 * - Owns no chunk lifecycle callbacks.
 */
class MementoRunController(
    private val scanner: WorldScanner,
) {

    fun attach(server: MinecraftServer) {
        scanner.attach(server)
    }

    fun detach() {
        scanner.detach()
    }

    /** Entry point used by CommandHandlers. */
    fun start(source: ServerCommandSource): Int {
        if (source.server == null) {
            source.sendError(Text.literal("Memento: server not ready"))
            return 0
        }
        return scanner.start(source)
    }
}
