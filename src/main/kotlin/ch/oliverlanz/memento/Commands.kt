package ch.oliverlanz.memento

import ch.oliverlanz.memento.application.MementoStones
import ch.oliverlanz.memento.application.land.ChunkInspection
import ch.oliverlanz.memento.application.land.RenewalBatchForgetting
import ch.oliverlanz.memento.domain.stones.Lorestone
import ch.oliverlanz.memento.domain.stones.StoneRegister
import ch.oliverlanz.memento.domain.stones.Witherstone
import ch.oliverlanz.memento.infrastructure.MementoConstants
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
                    .then(literal("witherstone").executes { list(MementoStones.Kind.FORGET, it.source) })
                    .then(literal("lorestone").executes { list(MementoStones.Kind.REMEMBER, it.source) })
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
                                    MementoConstants.DEFAULT_CHUNKS_RADIUS,
                                    MementoConstants.DEFAULT_DAYS_TO_MATURITY
                                )
                            }
                            .then(argument("radius", IntegerArgumentType.integer(1, 10))
                                .executes { ctx ->
                                    addWitherstone(
                                        ctx.source,
                                        StringArgumentType.getString(ctx, "name"),
                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                        MementoConstants.DEFAULT_DAYS_TO_MATURITY
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
                                    MementoConstants.DEFAULT_DAYS_TO_MATURITY
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
                    .then(argument("name", StringArgumentType.word())
                        .executes { ctx ->
                            remove(ctx.source, StringArgumentType.getString(ctx, "name"))
                        }
                    )
                )

                /* ======================
                 * SET (legacy only)
                 * ====================== */
                .then(literal("set")
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
                )
        )
    }

    /* ============================================================
     * Dispatch helpers
     * ============================================================ */

    private fun addWitherstone(
        src: ServerCommandSource,
        name: String,
        radius: Int,
        daysToMaturity: Int
    ): Int {
        val pos = BlockPos.ofFloored(src.position)
        val world = src.world

        // --- Legacy (authoritative) ---
        MementoStones.addOrReplace(
            MementoStones.Stone(
                name = name,
                kind = MementoStones.Kind.FORGET,
                dimension = world.registryKey,
                pos = pos,
                radius = radius,
                days = daysToMaturity,
                state =
                    if (daysToMaturity == 0)
                        MementoStones.WitherstoneState.MATURED
                    else
                        MementoStones.WitherstoneState.MATURING,
                createdGameTime = world.time
            )
        )

        // --- Shadow (new) ---
        StoneRegister.add(
            Witherstone(
                name = name,
                position = pos,
                daysToMaturity = daysToMaturity
            ).also { it.radius = radius }
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

        // --- Legacy (authoritative) ---
        MementoStones.addOrReplace(
            MementoStones.Stone(
                name = name,
                kind = MementoStones.Kind.REMEMBER,
                dimension = world.registryKey,
                pos = pos,
                radius = radius,
                days = null,
                state = null,
                createdGameTime = world.time
            )
        )

        // --- Shadow (new) ---
        StoneRegister.add(
            Lorestone(
                name = name,
                position = pos
            ).also { it.radius = radius }
        )

        src.sendFeedback(
            { Text.literal("Lorestone '$name' placed (radius=$radius)") },
            false
        )
        return 1
    }

    private fun remove(src: ServerCommandSource, name: String): Int {
        val removed = MementoStones.remove(name)

        // Legacy cleanup
        RenewalBatchForgetting.discardGroup(name)

        // Shadow cleanup
        StoneRegister.remove(name)

        src.sendFeedback(
            { Text.literal(if (removed) "Removed '$name'" else "No such stone '$name'") },
            false
        )
        return 1
    }

    /* ============================================================
     * Legacy helpers below (unchanged)
     * ============================================================ */

    private fun setDaysToMaturity(
        src: ServerCommandSource,
        name: String,
        value: Int
    ): Int {
        val stone = MementoStones.get(name)
            ?: return error(src, "No such witherstone '$name'")

        if (stone.kind != MementoStones.Kind.FORGET) {
            return error(src, "'$name' is not a witherstone")
        }

        if (stone.state == MementoStones.WitherstoneState.MATURED && value > 0) {
            RenewalBatchForgetting.discardGroup(name)
        }

        MementoStones.addOrReplace(
            stone.copy(
                days = value,
                state =
                    if (value == 0)
                        MementoStones.WitherstoneState.MATURED
                    else
                        MementoStones.WitherstoneState.MATURING
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
        val stone = MementoStones.get(name)
            ?: return error(src, "No such stone '$name'")

        MementoStones.addOrReplace(stone.copy(radius = radius))

        src.sendFeedback(
            { Text.literal("Stone '$name' radius set to $radius") },
            false
        )
        return 1
    }

    private fun inspect(src: ServerCommandSource, name: String): Int {
        val stone = MementoStones.get(name)
            ?: run {
                src.sendError(Text.literal("No stone found with name '$name'"))
                return 0
            }

        src.sendFeedback({ Text.literal("Inspect '$name' (${stone.kind})") }, false)
        src.sendFeedback(
            { Text.literal("Stone: dim=${stone.dimension.value}, pos=${stone.pos}, radius=${stone.radius}") },
            false
        )

        val batch = RenewalBatchForgetting.getBatchByStoneName(name)
        if (batch != null) {
            val reports = ChunkInspection.inspectBatch(src.server, batch)
            src.sendFeedback(
                { Text.literal("Renewal batch: state=${batch.state}, chunks=${reports.size}") },
                false
            )
        }

        return 1
    }

    private fun list(
        kind: MementoStones.Kind?,
        src: ServerCommandSource
    ): Int {
        val stones = MementoStones.list()
            .filter { kind == null || it.kind == kind }

        if (stones.isEmpty()) {
            src.sendFeedback({ Text.literal("No stones found.") }, false)
            return 1
        }

        stones.forEach {
            src.sendFeedback(
                { Text.literal("${it.name}: ${it.kind} at ${it.pos} radius=${it.radius}") },
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
