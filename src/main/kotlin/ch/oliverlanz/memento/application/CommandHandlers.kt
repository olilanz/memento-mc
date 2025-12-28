package ch.oliverlanz.memento.application

import ch.oliverlanz.memento.domain.events.WitherstoneTransitionTrigger
import ch.oliverlanz.memento.domain.stones.StoneRegister
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * Application layer: command execution logic.
 *
 * Notes:
 * - Legacy MementoStones remains authoritative.
 * - Shadow StoneRegister is mirrored passively for observability and future handover.
 */
object CommandHandlers {

    fun list(kind: MementoStones.Kind?, src: ServerCommandSource): Int {
        val stones = MementoStones.list()
            .filter { kind == null || it.kind == kind }

        if (stones.isEmpty()) {
            src.sendFeedback({ Text.literal("No stones found.") }, false)
            return 1
        }

        src.sendFeedback(
            { Text.literal("Stones (${stones.size}):").formatted(Formatting.YELLOW) },
            false
        )
        stones.forEach { s ->
            val label = when (s.kind) {
                MementoStones.Kind.FORGET -> "witherstone"
                MementoStones.Kind.REMEMBER -> "lorestone"
            }
            src.sendFeedback(
                { Text.literal("- ${s.name} ($label) r=${s.radius}").formatted(Formatting.GRAY) },
                false
            )
        }
        return 1
    }

    fun inspect(src: ServerCommandSource, name: String): Int {
        val s = MementoStones.get(name) ?: return error(src, "No such stone '$name'.")
        val label = when (s.kind) {
            MementoStones.Kind.FORGET -> "WITHERSTONE"
            MementoStones.Kind.REMEMBER -> "LORESTONE"
        }

        src.sendFeedback({ Text.literal("Stone '${s.name}'").formatted(Formatting.YELLOW) }, false)
        src.sendFeedback({ Text.literal("Type: $label").formatted(Formatting.GRAY) }, false)
        src.sendFeedback({ Text.literal("Dimension: ${s.dimension.value}").formatted(Formatting.GRAY) }, false)
        src.sendFeedback({ Text.literal("Position: ${s.pos.x}, ${s.pos.y}, ${s.pos.z}").formatted(Formatting.GRAY) }, false)
        src.sendFeedback({ Text.literal("Radius: ${s.radius}").formatted(Formatting.GRAY) }, false)

        if (s.kind == MementoStones.Kind.FORGET) {
            src.sendFeedback({ Text.literal("DaysToMaturity: ${s.days}").formatted(Formatting.GRAY) }, false)
            src.sendFeedback({ Text.literal("State: ${s.state}").formatted(Formatting.GRAY) }, false)
        }

        src.sendFeedback({ Text.literal("CreatedGameTime: ${s.createdGameTime}").formatted(Formatting.GRAY) }, false)
        return 1
    }

    fun addWitherstone(src: ServerCommandSource, name: String, radius: Int, daysToMaturity: Int): Int {
        val world = src.world
        val pos = src.playerOrThrow.blockPos
        val now = world.time

        MementoStones.addOrReplace(
            MementoStones.Stone(
                name = name,
                kind = MementoStones.Kind.FORGET,
                dimension = world.registryKey,
                pos = pos,
                radius = radius,
                days = daysToMaturity,
                state = if (daysToMaturity == 0) MementoStones.WitherstoneState.MATURED else MementoStones.WitherstoneState.MATURING,
                createdGameTime = now
            )
        )

        StoneRegister.addOrReplaceWitherstone(
            name = name,
            dimension = world.registryKey,
            position = pos,
            radius = radius,
            daysToMaturity = daysToMaturity,
            trigger = WitherstoneTransitionTrigger.OPERATOR
        )

        src.sendFeedback({ Text.literal("Witherstone '$name' added.").formatted(Formatting.GREEN) }, false)
        return 1
    }

    fun addLorestone(src: ServerCommandSource, name: String, radius: Int): Int {
        val world = src.world
        val pos = src.playerOrThrow.blockPos
        val now = world.time

        MementoStones.addOrReplace(
            MementoStones.Stone(
                name = name,
                kind = MementoStones.Kind.REMEMBER,
                dimension = world.registryKey,
                pos = pos,
                radius = radius,
                days = null,
                state = null,
                createdGameTime = now
            )
        )

        StoneRegister.addOrReplaceLorestone(
            name = name,
            dimension = world.registryKey,
            position = pos,
            radius = radius
        )

        src.sendFeedback({ Text.literal("Lorestone '$name' added.").formatted(Formatting.GREEN) }, false)
        return 1
    }

    fun remove(src: ServerCommandSource, name: String): Int {
        if (!MementoStones.remove(name)) {
            return error(src, "No such stone '$name'.")
        }

        StoneRegister.remove(name)

        src.sendFeedback({ Text.literal("Stone '$name' removed.").formatted(Formatting.GREEN) }, false)
        return 1
    }

    fun setRadius(src: ServerCommandSource, name: String, value: Int): Int {
        val stone = MementoStones.get(name) ?: return error(src, "No such stone '$name'.")

        MementoStones.addOrReplace(stone.copy(radius = value))

        when (stone.kind) {
            MementoStones.Kind.FORGET -> StoneRegister.addOrReplaceWitherstone(
                name = stone.name,
                dimension = stone.dimension,
                position = stone.pos,
                radius = value,
                daysToMaturity = stone.days ?: 0,
                trigger = WitherstoneTransitionTrigger.OPERATOR
            )
            MementoStones.Kind.REMEMBER -> StoneRegister.addOrReplaceLorestone(
                name = stone.name,
                dimension = stone.dimension,
                position = stone.pos,
                radius = value
            )
        }

        src.sendFeedback({ Text.literal("Radius updated for '$name'.").formatted(Formatting.GREEN) }, false)
        return 1
    }

    fun setDaysToMaturity(src: ServerCommandSource, name: String, value: Int): Int {
        val stone = MementoStones.get(name) ?: return error(src, "No such witherstone '$name'.")

        if (stone.kind != MementoStones.Kind.FORGET) {
            return error(src, "'$name' is not a witherstone.")
        }

        MementoStones.addOrReplace(
            stone.copy(
                days = value,
                state = if (value == 0) MementoStones.WitherstoneState.MATURED else MementoStones.WitherstoneState.MATURING
            )
        )

        StoneRegister.addOrReplaceWitherstone(
            name = stone.name,
            dimension = stone.dimension,
            position = stone.pos,
            radius = stone.radius,
            daysToMaturity = value,
            trigger = WitherstoneTransitionTrigger.OPERATOR
        )

        src.sendFeedback({ Text.literal("daysToMaturity updated for '$name'.").formatted(Formatting.GREEN) }, false)
        return 1
    }

    private fun error(src: ServerCommandSource, msg: String): Int {
        src.sendError(Text.literal(msg))
        return 0
    }
}