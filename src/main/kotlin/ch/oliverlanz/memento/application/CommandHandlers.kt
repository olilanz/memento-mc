package ch.oliverlanz.memento.application

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import java.util.concurrent.CompletableFuture
import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.StoneLifecycleState
import ch.oliverlanz.memento.domain.events.StoneLifecycleTransition
import ch.oliverlanz.memento.domain.events.StoneLifecycleTrigger
import ch.oliverlanz.memento.domain.renewal.RenewalBatchSnapshot
import ch.oliverlanz.memento.domain.renewal.RenewalBatchState
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.projection.RenewalAnalysisState
import ch.oliverlanz.memento.domain.renewal.projection.RenewalDecision
import ch.oliverlanz.memento.domain.renewal.projection.RenewalProjection
import ch.oliverlanz.memento.domain.stones.Lorestone
import ch.oliverlanz.memento.domain.stones.LorestoneView
import ch.oliverlanz.memento.domain.stones.Stone
import ch.oliverlanz.memento.domain.stones.StoneAuthority
import ch.oliverlanz.memento.domain.stones.StoneMapService
import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.domain.stones.Witherstone
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.ItemEntity
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Box
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import ch.oliverlanz.memento.application.visualization.EffectsHost
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import ch.oliverlanz.memento.infrastructure.worldscan.WorldScanner
import java.util.Locale

/**
 * Application-layer command handlers.
 *
 * Commands.kt defines the authoritative command grammar.
 * This file contains the execution logic and delegates to the domain layer
 * (StoneAuthority + RenewalTracker).
 */
object CommandHandlers {

    private const val INSPECT_MAX_CHUNK_PROBES = 4
    private const val INSPECT_MAX_OTHER_IDENTIFIERS = 7
    private const val INSPECT_MAX_PLAYERS = 6
    private const val LIST_MAX_ROWS = 4

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

    @Volatile
    private var renewalProjection: RenewalProjection? = null

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

    fun attachRenewalProjection(projection: RenewalProjection) {
        renewalProjection = projection
    }

    fun detachRenewalProjection() {
        renewalProjection = null
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

            val scope = when (kind) {
                null -> "all"
                StoneKind.WITHERSTONE -> "witherstone"
                StoneKind.LORESTONE -> "lorestone"
            }

            val lines = mutableListOf<String>()
            lines += "scope: $scope"
            stones.take(LIST_MAX_ROWS).forEach { stone ->
                lines += "- ${formatStoneLine(stone)}"
            }
            val remaining = stones.size - LIST_MAX_ROWS
            if (remaining > 0) {
                lines += "and $remaining more"
            }

            sendCompactReport(
                source = source,
                header = "Stones (${stones.size})",
                body = lines,
                maxBodyLines = 6,
            )
            1
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=list failed", e)
            source.sendError(Text.literal("[Memento] could not list stones (see server log)."))
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

            val lines = formatStoneInspect(source, stone)
            sendCompactReport(
                source = source,
                header = lines.first(),
                body = lines.drop(1),
                maxBodyLines = 9,
            )
            1
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=inspect failed name='{}'", e, name)
            source.sendError(Text.literal("[Memento] could not inspect stone (see server log)."))
            0
        }
    }

    fun inspect(source: ServerCommandSource): Int {
        return try {
            val lines = formatInspectSummary(source)
            sendCompactReport(
                source = source,
                header = lines.first(),
                body = lines.drop(1),
                maxBodyLines = 5,
            )
            1
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=inspect summary failed", e)
            source.sendError(Text.literal("[Memento] could not inspect status (see server log)."))
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
            source.sendFeedback({ Text.literal("Visualizing '$name' briefly.").formatted(Formatting.YELLOW) }, false)
            1
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=visualize failed name='{}'", e, name)
            source.sendError(Text.literal("[Memento] could not visualize stone (see server log)."))
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
                { Text.literal("Visualizing ${stones.size} stones briefly.").formatted(Formatting.YELLOW) },
                false
            )
            1
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=visualize all failed", e)
            source.sendError(Text.literal("[Memento] could not visualize stones (see server log)."))
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
            source.sendError(Text.literal("[Memento] could not start scan (see server log)."))
            0
        }
    }

    fun renew(source: ServerCommandSource): Int {
        val projection = renewalProjection
        if (projection == null) {
            source.sendError(Text.literal("Renewal projection is not ready yet."))
            return 0
        }

        val status = projection.statusView()
        if (status.state != RenewalAnalysisState.STABLE) {
            source.sendError(Text.literal("[Memento] renewal analysis is not stable yet."))
            return 0
        }

        val decision = projection.decisionView()
        if (decision == null) {
            source.sendError(Text.literal("[Memento] no eligible renewal target identified."))
            return 0
        }

        when (decision) {
            is RenewalDecision.Region -> {
                MementoLog.info(
                    MementoConcept.RENEWAL,
                    "renew simulation decision grain=region world={} region=({}, {}) by={}",
                    decision.region.worldId,
                    decision.region.regionX,
                    decision.region.regionZ,
                    source.name,
                )
                source.sendFeedback(
                    { Text.literal("[Memento] simulated region renewal for ${decision.region.worldId} r(${decision.region.regionX},${decision.region.regionZ}).").formatted(Formatting.YELLOW) },
                    false
                )
            }

            is RenewalDecision.ChunkBatch -> {
                MementoLog.info(
                    MementoConcept.RENEWAL,
                    "renew simulation decision grain=chunk count={} by={}",
                    decision.chunks.size,
                    source.name,
                )
                source.sendFeedback(
                    { Text.literal("[Memento] simulated chunk-batch renewal for ${decision.chunks.size} chunks.").formatted(Formatting.YELLOW) },
                    false
                )
            }
        }

        return 1
    }

    fun addWitherstone(source: ServerCommandSource, name: String, radius: Int, daysToMaturity: Int): Int {
        MementoLog.info(MementoConcept.OPERATOR, "command=addWitherstone name='{}' radius={} daysToMaturity={} by={}", name, radius, daysToMaturity, source.name)
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
            { Text.literal("Witherstone '$name' added at ${pos.x},${pos.y},${pos.z}.").formatted(Formatting.GREEN) },
            false
        )
        return 1
    }

    fun addLorestone(source: ServerCommandSource, name: String, radius: Int): Int {
        MementoLog.info(MementoConcept.OPERATOR, "command=addLorestone name='{}' radius={} by={}", name, radius, source.name)
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
            { Text.literal("Lorestone '$name' added at ${pos.x},${pos.y},${pos.z}.").formatted(Formatting.GREEN) },
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
            source.sendError(Text.literal("[Memento] could not remove stone (see server log)."))
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
            source.sendError(Text.literal("[Memento] could not alter radius (see server log)."))
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
            source.sendError(Text.literal("[Memento] could not alter daysToMaturity (see server log)."))
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

    private fun formatInspectSummary(source: ServerCommandSource): List<String> {
        val lines = mutableListOf("Memento inspect")

        val stones = StoneAuthority.list().sortedBy { it.name }
        val withers = stones.filterIsInstance<Witherstone>()
        val lores = stones.filterIsInstance<Lorestone>()
        val witherStates = withers
            .groupingBy { it.state }
            .eachCount()

        lines += "Stones: total=${stones.size}, witherstone=${withers.size}, lorestone=${lores.size}"
        if (witherStates.isNotEmpty()) {
            lines += "  Witherstone states: ${witherStates.entries.sortedBy { it.key.name }.joinToString(", ") { "${it.key.name.lowercase(Locale.ROOT)}=${it.value}" }}"
        }

        val batches = RenewalTracker.snapshotBatches()
        if (batches.isNotEmpty()) {
            val states = batches.groupingBy { it.state }.eachCount()
            lines += "Renewal: active batches=${batches.size}"
            lines += "  States: ${states.entries.sortedBy { it.key.name }.joinToString(", ") { "${it.key.name.lowercase(Locale.ROOT)}=${it.value}" }}"
        }

        val scannerStatus = worldScanner?.statusView()
        val projection = renewalProjection
        val projectionStatus = projection?.statusView()

        val schedulerLine = when {
            scannerStatus?.active == true -> {
                val durationText = scannerStatus.runningDurationMs?.let { " for ${formatDuration(it)}" } ?: ""
                "Scheduler: surveying the world$durationText."
            }

            projectionStatus != null && projectionStatus.state == RenewalAnalysisState.STABILIZING -> {
                val durationText = projectionStatus.runningDurationMs?.let { " for ${formatDuration(it)}" } ?: ""
                "Scheduler: evaluating renewal options$durationText."
            }

            projectionStatus?.blockedOnGate == true -> {
                "Scheduler: waiting for the world survey to finish."
            }

            projectionStatus?.lastCompletedReason == "SCAN_COMPLETED" && projectionStatus.lastCompletedDurationMs != null -> {
                "Scheduler: renewal evaluation completed after ${formatDuration(projectionStatus.lastCompletedDurationMs)}."
            }

            scannerStatus?.lastCompletedDurationMs != null -> {
                "Scheduler: world survey completed after ${formatDuration(scannerStatus.lastCompletedDurationMs)}."
            }

            else -> "Scheduler: idle."
        }
        lines += schedulerLine

        if (scannerStatus != null && scannerStatus.worldMapTotal > 0) {
            lines += "World memory: ${scannerStatus.worldMapScanned}/${scannerStatus.worldMapTotal} chunks confirmed, ${scannerStatus.worldMapMissing} still uncertain"
        }

        if (projectionStatus != null && projectionStatus.state == RenewalAnalysisState.STABLE) {
            when (val d = projection.decisionView()) {
                is RenewalDecision.Region ->
                    lines += "Renewal target: region ${d.region.worldId} r(${d.region.regionX},${d.region.regionZ})"
                is RenewalDecision.ChunkBatch ->
                    lines += "Renewal target: ${d.chunks.size} chunks"
                null ->
                    lines += "Renewal target: none"
            }
        }

        return lines
    }

    private fun formatStoneInspect(source: ServerCommandSource, stone: Stone): List<String> {
        val lines = mutableListOf<String>()

        when (stone) {
            is Witherstone -> {
                lines += "Witherstone '${stone.name}'"
                lines += "dimension: ${stone.dimension}"
                lines += "position: (${stone.position.x},${stone.position.y},${stone.position.z})"
                lines += "radius: ${stone.radius}"
                lines += "daysToMaturity: ${stone.daysToMaturity}"
                lines += "state: ${stone.state}"

                val influenceCount = StoneMapService.influencedChunks(stone).size
                lines += "influenced chunks: $influenceCount"

                val batch = RenewalTracker.snapshotBatches().firstOrNull { it.name == stone.name }
                if (batch != null) {
                    lines += "renewal batch: ${batch.state} (${batch.chunks.size} chunks)"
                    lines += waitingExplanation(batch.state)

                    if (batch.state == RenewalBatchState.WAITING_FOR_UNLOAD) {
                        val blockers = probeUnloadBlockers(
                            server = source.server,
                            stone = stone,
                            batch = batch,
                        )
                        if (blockers.players.isNotEmpty()) {
                            lines += "Players: ${blockers.players.joinToString(", ")}"
                        }
                        if (blockers.otherNames.isNotEmpty()) {
                            lines += "Other: ${blockers.otherNames.joinToString(", ")}"
                        }
                    }
                }
            }

            is Lorestone -> {
                lines += "Lorestone '${stone.name}'"
                lines += "dimension: ${stone.dimension}"
                lines += "position: (${stone.position.x},${stone.position.y},${stone.position.z})"
                lines += "radius: ${stone.radius}"
                val influenceCount = StoneMapService.influencedChunks(stone).size
                lines += "influenced chunks: $influenceCount"
            }
        }

        return lines
    }

    private fun waitingExplanation(state: RenewalBatchState): String =
        when (state) {
            RenewalBatchState.WAITING_FOR_UNLOAD ->
                "waiting: for chunks to unload naturally"

            RenewalBatchState.WAITING_FOR_RENEWAL ->
                "waiting: for post-unload load evidence"

            RenewalBatchState.UNLOAD_COMPLETED ->
                "waiting: unload gate just passed"

            RenewalBatchState.RENEWAL_COMPLETE ->
                "waiting: none (renewal complete)"
        }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    private data class UnloadBlockers(
        val players: List<String>,
        val otherNames: List<String>,
    )

    private fun probeUnloadBlockers(
        server: MinecraftServer,
        stone: StoneView,
        batch: RenewalBatchSnapshot,
    ): UnloadBlockers {
        val world = server.getWorld(stone.dimension) ?: return UnloadBlockers(emptyList(), emptyList())

        val targetChunks = batch.chunks
            .asSequence()
            .sortedWith(compareBy({ it.x }, { it.z }))
            .take(INSPECT_MAX_CHUNK_PROBES)
            .toList()

        if (targetChunks.isEmpty()) {
            return UnloadBlockers(emptyList(), emptyList())
        }

        val players = linkedSetOf<String>()
        val others = linkedSetOf<String>()

        for (pos in targetChunks) {
            playersNearChunk(server, world, pos).forEach { players += it }

            val loaded = world.chunkManager.getChunk(pos.x, pos.z, net.minecraft.world.chunk.ChunkStatus.FULL, false)
                as? net.minecraft.world.chunk.WorldChunk
                ?: continue

            collectEntityIdentifiers(world, pos, others)
            collectBlockEntityIdentifiers(loaded, others)

            if (others.size >= INSPECT_MAX_OTHER_IDENTIFIERS) break
        }

        return UnloadBlockers(
            players = capNames(players.toList(), INSPECT_MAX_PLAYERS),
            otherNames = others.take(INSPECT_MAX_OTHER_IDENTIFIERS),
        )
    }

    private fun capNames(names: List<String>, max: Int): List<String> {
        if (names.size <= max) return names
        val head = names.take(max)
        return head + "and more"
    }

    private fun sendCompactReport(
        source: ServerCommandSource,
        header: String,
        body: List<String>,
        maxBodyLines: Int,
    ) {
        source.sendFeedback({ Text.literal(header).formatted(Formatting.GOLD) }, false)
        body
            .take(maxBodyLines)
            .forEach { line ->
                source.sendFeedback({ Text.literal(line).formatted(Formatting.GRAY) }, false)
            }
    }

    private fun playersNearChunk(
        server: MinecraftServer,
        world: ServerWorld,
        pos: ChunkPos,
    ): List<String> {
        val maxDist = server.playerManager.viewDistance
        return world.players
            .asSequence()
            .filter {
                val dx = kotlin.math.abs(it.chunkPos.x - pos.x)
                val dz = kotlin.math.abs(it.chunkPos.z - pos.z)
                dx <= maxDist && dz <= maxDist
            }
            .map { it.gameProfile.name }
            .distinct()
            .sorted()
            .toList()
    }

    private fun collectEntityIdentifiers(
        world: ServerWorld,
        pos: ChunkPos,
        out: MutableSet<String>,
    ) {
        if (out.size >= INSPECT_MAX_OTHER_IDENTIFIERS) return

        val box = Box(
            pos.startX.toDouble(),
            world.bottomY.toDouble(),
            pos.startZ.toDouble(),
            (pos.startX + 16).toDouble(),
            320.0,
            (pos.startZ + 16).toDouble(),
        )

        val entities = world.getOtherEntities(null, box)
        for (entity in entities) {
            if (out.size >= INSPECT_MAX_OTHER_IDENTIFIERS) break
            if (entity is net.minecraft.server.network.ServerPlayerEntity) continue

            val name = if (entity is ItemEntity) {
                Registries.ITEM.getId(entity.stack.item).path
            } else {
                Registries.ENTITY_TYPE.getId(entity.type).path
            }
            if (name.isNotBlank()) out += name
        }
    }

    private fun collectBlockEntityIdentifiers(
        chunk: net.minecraft.world.chunk.WorldChunk,
        out: MutableSet<String>,
    ) {
        if (out.size >= INSPECT_MAX_OTHER_IDENTIFIERS) return

        for (be in chunk.blockEntities.values) {
            if (out.size >= INSPECT_MAX_OTHER_IDENTIFIERS) break
            val id = Registries.BLOCK_ENTITY_TYPE.getId(be.type)?.path ?: continue
            if (id.isNotBlank()) out += id
        }
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
