package ch.oliverlanz.memento

import ch.oliverlanz.memento.infrastructure.MementoConstants
import ch.oliverlanz.memento.application.MementoAnchors
import ch.oliverlanz.memento.application.land.ChunkGroupForgetting
import ch.oliverlanz.memento.application.land.ChunkInspection
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
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
                .requires { it.hasPermissionLevel(MementoConstants.REQUIRED_OP_LEVEL) }

                /* ======================
                 * LIST
                 * ====================== */
                .then(literal("list")
                    .executes { list(null, it.source) }
                    .then(literal("witherstone").executes { list(MementoAnchors.Kind.FORGET, it.source) })
                    .then(literal("lorestone").executes { list(MementoAnchors.Kind.REMEMBER, it.source) })
                )

                /* ======================
                 * INSPECT
                 * ====================== */
                .then(literal("inspect")
                    .then(argument("name", StringArgumentType.word())
                        .executes { ctx ->
                            inspect(ctx.source, StringArgumentType.getString(ctx, "name"))
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
                                addWitherstone(
                                    ctx.source,
                                    StringArgumentType.getString(ctx, "name"),
                                    MementoConstants.DEFAULT_RADIUS_CHUNKS,
                                    MementoConstants.DEFAULT_FORGET_DAYS
                                )
                            }
                            .then(argument("radius", IntegerArgumentType.integer(1, 10))
                                .executes { ctx ->
                                    addWitherstone(
                                        ctx.source,
                                        StringArgumentType.getString(ctx, "name"),
                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                        MementoConstants.DEFAULT_FORGET_DAYS
                                    )
                                }
                                .then(argument("daysToMaturity", IntegerArgumentType.integer(0, 10))
                                    .executes { ctx ->
                                        addWitherstone(
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
                                addLorestone(
                                    ctx.source,
                                    StringArgumentType.getString(ctx, "name"),
                                    MementoConstants.DEFAULT_RADIUS_CHUNKS
                                )
                            }
                            .then(argument("radius", IntegerArgumentType.integer(1, 10))
                                .executes { ctx ->
                                    addLorestone(
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
                                remove(ctx.source, StringArgumentType.getString(ctx, "name"))
                            }
                        )
                    )
                    .then(literal("lorestone")
                        .then(argument("name", StringArgumentType.word())
                            .executes { ctx ->
                                remove(ctx.source, StringArgumentType.getString(ctx, "name"))
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
                                .then(argument("value", IntegerArgumentType.integer(1, 10))
                                    .executes { ctx ->
                                        setRadius(
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
                                        setDaysToMaturity(
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
                                .then(argument("value", IntegerArgumentType.integer(1, 10))
                                    .executes { ctx ->
                                        setRadius(
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

    /* ============================================================
     * Dispatch helpers (NO parsing logic below this line)
     * ============================================================ */

    private fun addWitherstone(
        src: ServerCommandSource,
        name: String,
        radius: Int,
        daysToMaturity: Int
    ): Int {
        val pos = BlockPos.ofFloored(src.position)
        val world = src.world

        MementoAnchors.addOrReplace(
            MementoAnchors.Anchor(
                name = name,
                kind = MementoAnchors.Kind.FORGET,
                dimension = world.registryKey,
                pos = pos,
                radius = radius,
                days = daysToMaturity,
                state =
                    if (daysToMaturity == 0)
                        MementoAnchors.WitherstoneState.MATURED
                    else
                        MementoAnchors.WitherstoneState.MATURING,
                createdGameTime = world.time
            )
        )

        src.sendFeedback(
            { Text.literal("Witherstone '$name' placed (radius=$radius, daysToMaturity=$daysToMaturity)") },
            false
        )
        return 1
    }

    private fun addLorestone(
        src: ServerCommandSource,
        name: String,
        radius: Int
    ): Int {
        val pos = BlockPos.ofFloored(src.position)
        val world = src.world

        MementoAnchors.addOrReplace(
            MementoAnchors.Anchor(
                name = name,
                kind = MementoAnchors.Kind.REMEMBER,
                dimension = world.registryKey,
                pos = pos,
                radius = radius,
                days = null,
                state = null,
                createdGameTime = world.time
            )
        )

        src.sendFeedback(
            { Text.literal("Lorestone '$name' placed (radius=$radius)") },
            false
        )
        return 1
    }

    private fun remove(src: ServerCommandSource, name: String): Int {
        val removed = MementoAnchors.remove(name)

        // Safe no-op if no group exists
        ChunkGroupForgetting.discardGroup(name)

        src.sendFeedback(
            { Text.literal(if (removed) "Removed '$name'" else "No such stone '$name'") },
            false
        )
        return 1
    }

    private fun setDaysToMaturity(
        src: ServerCommandSource,
        name: String,
        value: Int
    ): Int {
        val anchor = MementoAnchors.get(name)
            ?: return error(src, "No such witherstone '$name'")

        if (anchor.kind != MementoAnchors.Kind.FORGET) {
            return error(src, "'$name' is not a witherstone")
        }

        // Re-arming: matured -> maturing must discard derived group
        if (anchor.state == MementoAnchors.WitherstoneState.MATURED && value > 0) {
            ChunkGroupForgetting.discardGroup(name)
        }

        MementoAnchors.addOrReplace(
            anchor.copy(
                days = value,
                state =
                    if (value == 0)
                        MementoAnchors.WitherstoneState.MATURED
                    else
                        MementoAnchors.WitherstoneState.MATURING
            )
        )

        src.sendFeedback(
            { Text.literal("Witherstone '$name' daysToMaturity set to $value") },
            false
        )
        return 1
    }

    private fun setRadius(
        src: ServerCommandSource,
        name: String,
        radius: Int
    ): Int {
        val anchor = MementoAnchors.get(name)
            ?: return error(src, "No such stone '$name'")

        MementoAnchors.addOrReplace(anchor.copy(radius = radius))

        src.sendFeedback(
            { Text.literal("Stone '$name' radius set to $radius") },
            false
        )
        return 1
    }

    private fun inspect(
        src: ServerCommandSource,
        name: String
    ): Int {
        val group = ChunkGroupForgetting.getGroupByAnchorName(name)
            ?: run {
                src.sendError(Text.literal("No active chunk group for '$name'"))
                return 0
            }

        val reports = ChunkInspection.inspectGroup(src.server, group)

        if (reports.isEmpty()) {
            src.sendFeedback(
                { Text.literal("Nothing is blocking regeneration for '$name'") },
                false
            )
            return 1
        }

        for (r in reports) {
            src.sendFeedback(
                {
                    Text.literal(
                        "chunk (${r.pos.x}, ${r.pos.z}) in ${r.dimension.value}: ${r.summary}"
                    )
                },
                false
            )

            for (b in r.blockers) {
                src.sendFeedback(
                    { Text.literal("  - $b") },
                    false
                )
            }
        }
        return 1
    }

    private fun list(
        kind: MementoAnchors.Kind?,
        src: ServerCommandSource
    ): Int {
        val stones = MementoAnchors.list()
            .filter { kind == null || it.kind == kind }

        if (stones.isEmpty()) {
            src.sendFeedback({ Text.literal("No stones found.") }, false)
            return 1
        }

        stones.forEach { a ->
            val extra =
                if (a.kind == MementoAnchors.Kind.FORGET)
                    " daysToMaturity=${a.days}"
                else
                    ""

            src.sendFeedback(
                { Text.literal("${a.name}: ${a.kind} at ${a.pos} radius=${a.radius}$extra") },
                false
            )
        }
        return 1
    }

    private fun error(src: ServerCommandSource, msg: String): Int {
        src.sendError(Text.literal(msg))
        return 0
    }
}
