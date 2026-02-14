package ch.oliverlanz.memento.application

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import java.util.concurrent.CompletableFuture
import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.StoneLifecycleState
import ch.oliverlanz.memento.domain.events.StoneLifecycleTransition
import ch.oliverlanz.memento.domain.events.StoneLifecycleTrigger
import ch.oliverlanz.memento.domain.stones.Lorestone
import ch.oliverlanz.memento.domain.stones.LorestoneView
import ch.oliverlanz.memento.domain.stones.Stone
import ch.oliverlanz.memento.domain.stones.StoneAuthority
import ch.oliverlanz.memento.domain.stones.Witherstone
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import ch.oliverlanz.memento.application.visualization.EffectsHost
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import ch.oliverlanz.memento.infrastructure.worldscan.WorldScanner

/**
 * Application-layer command handlers.
 *
 * Commands.kt defines the authoritative command grammar.
 * This file contains the execution logic and delegates to the domain layer
 * (StoneAuthority + RenewalTracker).
 */
object CommandHandlers {

    private enum class SuggestedStoneKind {
        ANY,
        WITHERSTONE,
        LORESTONE,
    }

    @Volatile
    private var suggestionIndexAttached: Boolean = false

    @Volatile
    private var suggestedKindsByName: Map<String, SuggestedStoneKind> = emptyMap()


    @Volatile
    private var effectsHost: EffectsHost? = null

    @Volatile
    private var worldScanner: WorldScanner? = null

    fun attachVisualizationEngine(engine: EffectsHost) {
        effectsHost = engine
    }

    fun detachVisualizationEngine() {
        effectsHost = null
    }

    fun attachWorldScanner(scanner: WorldScanner) {
        worldScanner = scanner
    }

    fun detachWorldScanner() {
        worldScanner = null
    }

    fun attachStoneNameSuggestions() {
        if (suggestionIndexAttached) return
        StoneDomainEvents.subscribeToLifecycleTransitions(::onStoneLifecycleTransitionForSuggestions)
        suggestionIndexAttached = true
    }

    fun detachStoneNameSuggestions() {
        if (!suggestionIndexAttached) return
        StoneDomainEvents.unsubscribeFromLifecycleTransitions(::onStoneLifecycleTransitionForSuggestions)
        suggestedKindsByName = emptyMap()
        suggestionIndexAttached = false
    }

    enum class StoneKind { WITHERSTONE, LORESTONE }

    fun suggestAnyStoneName(
        context: CommandContext<ServerCommandSource>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> = suggestNames(SuggestedStoneKind.ANY, builder)

    fun suggestWitherstoneName(
        context: CommandContext<ServerCommandSource>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> = suggestNames(SuggestedStoneKind.WITHERSTONE, builder)

    fun suggestLorestoneName(
        context: CommandContext<ServerCommandSource>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> = suggestNames(SuggestedStoneKind.LORESTONE, builder)

    fun list(kind: StoneKind?, source: ServerCommandSource): Int {
        return try {
            val stones = StoneAuthority.list()
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
            1
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=list failed", e)
            source.sendError(Text.literal("Memento: could not list stones (see server log)."))
            0
        }
    }

    fun inspect(source: ServerCommandSource, name: String): Int {
        return try {
            val stone = StoneAuthority.get(name)
            if (stone == null) {
                warnStaleNameSelection(source, name, "inspect")
                source.sendError(Text.literal("No stone named '$name'."))
                return 0
            }

            val lines = formatStoneInspect(stone)
            source.sendFeedback({ Text.literal(lines.first()).formatted(Formatting.GOLD) }, false)
            lines.drop(1).forEach { line ->
                source.sendFeedback({ Text.literal(line).formatted(Formatting.GRAY) }, false)
            }
            1
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=inspect failed name='{}'", e, name)
            source.sendError(Text.literal("Memento: could not inspect stone (see server log)."))
            0
        }
    }

    fun visualize(source: ServerCommandSource, name: String): Int {
        MementoLog.info(MementoConcept.OPERATOR, "command=visualize name='{}' by={}", name, source.name)

        val stone = StoneAuthority.get(name)
        if (stone == null) {
            warnStaleNameSelection(source, name, "visualize")
            source.sendError(Text.literal("No stone named '$name'."))
            return 0
        }

        val engine = effectsHost
        if (engine == null) {
            source.sendError(Text.literal("Visualization engine is not ready yet."))
            return 0
        }

        return try {
            engine.visualizeStone(stone)
            source.sendFeedback({ Text.literal("Visualizing '$name' for a short time.").formatted(Formatting.YELLOW) }, false)
            1
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=visualize failed name='{}'", e, name)
            source.sendError(Text.literal("Memento: could not visualize stone (see server log)."))
            0
        }
    }

    fun visualizeAll(source: ServerCommandSource): Int {
        MementoLog.info(MementoConcept.OPERATOR, "command=visualize all by={}", source.name)

        val engine = effectsHost
        if (engine == null) {
            source.sendError(Text.literal("Visualization engine is not ready yet."))
            return 0
        }

        return try {
            val stones = StoneAuthority.list().sortedBy { it.name }
            if (stones.isEmpty()) {
                source.sendFeedback({ Text.literal("No stones registered.").formatted(Formatting.GRAY) }, false)
                return 1
            }

            stones.forEach { stone ->
                engine.visualizeStone(stone)
            }

            source.sendFeedback(
                { Text.literal("Visualizing all ${stones.size} stones for a short time.").formatted(Formatting.YELLOW) },
                false
            )
            1
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=visualize all failed", e)
            source.sendError(Text.literal("Memento: could not visualize stones (see server log)."))
            0
        }
    }

    fun scan(source: ServerCommandSource): Int {
        MementoLog.info(MementoConcept.OPERATOR, "command=scan by={}", source.name)

        val scanner = worldScanner
        if (scanner == null) {
            source.sendError(Text.literal("Scanner is not ready yet."))
            return 0
        }

        return try {
            scanner.startActiveScan(source)
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=scan failed", e)
            source.sendError(Text.literal("Memento: could not start scan (see server log)."))
            0
        }
    }

    fun addWitherstone(source: ServerCommandSource, name: String, radius: Int, daysToMaturity: Int): Int {
        MementoLog.info(MementoConcept.OPERATOR, "command=addWitherstone name='{}' radius={} daysToMaturity={} by={}", name, radius, daysToMaturity, source.name)
        val player = source.playerOrThrow
        val dim = source.world.registryKey

        val pos = resolveTargetBlockOrFail(source) ?: return 0

                        try {
        StoneAuthority.addWitherstone(
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
        MementoLog.info(MementoConcept.OPERATOR, "command=addLorestone name='{}' radius={} by={}", name, radius, source.name)
        val player = source.playerOrThrow
        val dim = source.world.registryKey

        val pos = resolveTargetBlockOrFail(source) ?: return 0

                        try {
        StoneAuthority.addLorestone(
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
        MementoLog.info(MementoConcept.OPERATOR, "command=removeStone name='{}' by={}", name, source.name)
        return try {
            val existing = StoneAuthority.get(name)
            if (existing == null) {
                warnStaleNameSelection(source, name, "remove")
                source.sendError(Text.literal("No stone named '$name'."))
                return 0
            }

            StoneAuthority.remove(name)

            source.sendFeedback({ Text.literal("Removed ${existing.javaClass.simpleName.lowercase()} '$name'.").formatted(Formatting.YELLOW) }, false)
            1
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=removeStone failed name='{}'", e, name)
            source.sendError(Text.literal("Memento: could not remove stone (see server log)."))
            0
        }
    }

        fun alterRadius(source: ServerCommandSource, name: String, value: Int): Int {
        MementoLog.info(MementoConcept.OPERATOR, "command=alterRadius name='{}' radius={} by={}", name, value, source.name)

        return try {
            val ok = StoneAuthority.alterRadius(
                name = name,
                radius = value,
                trigger = StoneLifecycleTrigger.OP_COMMAND,
            )

            if (!ok) {
                warnStaleNameSelection(source, name, "alter radius")
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
            MementoLog.error(MementoConcept.OPERATOR, "command=alterRadius failed name='{}' radius={}", e, name, value)
            source.sendError(Text.literal("Memento: could not alter radius (see server log)."))
            0
        }
    }


        fun alterDaysToMaturity(source: ServerCommandSource, name: String, value: Int): Int {
        MementoLog.info(MementoConcept.OPERATOR, "command=alterDaysToMaturity name='{}' daysToMaturity={} by={}", name, value, source.name)

        return try {
            when (
                StoneAuthority.alterDaysToMaturity(
                    name = name,
                    daysToMaturity = value,
                    trigger = StoneLifecycleTrigger.OP_COMMAND,
                )
            ) {
                StoneAuthority.AlterDaysResult.OK -> {
                    source.sendFeedback(
                        { Text.literal("Updated daysToMaturity for '$name' to $value.").formatted(Formatting.GREEN) },
                        false
                    )
                    1
                }

                StoneAuthority.AlterDaysResult.NOT_FOUND -> {
                    warnStaleNameSelection(source, name, "alter daysToMaturity")
                    source.sendError(Text.literal("No stone named '$name'."))
                    0
                }

                StoneAuthority.AlterDaysResult.NOT_SUPPORTED -> {
                    source.sendError(Text.literal("Stone '$name' does not support daysToMaturity (Lorestone)."))
                    0
                }

                StoneAuthority.AlterDaysResult.ALREADY_CONSUMED -> {
                    source.sendError(Text.literal("Stone '$name' is already consumed."))
                    0
                }
            }
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=alterDaysToMaturity failed name='{}' daysToMaturity={}", e, name, value)
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

    private fun onStoneLifecycleTransitionForSuggestions(event: StoneLifecycleTransition) {
        val next = linkedMapOf<String, SuggestedStoneKind>()
        next.putAll(suggestedKindsByName)

        when (event.to) {
            StoneLifecycleState.CONSUMED -> next.remove(event.stone.name)
            else -> {
                val kind = when (event.stone) {
                    is WitherstoneView -> SuggestedStoneKind.WITHERSTONE
                    is LorestoneView -> SuggestedStoneKind.LORESTONE
                    else -> SuggestedStoneKind.ANY
                }
                next[event.stone.name] = kind
            }
        }

        suggestedKindsByName = next.toMap()
    }

    private fun suggestNames(
        requiredKind: SuggestedStoneKind,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val names = when (requiredKind) {
            SuggestedStoneKind.ANY -> suggestedKindsByName.keys
            SuggestedStoneKind.WITHERSTONE -> suggestedKindsByName.filterValues { it == SuggestedStoneKind.WITHERSTONE }.keys
            SuggestedStoneKind.LORESTONE -> suggestedKindsByName.filterValues { it == SuggestedStoneKind.LORESTONE }.keys
        }

        names
            .asSequence()
            .sorted()
            .forEach { builder.suggest(it) }

        return builder.buildFuture()
    }

    private fun warnStaleNameSelection(source: ServerCommandSource, name: String, command: String) {
        source.sendFeedback(
            { Text.literal("Suggestion stale for '$name' during $command. Try tab completion again.").formatted(Formatting.GRAY) },
            false
        )
    }
}
