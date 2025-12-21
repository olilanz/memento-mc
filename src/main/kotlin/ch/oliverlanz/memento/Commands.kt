package ch.oliverlanz.memento

import ch.oliverlanz.memento.chunkutils.ChunkLoading
import ch.oliverlanz.memento.chunkutils.ChunkInspection
import ch.oliverlanz.memento.chunkutils.ChunkGroupForgetting
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.command.argument.BlockPosArgumentType
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

/**
 * Locked grammar (position LAST):
 *
 * /memento anchor remember <name> [radius] [x y z]
 * /memento anchor forget   <name> [radius] [days] [x y z]
 * /memento release <name>
 * /memento list
 * /memento info
 *
 * Aliases:
 * /memento anchor lorestone ... == remember ...
 * /memento anchor witherstone ... == forget ...
 */
object Commands {

    fun register() {
        CommandRegistrationCallback.EVENT.register(
            CommandRegistrationCallback { dispatcher, _, _ ->
                register(dispatcher)
            }
        )
    }

    private fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        dispatcher.register(
            literal("memento")
                // Commands are operator-only. Using a constant makes it easy to tune later.
                .requires { it.hasPermissionLevel(MementoConstants.REQUIRED_OP_LEVEL) }
                .then(anchorTree())
                .then(releaseTree())
                .then(literal("list").executes { ctx -> cmdList(ctx.source) })
                .then(literal("info").executes { ctx -> cmdInfo(ctx.source) })
        )
    }

    private fun anchorTree() =
        literal("anchor")
            .then(rememberTree("remember"))
            .then(rememberTree("lorestone"))   // alias
            .then(forgetTree("forget"))
            .then(forgetTree("witherstone"))  // alias

    private fun rememberTree(word: String) =
        literal(word)
            .then(
                argument("name", StringArgumentType.word())
                    .executes { ctx ->
                        val src = ctx.source
                        val name = StringArgumentType.getString(ctx, "name")
                        val pos = defaultPos(src)
                        cmdAnchorRemember(src, name, pos, MementoConstants.DEFAULT_RADIUS_CHUNKS)
                    }
                    .then(
                        argument("radius", IntegerArgumentType.integer(1, 256))
                            .executes { ctx ->
                                val src = ctx.source
                                val name = StringArgumentType.getString(ctx, "name")
                                val radius = IntegerArgumentType.getInteger(ctx, "radius")
                                val pos = defaultPos(src)
                                cmdAnchorRemember(src, name, pos, radius)
                            }
                            .then(
                                argument("pos", BlockPosArgumentType.blockPos())
                                    .executes { ctx ->
                                        val src = ctx.source
                                        val name = StringArgumentType.getString(ctx, "name")
                                        val radius = IntegerArgumentType.getInteger(ctx, "radius")
                                        val pos = BlockPosArgumentType.getBlockPos(ctx, "pos")
                                        cmdAnchorRemember(src, name, pos, radius)
                                    }
                            )
                    )
                    .then(
                        argument("pos", BlockPosArgumentType.blockPos())
                            .executes { ctx ->
                                val src = ctx.source
                                val name = StringArgumentType.getString(ctx, "name")
                                val pos = BlockPosArgumentType.getBlockPos(ctx, "pos")
                                cmdAnchorRemember(src, name, pos, MementoConstants.DEFAULT_RADIUS_CHUNKS)
                            }
                    )
            )

    private fun forgetTree(word: String) =
        literal(word)
            .then(
                argument("name", StringArgumentType.word())
                    .executes { ctx ->
                        val src = ctx.source
                        val name = StringArgumentType.getString(ctx, "name")
                        val pos = defaultPos(src)
                        cmdAnchorForget(
                            src,
                            name,
                            pos,
                            MementoConstants.DEFAULT_RADIUS_CHUNKS,
                            MementoConstants.DEFAULT_FORGET_DAYS
                        )
                    }
                    .then(
                        argument("radius", IntegerArgumentType.integer(1, 256))
                            .executes { ctx ->
                                val src = ctx.source
                                val name = StringArgumentType.getString(ctx, "name")
                                val radius = IntegerArgumentType.getInteger(ctx, "radius")
                                val pos = defaultPos(src)
                                cmdAnchorForget(src, name, pos, radius, MementoConstants.DEFAULT_FORGET_DAYS)
                            }
                            .then(
                                argument("days", IntegerArgumentType.integer(-1, 3650))
                                    .executes { ctx ->
                                        val src = ctx.source
                                        val name = StringArgumentType.getString(ctx, "name")
                                        val radius = IntegerArgumentType.getInteger(ctx, "radius")
                                        val days = IntegerArgumentType.getInteger(ctx, "days")
                                        val pos = defaultPos(src)
                                        cmdAnchorForget(src, name, pos, radius, days)
                                    }
                                    .then(
                                        argument("pos", BlockPosArgumentType.blockPos())
                                            .executes { ctx ->
                                                val src = ctx.source
                                                val name = StringArgumentType.getString(ctx, "name")
                                                val radius = IntegerArgumentType.getInteger(ctx, "radius")
                                                val days = IntegerArgumentType.getInteger(ctx, "days")
                                                val pos = BlockPosArgumentType.getBlockPos(ctx, "pos")
                                                cmdAnchorForget(src, name, pos, radius, days)
                                            }
                                    )
                            )
                            .then(
                                argument("pos", BlockPosArgumentType.blockPos())
                                    .executes { ctx ->
                                        val src = ctx.source
                                        val name = StringArgumentType.getString(ctx, "name")
                                        val radius = IntegerArgumentType.getInteger(ctx, "radius")
                                        val pos = BlockPosArgumentType.getBlockPos(ctx, "pos")
                                        cmdAnchorForget(src, name, pos, radius, MementoConstants.DEFAULT_FORGET_DAYS)
                                    }
                            )
                    )
                    .then(
                        argument("days", IntegerArgumentType.integer(-1, 3650))
                            .executes { ctx ->
                                val src = ctx.source
                                val name = StringArgumentType.getString(ctx, "name")
                                val days = IntegerArgumentType.getInteger(ctx, "days")
                                val pos = defaultPos(src)
                                cmdAnchorForget(src, name, pos, MementoConstants.DEFAULT_RADIUS_CHUNKS, days)
                            }
                            .then(
                                argument("pos", BlockPosArgumentType.blockPos())
                                    .executes { ctx ->
                                        val src = ctx.source
                                        val name = StringArgumentType.getString(ctx, "name")
                                        val days = IntegerArgumentType.getInteger(ctx, "days")
                                        val pos = BlockPosArgumentType.getBlockPos(ctx, "pos")
                                        cmdAnchorForget(src, name, pos, MementoConstants.DEFAULT_RADIUS_CHUNKS, days)
                                    }
                            )
                    )
                    .then(
                        argument("pos", BlockPosArgumentType.blockPos())
                            .executes { ctx ->
                                val src = ctx.source
                                val name = StringArgumentType.getString(ctx, "name")
                                val pos = BlockPosArgumentType.getBlockPos(ctx, "pos")
                        cmdAnchorForget(
                            src,
                            name,
                            pos,
                            MementoConstants.DEFAULT_RADIUS_CHUNKS,
                            MementoConstants.DEFAULT_FORGET_DAYS
                        )
                            }
                    )
            )

    private fun releaseTree() =
        literal("release")
            .then(
                argument("name", StringArgumentType.word())
                    .executes { ctx ->
                        val src = ctx.source
                        val name = StringArgumentType.getString(ctx, "name")
                        cmdRelease(src, name)
                    }
            )

    private fun defaultPos(src: ServerCommandSource): BlockPos {
        return src.playerOrThrow.blockPos
    }

    private fun currentWorldKey(src: ServerCommandSource) = src.world.registryKey

    private fun currentGameTime(src: ServerCommandSource): Long {
        return src.world.time
    }

    private fun cmdAnchorRemember(src: ServerCommandSource, name: String, pos: BlockPos, radius: Int): Int {
        val worldKey = currentWorldKey(src)
        val now = currentGameTime(src)

        val anchor = MementoAnchors.Anchor(
            name = name,
            kind = MementoAnchors.Kind.REMEMBER,
            dimension = worldKey,
            pos = pos,
            radius = radius,
            days = null,
            state = null,
            createdGameTime = now
        )

        MementoAnchors.addOrReplace(anchor)
        MementoPersistence.save(src.server)

        // Lifecycle visibility for in-game testing.
        MementoDebug.info(
            src.server,
            "Anchor set (lorestone/remember): name='$name' dim=${fmtDim(worldKey)} pos=${fmtPos(pos)} r=$radius"
        )

        src.sendFeedback({ Text.literal("Anchored REMEMBER '$name' at ${fmtPos(pos)} r=$radius in ${fmtDim(worldKey)}") }, false)
        return 1
    }

    private fun cmdAnchorForget(src: ServerCommandSource, name: String, pos: BlockPos, radius: Int, days: Int): Int {
        val worldKey = currentWorldKey(src)
        val now = currentGameTime(src)

        val anchor = MementoAnchors.Anchor(
            name = name,
            kind = MementoAnchors.Kind.FORGET,
            dimension = worldKey,
            pos = pos,
            radius = radius,
            days = days,
            state = if (days != -1 && days <= 0) MementoAnchors.WitherstoneState.MATURED else MementoAnchors.WitherstoneState.MATURING,
            createdGameTime = now
        )

        MementoAnchors.addOrReplace(anchor)
        MementoPersistence.save(src.server)

        // Lifecycle visibility for in-game testing.
        MementoDebug.info(
            src.server,
            "Anchor set (witherstone/forget): name='$name' dim=${fmtDim(worldKey)} pos=${fmtPos(pos)} r=$radius days=$days"
        )

        val daysText = if (days == -1) "days=-1" else "days=$days"
        src.sendFeedback({ Text.literal("Anchored FORGET '$name' at ${fmtPos(pos)} r=$radius $daysText in ${fmtDim(worldKey)}") }, false)
        return 1
    }

    private fun cmdRelease(src: ServerCommandSource, name: String): Int {
        val removed = MementoAnchors.remove(name)
        if (removed) {
            MementoPersistence.save(src.server)
            MementoDebug.info(src.server, "Anchor released: name='$name'")
            src.sendFeedback({ Text.literal("Released anchor '$name'") }, false)
            return 1
        }
        src.sendError(Text.literal("No anchor named '$name'"))
        return 0
    }

    private fun cmdList(src: ServerCommandSource): Int {
        val all = MementoAnchors.list().sortedBy { it.name.lowercase() }
        if (all.isEmpty()) {
            src.sendFeedback({ Text.literal("No anchors defined.") }, false)
            return 1
        }

        src.sendFeedback({ Text.literal("Anchors (${all.size}):") }, false)
        for (a in all) {
            val days = when (a.kind) {
                MementoAnchors.Kind.REMEMBER -> ""
                MementoAnchors.Kind.FORGET -> {
                    val d = a.days ?: MementoConstants.DEFAULT_FORGET_DAYS
                    if (d == -1) " days=-1" else " days=$d"
                }
            }
            src.sendFeedback(
                { Text.literal("- ${a.name}: ${a.kind.name.lowercase()} ${fmtDim(a.dimension)} ${fmtPos(a.pos)} r=${a.radius}$days") },
                false
            )
        }
        return 1
    }

    private fun cmdInfo(src: ServerCommandSource): Int {
        val now = currentGameTime(src)
        val anchorCount = MementoAnchors.list().size

        val groups = ChunkGroupForgetting.snapshotMarkedGroups()
        val reports = ChunkInspection.inspectAll(src.server)
        val forgettableCount = reports.size

        src.sendFeedback({ Text.literal(
            "Memento info: time=$now anchors=$anchorCount markedGroups=${groups.size} groupChunks=$forgettableCount"
        ) }, false)

        // Group-level summary. This keeps the report readable and explains why a renewal is waiting.
        for (g in groups) {
            val world = src.server.getWorld(g.dimension)
            val loadedCount = if (world == null) {
                -1
            } else {
                g.chunks.count { ChunkLoading.isChunkLoadedBestEffort(world, it) }
            }

            val loadedText = if (loadedCount < 0) "dimension not loaded" else "loadedChunks=$loadedCount"
            src.sendFeedback({ Text.literal(
                "Eligible group: anchor='${g.anchorName}' dim=${fmtDim(g.dimension)} r=${g.radiusChunks} chunks=${g.chunks.size} $loadedText"
            ) }, false)
        }

        if (reports.isEmpty()) {
            src.sendFeedback({ Text.literal("No land is currently marked for forgetting.") }, false)
            return 1
        }

        val byDim = reports.groupBy { it.dimension }
        for ((dim, dimReports) in byDim) {
            src.sendFeedback({ Text.literal("Dimension: ${fmtDim(dim)} (${dimReports.size})") }, false)
            for (r in dimReports) {
                val base = "- chunk=(${r.pos.x}, ${r.pos.z}) ${if (r.isLoaded) "LOADED" else "UNLOADED"} | ${r.summary}"
                src.sendFeedback({ Text.literal(base) }, false)

                if (r.blockers.isNotEmpty()) {
                    for (b in r.blockers) {
                        src.sendFeedback({ Text.literal("    • ${b.kind.name.lowercase()}: ${b.details}") }, false)
                    }
                }
            }
        }

        src.sendFeedback(
            { Text.literal("Note: this report is best-effort. If a chunk remains loaded with no obvious blockers, it is likely waiting for server eviction.") },
            false
        )

        return 1
    }

    private fun fmtPos(pos: BlockPos) = "${pos.x} ${pos.y} ${pos.z}"
    private fun fmtDim(key: net.minecraft.registry.RegistryKey<World>) = key.value.toString()
}
