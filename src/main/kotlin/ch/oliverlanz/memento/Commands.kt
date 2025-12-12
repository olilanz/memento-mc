package ch.oliverlanz.memento

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource

object Commands {

    fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {

        dispatcher.register(
            CommandManager.literal("memento")
                .then(CommandManager.literal("info")
                    .executes { ctx ->
                        val sender = ctx.source
                        val anchors = Anchors.getAll()

                        if (anchors.isEmpty()) {
                            sender.sendFeedback({ net.minecraft.text.Text.of("No anchors set.") }, false)
                        } else {
                            anchors.forEach { a ->
                                sender.sendFeedback(
                                    {
                                        net.minecraft.text.Text.of(
                                            "- ${a.type} at ${a.pos.x}, ${a.pos.y}, ${a.pos.z} in ${a.worldRegistryKey.value}"
                                        )
                                    },
                                    false
                                )
                            }
                        }

                        1
                    }
                )
        )
    }
}
