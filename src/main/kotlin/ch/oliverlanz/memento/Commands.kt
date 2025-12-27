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
                .then(
                    literal("list")
                        .executes { list(null, it.source) }
                        .then(literal("witherstone").executes { list(MementoStones.Kind.FORGET, it.source) })
                        .then(literal("lorestone").executes { list(MementoStones.Kind.REMEMBER, it.source) })
                )

                /* ======================
                 * INSPECT
                 * ====================== */
                .then(
                    literal("inspect")
                        .then(
                            argument("name", StringArgumentType.word())
                                .executes { ctx ->
                                    inspect(ctx.source, StringArgumentType.getString(ctx, "name"))
                                }
                        )
                )

                /* ======================
                 * ADD
                 * ====================== */
                .then(
                    literal("add")
                        .then(
                            literal("witherstone")
                                .then(
                                    argument("name", StringArgumentType.word())
                                        .executes { ctx ->
                                            addWitherstone(
                                                ctx.source,
                                                StringArgumentType.getString(ctx, "name"),
                                                MementoConstants.DEFAULT_CHUNKS_RADIUS,
                                                MementoConstants.DEFAULT_DAYS_TO_MATURITY
                                            )
                                        }
                                        .then(
                                            argument("daysToMaturity", IntegerArgumentType.integer(0, 10))
                                                .executes { ctx ->
                                                    addWitherstone(
                                                        ctx.source,
                                                        StringArgumentType.getString(ctx, "name"),
                                                        MementoConstants.DEFAULT_CHUNKS_RADIUS,
                                                        IntegerArgumentType.getInteger(ctx, "daysToMaturity")
                                                    )
                                                }
                                                .then(
                                                    argument("radius", IntegerArgumentType.integer(1, 10))
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
                        .then(
                            literal("lorestone")
                                .then(
                                    argument("name", StringArgumentType.word())
                                        .executes { ctx ->
                                            addLorestone(
                                                ctx.source,
                                                StringArgumentType.getString(ctx, "name"),
                                                MementoConstants.DEFAULT_CHUNKS_RADIUS
                                            )
                                        }
                                        .then(
                                            argument("radius", IntegerArgumentType.integer(1, 10))
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
                .then(
                    literal("remove")
                        .then(
                            argument("name", StringArgumentType.word())
                                .executes { ctx ->
                                    remove(ctx.source, StringArgumentType.getString(ctx, "name"))
                                }
                        )
                )
        )
    }

    /* ============================================================
     * Helpers
     * ============================================================ */

    private fun list(kind: MementoStones.Kind?, src: ServerCommandSource): Int {
        val stones = MementoStones.list()
            .filter { kind == null || it.kind == kind }

        if (stones.isEmpty()) {
            src.sendFeedback({ Text.literal("No stones found.") }, false)
            return 1
        }

        stones.forEach { stone ->
            val lifecycle =
                if (stone.kind == MementoStones.Kind.FORGET) {
                    "state=${stone.state} days=${stone.days}"
                } else {
                    "state=N/A"
                }

            src.sendFeedback(
                {
                    Text.literal(
                        "${stone.name}: ${stone.kind} $lifecycle radius=${stone.radius}"
                    )
                },
                false
            )
        }
        return 1
    }

    private fun inspect(src: ServerCommandSource, name: String): Int {
        val stone = MementoStones.get(name)
            ?: run {
                src.sendError(Text.literal("No stone found with name '$name'"))
                return 0
            }

        src.sendFeedback(
            { Text.literal("Stone '${stone.name}' (${stone.kind})") },
            false
        )
        src.sendFeedback(
            { Text.literal("Location: dim=${stone.dimension.value}, pos=${stone.pos}, radius=${stone.radius}") },
            false
        )

        if (stone.kind == MementoStones.Kind.FORGET) {
            src.sendFeedback({ Text.literal("Lifecycle:") }, false)
            src.sendFeedback({ Text.literal("  state: ${stone.state}") }, false)
            src.sendFeedback({ Text.literal("  daysToMaturity: ${stone.days}") }, false)
            src.sendFeedback({ Text.literal("  createdGameTime: ${stone.createdGameTime}") }, false)
        } else {
            src.sendFeedback({ Text.literal("Lifecycle: N/A") }, false)
        }

        val batch = RenewalBatchForgetting.getBatchByStoneName(name)
        if (batch != null) {
            val reports = ChunkInspection.inspectBatch(src.server, batch)
            src.sendFeedback(
                {
                    Text.literal(
                        "RenewalBatch: state=${batch.state}, chunks=${reports.size}"
                    )
                },
                false
            )
        }

        return 1
    }

    private fun addWitherstone(
        src: ServerCommandSource,
        name: String,
        radius: Int,
        daysToMaturity: Int
    ): Int {
        val pos = BlockPos.ofFloored(src.position)
        val world = src.world

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

        StoneRegister.add(
            Witherstone(
                name = name,
                position = pos,
                daysToMaturity = daysToMaturity
            ).also { it.radius = radius }
        )

        src.sendFeedback(
            {
                Text.literal(
                    "Witherstone '$name' placed (daysToMaturity=$daysToMaturity, radius=$radius)"
                )
            },
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
        RenewalBatchForgetting.discardGroup(name)
        StoneRegister.remove(name)

        src.sendFeedback(
            { Text.literal(if (removed) "Removed '$name'" else "No such stone '$name'") },
            false
        )
        return 1
    }
}
