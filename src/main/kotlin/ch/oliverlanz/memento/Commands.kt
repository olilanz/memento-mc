package ch.oliverlanz.memento

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

    private const val DEFAULT_RADIUS = 5
    private const val DEFAULT_DAYS = 5

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
                .requires { it.hasPermissionLevel(2) }
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
                        cmdAnchorRemember(src, name, pos, DEFAULT_RADIUS)
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
                                cmdAnchorRemember(src, name, pos, DEFAULT_RADIUS)
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
                        cmdAnchorForget(src, name, pos, DEFAULT_RADIUS, DEFAULT_DAYS)
                    }
                    .then(
                        argument("radius", IntegerArgumentType.integer(1, 256))
                            .executes { ctx ->
                                val src = ctx.source
                                val name = StringArgumentType.getString(ctx, "name")
                                val radius = IntegerArgumentType.getInteger(ctx, "radius")
                                val pos = defaultPos(src)
                                cmdAnchorForget(src, name, pos, radius, DEFAULT_DAYS)
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
                                        cmdAnchorForget(src, name, pos, radius, DEFAULT_DAYS)
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
                                cmdAnchorForget(src, name, pos, DEFAULT_RADIUS, days)
                            }
                            .then(
                                argument("pos", BlockPosArgumentType.blockPos())
                                    .executes { ctx ->
                                        val src = ctx.source
                                        val name = StringArgumentType.getString(ctx, "name")
                                        val days = IntegerArgumentType.getInteger(ctx, "days")
                                        val pos = BlockPosArgumentType.getBlockPos(ctx, "pos")
                                        cmdAnchorForget(src, name, pos, DEFAULT_RADIUS, days)
                                    }
                            )
                    )
                    .then(
                        argument("pos", BlockPosArgumentType.blockPos())
                            .executes { ctx ->
                                val src = ctx.source
                                val name = StringArgumentType.getString(ctx, "name")
                                val pos = BlockPosArgumentType.getBlockPos(ctx, "pos")
                                cmdAnchorForget(src, name, pos, DEFAULT_RADIUS, DEFAULT_DAYS)
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
        // Simple + stable: use player's current block position.
        // (If you want “block you’re looking at”, we can raycast later.)
        return src.playerOrThrow.blockPos
    }

    private fun currentWorldKey(src: ServerCommandSource) = src.world.registryKey

    private fun currentGameTime(src: ServerCommandSource): Long {
        // If this ever fails due to mapping differences, change to:
        // src.world.getTime()
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
            createdGameTime = now
        )

        MementoAnchors.addOrReplace(anchor)
        MementoPersistence.save(src.server)

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
            createdGameTime = now
        )

        MementoAnchors.addOrReplace(anchor)
        MementoPersistence.save(src.server)

        val daysText = if (days == -1) "days=-1" else "days=$days"
        src.sendFeedback({ Text.literal("Anchored FORGET '$name' at ${fmtPos(pos)} r=$radius $daysText in ${fmtDim(worldKey)}") }, false)
        return 1
    }

    private fun cmdRelease(src: ServerCommandSource, name: String): Int {
        val removed = MementoAnchors.remove(name)
        if (removed) {
            MementoPersistence.save(src.server)
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
                    val d = a.days ?: DEFAULT_DAYS
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
        val worldKey = currentWorldKey(src)
        val now = currentGameTime(src)
        val count = MementoAnchors.list().size
        src.sendFeedback({ Text.literal("Memento debug: world=${fmtDim(worldKey)} time=$now anchors=$count") }, false)
        return 1
    }

    private fun fmtPos(pos: BlockPos) = "${pos.x} ${pos.y} ${pos.z}"
    private fun fmtDim(key: net.minecraft.registry.RegistryKey<World>) = key.value.toString()
}
