package ch.oliverlanz.memento

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.command.argument.BlockPosArgumentType
import net.minecraft.server.command.CommandManager.*
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos

object Commands {

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            register(dispatcher)
        }
    }

    private fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {

        dispatcher.register(
            literal("memento")
                .requires { it.hasPermissionLevel(2) }

                // /memento remember <name> [x y z] [radius]
                .then(literal("remember")
                    .then(argument("name", StringArgumentType.word())
                        .executes { ctx ->
                            remember(
                                ctx.source,
                                StringArgumentType.getString(ctx, "name"),
                                null,
                                MementoAnchors.DEFAULT_RADIUS
                            )
                        }
                        .then(argument("pos", BlockPosArgumentType.blockPos())
                            .executes { ctx ->
                                remember(
                                    ctx.source,
                                    StringArgumentType.getString(ctx, "name"),
                                    BlockPosArgumentType.getBlockPos(ctx, "pos"),
                                    MementoAnchors.DEFAULT_RADIUS
                                )
                            }
                            .then(argument("radius", IntegerArgumentType.integer(0))
                                .executes { ctx ->
                                    remember(
                                        ctx.source,
                                        StringArgumentType.getString(ctx, "name"),
                                        BlockPosArgumentType.getBlockPos(ctx, "pos"),
                                        IntegerArgumentType.getInteger(ctx, "radius")
                                    )
                                }
                            )
                        )
                    )
                )

                // /memento forget <name> [x y z] [radius] [days]
                .then(literal("forget")
                    .then(argument("name", StringArgumentType.word())
                        .executes { ctx ->
                            forget(
                                ctx.source,
                                StringArgumentType.getString(ctx, "name"),
                                null,
                                MementoAnchors.DEFAULT_RADIUS,
                                MementoAnchors.DEFAULT_DAYS
                            )
                        }
                        .then(argument("pos", BlockPosArgumentType.blockPos())
                            .executes { ctx ->
                                forget(
                                    ctx.source,
                                    StringArgumentType.getString(ctx, "name"),
                                    BlockPosArgumentType.getBlockPos(ctx, "pos"),
                                    MementoAnchors.DEFAULT_RADIUS,
                                    MementoAnchors.DEFAULT_DAYS
                                )
                            }
                            .then(argument("radius", IntegerArgumentType.integer(0))
                                .executes { ctx ->
                                    forget(
                                        ctx.source,
                                        StringArgumentType.getString(ctx, "name"),
                                        BlockPosArgumentType.getBlockPos(ctx, "pos"),
                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                        MementoAnchors.DEFAULT_DAYS
                                    )
                                }
                                .then(argument("days", IntegerArgumentType.integer(-1))
                                    .executes { ctx ->
                                        forget(
                                            ctx.source,
                                            StringArgumentType.getString(ctx, "name"),
                                            BlockPosArgumentType.getBlockPos(ctx, "pos"),
                                            IntegerArgumentType.getInteger(ctx, "radius"),
                                            IntegerArgumentType.getInteger(ctx, "days")
                                        )
                                    }
                                )
                            )
                        )
                    )
                )

                // /memento cancel <name>
                .then(literal("cancel")
                    .then(argument("name", StringArgumentType.word())
                        .executes { ctx ->
                            cancel(ctx.source, StringArgumentType.getString(ctx, "name"))
                        }
                    )
                )

                // /memento list
                .then(literal("list")
                    .executes { ctx -> list(ctx.source) }
                )

                // /memento info
                .then(literal("info")
                    .executes { ctx -> info(ctx.source) }
                )
        )
    }

    private fun remember(
        source: ServerCommandSource,
        name: String,
        posArg: BlockPos?,
        radius: Int
    ): Int {
        val world = source.world
        val player = source.playerOrThrow
        val pos = posArg ?: player.blockPos

        val anchor = MementoAnchors.Anchor(
            name = name,
            kind = MementoAnchors.Kind.REMEMBER,
            dimension = world.registryKey,
            pos = pos,
            radius = radius,
            days = null,
            createdGameTime = world.time
        )

        if (!MementoAnchors.add(anchor)) {
            source.sendError(Text.literal("Memento '$name' already exists"))
            return 0
        }

        // Persist immediately on change
        MementoPersistence.save(source.server)

        source.sendFeedback(
            { Text.literal("Remember memento '$name' set at $pos (r=$radius)") },
            false
        )
        return 1
    }

    private fun forget(
        source: ServerCommandSource,
        name: String,
        posArg: BlockPos?,
        radius: Int,
        days: Int
    ): Int {
        val world = source.world
        val player = source.playerOrThrow
        val pos = posArg ?: player.blockPos

        val anchor = MementoAnchors.Anchor(
            name = name,
            kind = MementoAnchors.Kind.FORGET,
            dimension = world.registryKey,
            pos = pos,
            radius = radius,
            days = days,
            createdGameTime = world.time
        )

        if (!MementoAnchors.add(anchor)) {
            source.sendError(Text.literal("Memento '$name' already exists"))
            return 0
        }

        MementoPersistence.save(source.server)

        val daysText = if (days == -1) "days=immediate" else "days=$days"
        source.sendFeedback(
            { Text.literal("Forget memento '$name' set at $pos (r=$radius, $daysText)") },
            false
        )
        return 1
    }

    private fun cancel(source: ServerCommandSource, name: String): Int {
        val removed = MementoAnchors.remove(name)
        if (!removed) {
            source.sendError(Text.literal("No such memento: $name"))
            return 0
        }

        MementoPersistence.save(source.server)

        source.sendFeedback({ Text.literal("Memento '$name' canceled") }, false)
        return 1
    }

    private fun list(source: ServerCommandSource): Int {
        val anchors = MementoAnchors.list().sortedBy { it.name.lowercase() }

        if (anchors.isEmpty()) {
            source.sendFeedback({ Text.literal("No mementos defined") }, false)
            return 1
        }

        for (a in anchors) {
            val kind = if (a.kind == MementoAnchors.Kind.REMEMBER) "remember" else "forget"
            val extra = if (a.kind == MementoAnchors.Kind.FORGET) {
                val d = a.days ?: MementoAnchors.DEFAULT_DAYS
                val daysText = if (d == -1) "immediate" else d.toString()
                ", days=$daysText"
            } else ""

            source.sendFeedback(
                {
                    Text.literal(
                        "- ${a.name} ($kind) @ ${a.dimension.value} ${a.pos} r=${a.radius}$extra"
                    )
                },
                false
            )
        }
        return 1
    }

    private fun info(source: ServerCommandSource): Int {
        val total = MementoAnchors.list().size
        val remember = MementoAnchors.list().count { it.kind == MementoAnchors.Kind.REMEMBER }
        val forget = MementoAnchors.list().count { it.kind == MementoAnchors.Kind.FORGET }

        source.sendFeedback(
            { Text.literal("Memento debug: total=$total (remember=$remember, forget=$forget). Persistence: memento_anchors.json") },
            false
        )
        return 1
    }
}
