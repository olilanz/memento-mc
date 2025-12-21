package ch.oliverlanz.memento

import ch.oliverlanz.memento.chunkutils.ChunkGroupForgetting
import ch.oliverlanz.memento.chunkutils.ChunkInspection
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos

/**
 * Command parsing + dispatch only.
 *
 * Domain logic belongs to the anchor / forgetting utilities. This file should stay
 * as a thin translation layer from chat commands to explicit domain operations.
 */
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

                // ---------- listing ----------
                .then(
                    literal("list")
                        .executes { ctx -> list(ctx.source, null) }
                        .then(literal("witherstone").executes { ctx -> list(ctx.source, MementoAnchors.Kind.FORGET) })
                        .then(literal("lorestone").executes { ctx -> list(ctx.source, MementoAnchors.Kind.REMEMBER) })
                )

                // ---------- inspect ----------
                .then(
                    literal("inspect")
                        .then(
                            argument("name", StringArgumentType.word())
                                .executes { ctx ->
                                    inspect(ctx.source, StringArgumentType.getString(ctx, "name"))
                                }
                        )
                )

                // ---------- add ----------
                .then(
                    literal("add")
                        .then(
                            literal("witherstone")
                                .then(
                                    argument("name", StringArgumentType.word())
                                        .then(
                                            argument("radius", IntegerArgumentType.integer(1, 10))
                                                .then(
                                                    argument("daysToMaturity", IntegerArgumentType.integer(0, 10))
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

                // ---------- remove ----------
                .then(
                    literal("remove")
                        .then(
                            literal("witherstone")
                                .then(argument("name", StringArgumentType.word()).executes { ctx ->
                                    removeStone(ctx.source, StringArgumentType.getString(ctx, "name"), expected = MementoAnchors.Kind.FORGET)
                                })
                        )
                        .then(
                            literal("lorestone")
                                .then(argument("name", StringArgumentType.word()).executes { ctx ->
                                    removeStone(ctx.source, StringArgumentType.getString(ctx, "name"), expected = MementoAnchors.Kind.REMEMBER)
                                })
                        )
                )

                // ---------- set ----------
                .then(
                    literal("set")
                        .then(
                            literal("witherstone")
                                .then(
                                    argument("name", StringArgumentType.word())
                                        .then(
                                            literal("daysToMaturity")
                                                .then(
                                                    argument("value", IntegerArgumentType.integer(0, 10))
                                                        .executes { ctx ->
                                                            setWitherstoneDays(
                                                                ctx.source,
                                                                StringArgumentType.getString(ctx, "name"),
                                                                IntegerArgumentType.getInteger(ctx, "value")
                                                            )
                                                        }
                                                )
                                        )
                                        .then(
                                            literal("radius")
                                                .then(
                                                    argument("value", IntegerArgumentType.integer(1, 10))
                                                        .executes { ctx ->
                                                            setWitherstoneRadius(
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

    // ---------------- dispatch implementations ----------------

    private fun addWitherstone(src: ServerCommandSource, name: String, radius: Int, daysToMaturity: Int): Int {
        val world = src.world
        val pos = BlockPos.ofFloored(src.position)

        // Replace semantics: if a stone name is reused, we discard any existing derived group first.
        ChunkGroupForgetting.discardGroup(name)

        val anchor = MementoAnchors.Anchor(
            name = name,
            kind = MementoAnchors.Kind.FORGET,
            dimension = world.registryKey,
            pos = pos,
            radius = radius,
            days = daysToMaturity,
            state = if (daysToMaturity == 0) MementoAnchors.WitherstoneState.MATURED else MementoAnchors.WitherstoneState.MATURING,
            createdGameTime = world.time
        )
        MementoAnchors.addOrReplace(anchor)
        MementoPersistence.save(src.server)

        // If created matured, mark its group immediately (same semantics as nightly 1->0 maturation).
        ChunkGroupForgetting.markMaturedWitherstoneNow(src.server, anchor)

        src.sendFeedback(
            { Text.literal("Witherstone '$name' placed (radius=$radius, daysToMaturity=$daysToMaturity)") },
            false
        )
        return 1
    }

    private fun addLorestone(src: ServerCommandSource, name: String, radius: Int): Int {
        val world = src.world
        val pos = BlockPos.ofFloored(src.position)

        val anchor = MementoAnchors.Anchor(
            name = name,
            kind = MementoAnchors.Kind.REMEMBER,
            dimension = world.registryKey,
            pos = pos,
            radius = radius,
            days = null,
            state = null,
            createdGameTime = world.time
        )
        MementoAnchors.addOrReplace(anchor)
        MementoPersistence.save(src.server)

        src.sendFeedback(
            { Text.literal("Lorestone '$name' placed (radius=$radius)") },
            false
        )
        return 1
    }

    private fun removeStone(src: ServerCommandSource, name: String, expected: MementoAnchors.Kind): Int {
        val existing = MementoAnchors.get(name)
            ?: return error(src, "No such stone '$name'")

        if (existing.kind != expected) {
            return error(src, "'$name' is not a ${expected.name.lowercase()}")
        }

        MementoAnchors.remove(name)
        ChunkGroupForgetting.discardGroup(name)
        MementoPersistence.save(src.server)

        src.sendFeedback({ Text.literal("Removed '$name'") }, false)
        return 1
    }

    private fun setWitherstoneDays(src: ServerCommandSource, name: String, daysToMaturity: Int): Int {
        val existing = MementoAnchors.get(name)
            ?: return error(src, "No such witherstone '$name'")

        if (existing.kind != MementoAnchors.Kind.FORGET) {
            return error(src, "'$name' is not a witherstone")
        }

        val prevDays = existing.days ?: 0
        val wasMatured = (existing.state == MementoAnchors.WitherstoneState.MATURED || prevDays == 0)

        // Locked semantics: 0 -> N rewinds lifecycle; derived group must be discarded.
        if (wasMatured && daysToMaturity > 0) {
            ChunkGroupForgetting.discardGroup(name)
        }

        val updated = existing.copy(
            days = daysToMaturity,
            state = if (daysToMaturity == 0) MementoAnchors.WitherstoneState.MATURED else MementoAnchors.WitherstoneState.MATURING
        )
        MementoAnchors.addOrReplace(updated)
        MementoPersistence.save(src.server)

        // Setting N -> 0 matures immediately; mark group now.
        if (daysToMaturity == 0) {
            ChunkGroupForgetting.markMaturedWitherstoneNow(src.server, updated)
        }

        src.sendFeedback({ Text.literal("Witherstone '$name' daysToMaturity set to $daysToMaturity") }, false)
        return 1
    }

    private fun setWitherstoneRadius(src: ServerCommandSource, name: String, radius: Int): Int {
        val existing = MementoAnchors.get(name)
            ?: return error(src, "No such witherstone '$name'")

        if (existing.kind != MementoAnchors.Kind.FORGET) {
            return error(src, "'$name' is not a witherstone")
        }

        val updated = existing.copy(radius = radius)
        MementoAnchors.addOrReplace(updated)
        MementoPersistence.save(src.server)

        // If already matured, re-derive group immediately with the new radius.
        val days = updated.days ?: 0
        val matured = (updated.state == MementoAnchors.WitherstoneState.MATURED || days == 0)
        if (matured) {
            ChunkGroupForgetting.discardGroup(name)
            ChunkGroupForgetting.markMaturedWitherstoneNow(src.server, updated)
        }

        src.sendFeedback({ Text.literal("Witherstone '$name' radius set to $radius") }, false)
        return 1
    }

    private fun list(src: ServerCommandSource, kind: MementoAnchors.Kind?): Int {
        val stones = MementoAnchors.list()
            .filter { kind == null || it.kind == kind }

        if (stones.isEmpty()) {
            src.sendFeedback({ Text.literal("No stones found.") }, false)
            return 1
        }

        for (a in stones) {
            val days = a.days?.let { " daysToMaturity=$it" } ?: ""
            src.sendFeedback(
                { Text.literal("${a.name}: ${a.kind.name.lowercase()} at ${a.pos} r=${a.radius}$days") },
                false
            )
        }
        return 1
    }

    private fun inspect(src: ServerCommandSource, name: String): Int {
        val anchor = MementoAnchors.get(name)
            ?: return error(src, "No such stone '$name'")

        if (anchor.kind == MementoAnchors.Kind.REMEMBER) {
            src.sendFeedback(
                { Text.literal("Lorestone '$name': radius=${anchor.radius} (no forgetting behavior in this slice)") },
                false
            )
            return 1
        }

        val days = anchor.days ?: return error(src, "Witherstone '$name' has no days counter (unexpected)")
        val state = anchor.state ?: if (days == 0) MementoAnchors.WitherstoneState.MATURED else MementoAnchors.WitherstoneState.MATURING

        src.sendFeedback(
            { Text.literal("Witherstone '$name': state=$state radius=${anchor.radius} daysToMaturity=$days") },
            false
        )

        if (days > 0 || state == MementoAnchors.WitherstoneState.MATURING) {
            src.sendFeedback(
                { Text.literal("Not matured yet; will mature in $days day(s).") },
                false
            )
            return 1
        }

        // Ensure group exists (should after startup rebuild, but safe for command path).
        ChunkGroupForgetting.markMaturedWitherstoneNow(src.server, anchor)

        val group = ChunkGroupForgetting.getGroupByAnchorName(name)
            ?: run {
                src.sendFeedback({ Text.literal("No derived chunk group found for '$name'.") }, false)
                return 1
            }

        val reports = ChunkInspection.inspectGroup(src.server, group)
        val loaded = reports.filter { it.isLoaded }

        src.sendFeedback(
            { Text.literal("Chunk group: ${group.chunks.size} chunks; loaded=${loaded.size}") },
            false
        )

        if (loaded.isEmpty()) {
            src.sendFeedback(
                { Text.literal("All chunks are currently unloaded. Regeneration can begin on the next eligible trigger.") },
                false
            )
            return 1
        }

        // Explain precisely what we are waiting for: the loaded chunks to unload.
        for (r in loaded) {
            val header = "Chunk (${r.pos.x},${r.pos.z}) is loaded"
            src.sendFeedback({ Text.literal(header) }, false)
            for (b in r.blockers) {
                src.sendFeedback({ Text.literal(" - ${b.kind}: ${b.details}") }, false)
            }
        }

        return 1
    }

    private fun error(src: ServerCommandSource, msg: String): Int {
        src.sendError(Text.literal(msg))
        return 0
    }
}