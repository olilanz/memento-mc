package ch.oliverlanz.memento

import ch.oliverlanz.memento.application.CommandHandlers
import ch.oliverlanz.memento.infrastructure.MementoConstants
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource

object Commands {

    fun register() {
        CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher, _, _ ->
            register(dispatcher)
        })
    }

    // Grammar is authoritative. Do not change without explicit agreement.
    private fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        dispatcher.register(
            literal("memento")
                .requires { it.hasPermissionLevel(MementoConstants.REQUIRED_OP_LEVEL) }

                /* ======================
                 * LIST
                 * ====================== */
                .then(literal("list")
                    .executes { CommandHandlers.list(null, it.source) }
                    .then(literal("witherstone").executes { CommandHandlers.list(CommandHandlers.StoneKind.WITHERSTONE, it.source) })
                    .then(literal("lorestone").executes { CommandHandlers.list(CommandHandlers.StoneKind.LORESTONE, it.source) })
                )

                /* ======================
                 * INSPECT
                 * ====================== */
                .then(literal("inspect")
                    .then(argument("name", StringArgumentType.word())
                        .executes { ctx ->
                            CommandHandlers.inspect(ctx.source, StringArgumentType.getString(ctx, "name"))
                        }
                    )
                )

                /* ======================
                 * ADD
                 * ====================== */
                .then(literal("add")

                    // witherstone: name [radius] [daysToMaturity]
                    .then(literal("witherstone")
                        .then(argument("name", StringArgumentType.word())
                            .executes { ctx ->
                                CommandHandlers.addWitherstone(
                                    ctx.source,
                                    StringArgumentType.getString(ctx, "name"),
                                    MementoConstants.DEFAULT_CHUNKS_RADIUS,
                                    MementoConstants.DEFAULT_DAYS_TO_MATURITY
                                )
                            }
                            .then(argument("radius", IntegerArgumentType.integer(0, 10))
                                .executes { ctx ->
                                    CommandHandlers.addWitherstone(
                                        ctx.source,
                                        StringArgumentType.getString(ctx, "name"),
                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                        MementoConstants.DEFAULT_DAYS_TO_MATURITY
                                    )
                                }
                                .then(argument("daysToMaturity", IntegerArgumentType.integer(0, 10))
                                    .executes { ctx ->
                                        CommandHandlers.addWitherstone(
                                            ctx.source,
                                            StringArgumentType.getString(ctx, "name"),
                                            IntegerArgumentType.getInteger(ctx, "radius"),
                                            IntegerArgumentType.getInteger(ctx, "daysToMaturity")
                                        )
                                    }
                                )
                            )
                        )
                    )

                    // lorestone: name [radius]
                    .then(literal("lorestone")
                        .then(argument("name", StringArgumentType.word())
                            .executes { ctx ->
                                CommandHandlers.addLorestone(
                                    ctx.source,
                                    StringArgumentType.getString(ctx, "name"),
                                    MementoConstants.DEFAULT_CHUNKS_RADIUS
                                )
                            }
                            .then(argument("radius", IntegerArgumentType.integer(0, 10))
                                .executes { ctx ->
                                    CommandHandlers.addLorestone(
                                        ctx.source,
                                        StringArgumentType.getString(ctx, "name"),
                                        IntegerArgumentType.getInteger(ctx, "radius")
                                    )
                                }
                            )
                        )
                    )
                )

                /* ======================
                 * REMOVE
                 * ====================== */
                .then(literal("remove")
                    .then(literal("witherstone")
                        .then(argument("name", StringArgumentType.word())
                            .executes { ctx ->
                                CommandHandlers.remove(ctx.source, StringArgumentType.getString(ctx, "name"))
                            }
                        )
                    )
                    .then(literal("lorestone")
                        .then(argument("name", StringArgumentType.word())
                            .executes { ctx ->
                                CommandHandlers.remove(ctx.source, StringArgumentType.getString(ctx, "name"))
                            }
                        )
                    )
                )

                /* ======================
                 * SET
                 * ====================== */
                .then(literal("set")

                    // witherstone attributes
                    .then(literal("witherstone")
                        .then(argument("name", StringArgumentType.word())
                            .then(literal("radius")
                                .then(argument("value", IntegerArgumentType.integer(0, 10))
                                    .executes { ctx ->
                                        CommandHandlers.setRadius(
                                            ctx.source,
                                            StringArgumentType.getString(ctx, "name"),
                                            IntegerArgumentType.getInteger(ctx, "value")
                                        )
                                    }
                                )
                            )
                            .then(literal("daysToMaturity")
                                .then(argument("value", IntegerArgumentType.integer(0, 10))
                                    .executes { ctx ->
                                        CommandHandlers.setDaysToMaturity(
                                            ctx.source,
                                            StringArgumentType.getString(ctx, "name"),
                                            IntegerArgumentType.getInteger(ctx, "value")
                                        )
                                    }
                                )
                            )
                        )
                    )

                    // lorestone attributes
                    .then(literal("lorestone")
                        .then(argument("name", StringArgumentType.word())
                            .then(literal("radius")
                                .then(argument("value", IntegerArgumentType.integer(0, 10))
                                    .executes { ctx ->
                                        CommandHandlers.setRadius(
                                            ctx.source,
                                            StringArgumentType.getString(ctx, "name"),
                                            IntegerArgumentType.getInteger(ctx, "value")
                                        )
                                    }
                                )
                            )
                        )
                    )
                )
        )
    }
}