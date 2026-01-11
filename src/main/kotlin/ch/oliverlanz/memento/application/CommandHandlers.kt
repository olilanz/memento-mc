package ch.oliverlanz.memento.application

import ch.oliverlanz.memento.domain.events.StoneLifecycleTrigger
import ch.oliverlanz.memento.domain.stones.Lorestone
import ch.oliverlanz.memento.domain.stones.Stone
import ch.oliverlanz.memento.domain.stones.StoneTopology
import ch.oliverlanz.memento.domain.stones.Witherstone
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import org.slf4j.LoggerFactory
import ch.oliverlanz.memento.application.visualization.StoneVisualizationEngine
import ch.oliverlanz.memento.application.visualization.VisualizationType

/**
 * Application-layer command handlers.
 *
 * Commands.kt defines the authoritative command grammar.
 * This file contains the execution logic and delegates to the domain layer
 * (StoneTopology + RenewalTracker).
 */
object CommandHandlers {

    private val log = LoggerFactory.getLogger("memento")

    @Volatile
    private var visualizationEngine: StoneVisualizationEngine? = null

    fun attachVisualizationEngine(engine: StoneVisualizationEngine) {
        visualizationEngine = engine
    }

    fun detachVisualizationEngine() {
        visualizationEngine = null
    }

    enum class StoneKind { WITHERSTONE, LORESTONE }

    fun list(kind: StoneKind?, source: ServerCommandSource): Int {
        val stones = StoneTopology.list()
            .filter { stone ->
                when (kind) {
                    null -> true
                    StoneKind.WITHERSTONE -> stone is Witherstone
                    StoneKind.LORESTONE -> stone is Lorestone
                }
            }
            .sortedBy { it.name }

        if (stones.isEmpty()) {
            source.sendFeedback({ Text.literal("No stones registered.").formatted(Formatting.GRAY) }, false)
            return 1
        }

        source.sendFeedback({ Text.literal("Registered stones (${stones.size}):").formatted(Formatting.GOLD) }, false)
        stones.forEach { stone ->
            source.sendFeedback({ Text.literal(" - ${formatStoneLine(stone)}") }, false)
        }
        return 1
    }

    fun inspect(source: ServerCommandSource, name: String): Int {
        val stone = StoneTopology.get(name)
        if (stone == null) {
            source.sendError(Text.literal("No stone named '$name'."))
            return 0
        }

        val lines = formatStoneInspect(stone)
        source.sendFeedback({ Text.literal(lines.first()).formatted(Formatting.GOLD) }, false)
        lines.drop(1).forEach { line ->
            source.sendFeedback({ Text.literal(line).formatted(Formatting.GRAY) }, false)
        }
        return 1
    }

    fun visualize(source: ServerCommandSource, name: String): Int {
        log.info("[CMD] visualize name='{}' by={}", name, source.name)

        val stone = StoneTopology.get(name)
        if (stone == null) {
            source.sendError(Text.literal("No stone named '$name'."))
            return 0
        }

        val engine = visualizationEngine
        if (engine == null) {
            source.sendError(Text.literal("Visualization engine is not ready yet."))
            return 0
        }

        engine.visualizeStone(stone, VisualizationType.INSPECTION)
        source.sendFeedback({ Text.literal("Visualizing '$name' for a short time.").formatted(Formatting.YELLOW) }, false)
        return 1
    }

    fun addWitherstone(source: ServerCommandSource, name: String, radius: Int, daysToMaturity: Int): Int {
        log.info("[CMD] addWitherstone name='{}' radius={} daysToMaturity={} by={}", name, radius, daysToMaturity, source.name)
        val player = source.playerOrThrow
        val dim = source.world.registryKey

        val pos = resolveTargetBlockOrFail(source) ?: return 0

                        try {
        StoneTopology.addWitherstone(
                    name = name,
                    dimension = dim,
                    position = pos,
                    radius = radius,
                    daysToMaturity = daysToMaturity,
                    trigger = StoneLifecycleTrigger.OP_COMMAND
                )
                } catch (e: IllegalArgumentException) {
                    source.sendError(Text.literal(e.message ?: "Invalid stone definition."))
                    return 0
                }

        source.sendFeedback(
            { Text.literal("Witherstone '$name' registered at ${pos.x},${pos.y},${pos.z} (r=$radius, days=$daysToMaturity).").formatted(Formatting.GREEN) },
            false
        )
        return 1
    }

    fun addLorestone(source: ServerCommandSource, name: String, radius: Int): Int {
        log.info("[CMD] addLorestone name='{}' radius={} by={}", name, radius, source.name)
        val player = source.playerOrThrow
        val dim = source.world.registryKey

        val pos = resolveTargetBlockOrFail(source) ?: return 0

                        try {
        StoneTopology.addLorestone(
                    name = name,
                    dimension = dim,
                    position = pos,
                    radius = radius
                )
                } catch (e: IllegalArgumentException) {
                    source.sendError(Text.literal(e.message ?: "Invalid stone definition."))
                    return 0
                }

        source.sendFeedback(
            { Text.literal("Lorestone '$name' registered at ${pos.x},${pos.y},${pos.z} (r=$radius).").formatted(Formatting.GREEN) },
            false
        )
        return 1
    }

    fun remove(source: ServerCommandSource, name: String): Int {
        log.info("[CMD] removeStone name='{}' by={}", name, source.name)
        val existing = StoneTopology.get(name)
        if (existing == null) {
            source.sendError(Text.literal("No stone named '$name'."))
            return 0
        }

        StoneTopology.remove(name)

        source.sendFeedback({ Text.literal("Removed ${existing.javaClass.simpleName.lowercase()} '$name'.").formatted(Formatting.YELLOW) }, false)
        return 1
    }

        fun alterRadius(source: ServerCommandSource, name: String, value: Int): Int {
        log.info("[CMD] alterRadius name='{}' radius={} by={}", name, value, source.name)

        return try {
            val ok = StoneTopology.alterRadius(
                name = name,
                radius = value,
                trigger = StoneLifecycleTrigger.OP_COMMAND,
            )

            if (!ok) {
                source.sendError(Text.literal("No stone named '$name'."))
                0
            } else {
                source.sendFeedback(
                    { Text.literal("Updated radius for '$name' to $value.").formatted(Formatting.GREEN) },
                    false
                )
                1
            }
        } catch (e: Exception) {
            log.error("[CMD] alterRadius failed name='{}' radius={}", name, value, e)
            source.sendError(Text.literal("Memento: could not alter radius (see server log)."))
            0
        }
    }


        fun alterDaysToMaturity(source: ServerCommandSource, name: String, value: Int): Int {
        log.info("[CMD] alterDaysToMaturity name='{}' daysToMaturity={} by={}", name, value, source.name)

        return try {
            when (
                StoneTopology.alterDaysToMaturity(
                    name = name,
                    daysToMaturity = value,
                    trigger = StoneLifecycleTrigger.OP_COMMAND,
                )
            ) {
                StoneTopology.AlterDaysResult.OK -> {
                    source.sendFeedback(
                        { Text.literal("Updated daysToMaturity for '$name' to $value.").formatted(Formatting.GREEN) },
                        false
                    )
                    1
                }

                StoneTopology.AlterDaysResult.NOT_FOUND -> {
                    source.sendError(Text.literal("No stone named '$name'."))
                    0
                }

                StoneTopology.AlterDaysResult.NOT_SUPPORTED -> {
                    source.sendError(Text.literal("Stone '$name' does not support daysToMaturity (Lorestone)."))
                    0
                }

                StoneTopology.AlterDaysResult.ALREADY_CONSUMED -> {
                    source.sendError(Text.literal("Stone '$name' is already consumed."))
                    0
                }
            }
        } catch (e: Exception) {
            log.error("[CMD] alterDaysToMaturity failed name='{}' daysToMaturity={}", name, value, e)
            source.sendError(Text.literal("Memento: could not alter daysToMaturity (see server log)."))
            0
        }
    }


    private fun resolveTargetBlockOrFail(source: ServerCommandSource): BlockPos? {
        val player = source.playerOrThrow
        val hit = player.raycast(
            15.0, // max distance
            0.0f,  // tick delta
            false
        )

        if (hit.type != HitResult.Type.BLOCK) {
            source.sendError(Text.literal("No block in reach. Look at a block to place the stone."))
            return null
        }

        val blockHit = hit as net.minecraft.util.hit.BlockHitResult
        return blockHit.blockPos.up()
    }

    private fun formatStoneLine(stone: Stone): String =
        when (stone) {
            is Witherstone ->
                "witherstone '${stone.name}' dim=${stone.dimension} pos=(${stone.position.x},${stone.position.y},${stone.position.z}) r=${stone.radius} days=${stone.daysToMaturity} state=${stone.state}"
            is Lorestone ->
                "lorestone '${stone.name}' dim=${stone.dimension} pos=(${stone.position.x},${stone.position.y},${stone.position.z}) r=${stone.radius}"
        }

    private fun formatStoneInspect(stone: Stone): List<String> =
        when (stone) {
            is Witherstone -> listOf(
                "Witherstone '${stone.name}'",
                "dimension: ${stone.dimension}",
                "position: (${stone.position.x},${stone.position.y},${stone.position.z})",
                "radius: ${stone.radius}",
                "daysToMaturity: ${stone.daysToMaturity}",
                "state: ${stone.state}",
            )
            is Lorestone -> listOf(
                "Lorestone '${stone.name}'",
                "dimension: ${stone.dimension}",
                "position: (${stone.position.x},${stone.position.y},${stone.position.z})",
                "radius: ${stone.radius}",
            )
        }
}
