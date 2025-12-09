package ch.oliverlanz.memento

import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text

object Commands {

    fun register() {
        CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher, _, _ ->

            // /memento info
            dispatcher.register(
                CommandManager.literal("memento")
                    .then(
                        CommandManager.literal("info")
                            .executes { ctx -> info(ctx) }
                    )
            )
        })

        Memento.log.info { "Registered /memento info command" }
    }

    private fun info(ctx: CommandContext<ServerCommandSource>): Int {
        val source = ctx.source
        val player = source.player ?: run {
            source.sendFeedback({ Text.literal("Must be a player to run this command.") }, false)
            return 1
        }

        val world = player.entityWorld
        val pos = player.blockPos
        val chunk = world.getChunk(pos)
        val chunkPos = chunk.pos
        val dim = world.registryKey.value.toString()

        val msg = """
        §6Memento Info:
        §fPlayer: §e${player.name.string}
        §fDimension: §e$dim
        §fPlayer Position: §e${pos.x}, ${pos.y}, ${pos.z}
        §fChunk: §e(${chunkPos.x}, ${chunkPos.z})
    """.trimIndent()

        source.sendFeedback({ Text.literal(msg) }, false)
        return 1
    }
}
