package ch.oliverlanz.memento

import ch.oliverlanz.memento.infrastructure.MementoConstants
import ch.oliverlanz.memento.application.MementoStones
import ch.oliverlanz.memento.application.land.RenewalBatchForgetting
import ch.oliverlanz.memento.application.land.inspect.RenewalBatchView
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


private fun findBatchViewByName(name: String): RenewalBatchView? =
    RenewalBatchForgetting.snapshotBatches().firstOrNull { it.name == name }

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
                                    MementoConstants.DEFAULT_CHUNKS_RADIUS
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

        src.sendFeedback(
            { Text.literal("Lorestone '$name' placed (radius=$radius)") },
            false
        )
        return 1
    }

    private fun remove(src: ServerCommandSource, name: String): Int {
        val removed = MementoStones.remove(name)

        // Safe no-op if no group exists
        RenewalBatchForgetting.discardGroup(name)

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
        val stone = MementoStones.get(name)
            ?: return error(src, "No such witherstone '$name'")

        if (stone.kind != MementoStones.Kind.FORGET) {
            return error(src, "'$name' is not a witherstone")
        }

        // Re-arming: matured -> maturing must discard derived group
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

    private 
    fun inspect(
        src: ServerCommandSource,
        name: String
    ): Int {
        val stone = MementoStones.get(name)
            ?: run {
                src.sendError(Text.literal("No stone found with name '$name'"))
                return 0
            }

        val stoneLabel =
            when (stone.kind) {
                MementoStones.Kind.FORGET -> "Witherstone"
                MementoStones.Kind.REMEMBER -> "Lorestone"
            }

        src.sendFeedback(
            { Text.literal("Inspect '$name' ($stoneLabel)") },
            false
        )

        src.sendFeedback(
            { Text.literal("Stone: dim=${stone.dimension.value}, pos=${stone.pos.x},${stone.pos.y},${stone.pos.z}, radiusChunks=${stone.radius}") },
            false
        )

        if (stone.kind == MementoStones.Kind.FORGET) {
            val state = stone.state ?: MementoStones.WitherstoneState.MATURING
            val days = stone.days

            src.sendFeedback(
                { Text.literal("Witherstone state: $state" + (if (days != null) " (daysToMaturity=$days)" else "")) },
                false
            )

            if (state == MementoStones.WitherstoneState.MATURING && days != null && days > 0) {
                src.sendFeedback(
                    { Text.literal("Waiting for stone maturity trigger: NIGHTLY_TICK (days remaining: $days).") },
                    false
                )
            } else if (state == MementoStones.WitherstoneState.MATURED) {
                src.sendFeedback(
                    { Text.literal("Stone is matured. Derived chunk groups are created on stone maturity triggers: SERVER_START, NIGHTLY_TICK, COMMAND.") },
                    false
                )
            }
        }

        val batch = findBatchViewByName(name)
        if (batch == null) {
            src.sendFeedback(
                { Text.literal("Chunk group: none derived yet.") },
                false
            )
            return 1
        }

        val reports = ChunkInspection.inspectBatch(src.server, batch)
        val loaded = reports.filter { it.isLoaded }
        val loadedCount = loaded.size
        val total = reports.size
        val unloadedCount = (total - loadedCount).coerceAtLeast(0)

        src.sendFeedback(
            { Text.literal("Renewal batch: state=${batch.state}, chunks=$total, loaded=$loadedCount, unloaded=$unloadedCount") },
            false
        )

        if (batch.state == ch.oliverlanz.memento.domain.renewal.RenewalBatchState.BLOCKED) {
            src.sendFeedback(
                { Text.literal("Waiting for chunk renewal trigger: CHUNK_UNLOAD (loaded chunks prevent atomic renewal).") },
                false
            )
        }

        if (loaded.isNotEmpty()) {
            src.sendFeedback(
                { Text.literal("Blocking chunks (loaded):") },
                false
            )

            val maxLines = 12
            loaded.take(maxLines).forEach { r ->
                src.sendFeedback(
                    { Text.literal("- chunk (${r.pos.x},${r.pos.z}): ${r.summary}") },
                    false
                )
            }
            if (loaded.size > maxLines) {
                src.sendFeedback(
                    { Text.literal("… and ${loaded.size - maxLines} more loaded chunk(s).") },
                    false
                )
            }
        } else {
            src.sendFeedback(
                { Text.literal("No loaded chunks are blocking renewal.") },
                false
            )
        }

        return 1
    }

fun list(
        kind: MementoStones.Kind?,
        src: ServerCommandSource
    ): Int {
        val stones = MementoStones.list()
            .filter { kind == null || it.kind == kind }

        if (stones.isEmpty()) {
            src.sendFeedback({ Text.literal("No stones found.") }, false)
            return 1
        }

        stones.forEach { a ->
            val extra =
                if (a.kind == MementoStones.Kind.FORGET)
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