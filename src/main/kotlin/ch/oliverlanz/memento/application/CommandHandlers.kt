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
import ch.oliverlanz.memento.domain.renewal.RenewalTrigger
import ch.oliverlanz.memento.domain.renewal.projection.RegionKey
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCandidateAction
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCandidateId
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCommittedSnapshot
import ch.oliverlanz.memento.domain.renewal.projection.RenewalRankedCandidate
import ch.oliverlanz.memento.domain.renewal.projection.RenewalProjection
import ch.oliverlanz.memento.domain.renewal.projection.toChunkKeyOrNull
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
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.Formatting
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Box
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import ch.oliverlanz.memento.application.visualization.EffectsHost
import ch.oliverlanz.memento.infrastructure.pruning.WorldPruningService
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import ch.oliverlanz.memento.infrastructure.worldscan.MementoCsvWriter
import ch.oliverlanz.memento.infrastructure.worldscan.WorldScanner
import java.util.Locale
import kotlin.collections.ArrayDeque

/**
 * Application-layer command handlers.
 *
 * Commands.kt defines the authoritative command grammar.
 * This file contains the execution logic and delegates to the domain layer
 * (StoneAuthority + RenewalTracker).
 */
object CommandHandlers {

    private data class PreviewBinding(
        val storedGeneration: Long,
        val storedItems: List<RenewalRankedCandidate>,
    )

    private data class ForceQueue(
        val owner: String,
        var generation: Long,
        val pending: ArrayDeque<RenewalRankedCandidate>,
        val total: Int,
        var submitted: Int = 0,
        var lastWaitReason: String? = null,
        var lastSeenPruneCompletionKey: String? = null,
    )

    private enum class QueueSubmitResult {
        SUBMITTED,
        WAIT_RETRY,
        HARD_FAIL,
    }

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

    @Volatile
    private var renewPreviewBinding: PreviewBinding? = null

    @Volatile
    private var renewForceQueue: ForceQueue? = null

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

    fun onMediumPulse() {
        processRenewForceQueue()
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
        val snapshot = committedSnapshotOrSendError(source) ?: return 0
        val items = snapshot.rankedCandidates.take(10)
        renewPreviewBinding = PreviewBinding(
            storedGeneration = snapshot.generation,
            storedItems = items,
        )

        runCatching {
            MementoCsvWriter.writeEligibilitySnapshot(
                server = source.server,
                snapshot = snapshot,
            )
        }.onFailure { t ->
            MementoLog.error(
                MementoConcept.RENEWAL,
                "renew preview csv export failed generation={} by={}",
                t,
                snapshot.generation,
                source.name,
            )
        }

        if (items.isEmpty()) {
            source.sendFeedback(
                { Text.literal("[Memento] no eligible renewal candidates found.").formatted(Formatting.YELLOW) },
                false,
            )
            return 1
        }

        source.sendFeedback(
            { Text.literal("[Memento] renewal preview (top ${items.size}):").formatted(Formatting.GOLD) },
            false,
        )
        items.forEach { candidate ->
            source.sendFeedback({ Text.literal(formatPreviewCandidate(candidate)).formatted(Formatting.GRAY) }, false)
        }

        MementoLog.info(
            MementoConcept.RENEWAL,
            "renew preview stored generation={} items={} by={}",
            snapshot.generation,
            items.size,
            source.name,
        )
        return 1
    }

    fun renewForce(source: ServerCommandSource): Int {
        return renewForce(source, 1)
    }

    fun renewForce(source: ServerCommandSource, count: Int): Int {
        if (count <= 0) {
            source.sendError(Text.literal("[Memento] force count must be > 0."))
            return 0
        }

        if (renewForceQueue != null) {
            source.sendError(Text.literal("[Memento] a force sequence is already running; wait for completion."))
            return 0
        }

        val preview = renewPreviewBinding
        if (preview == null) {
            source.sendError(Text.literal("[Memento] no preview stored. Run /memento renew first."))
            return 0
        }

        val snapshot = committedSnapshotOrSendError(source) ?: return 0

        val projection = renewalProjection
        if (projection == null) {
            source.sendError(Text.literal("Renewal projection is not ready yet."))
            return 0
        }

        val requested = preview.storedItems.take(count)
        if (requested.isEmpty()) {
            source.sendError(Text.literal("[Memento] no stored preview items available for force renewal."))
            return 0
        }

        if (snapshot.generation != preview.storedGeneration) {
            MementoLog.info(
                MementoConcept.RENEWAL,
                "renew force sequence generation drift-at-enqueue storedGeneration={} currentGeneration={} requested={} by={}",
                preview.storedGeneration,
                snapshot.generation,
                requested.size,
                source.name,
            )
        }

        // Capture an immutable copy of preview items for this sequence execution.
        // Later `/memento renew` preview refreshes may replace renewPreviewBinding,
        // but must not alter the in-flight force sequence worklist.
        val pending = ArrayDeque<RenewalRankedCandidate>()
        requested.forEach { pending.addLast(it) }

        val completion = WorldPruningService.lastCompletionOrNull()
        renewForceQueue = ForceQueue(
            owner = source.name,
            generation = snapshot.generation,
            pending = pending,
            total = requested.size,
            lastSeenPruneCompletionKey = completion?.let { completionKey(it) },
        )

        MementoLog.info(
            MementoConcept.RENEWAL,
            "renew force sequence enqueued generation={} items={} by={}",
            snapshot.generation,
            requested.size,
            source.name,
        )

        source.sendFeedback(
            {
                Text.literal(
                    "[Memento] force sequence started with ${requested.size} item(s). " +
                        "Execution will continue as pruning/projection gates allow."
                ).formatted(Formatting.YELLOW)
            },
            false,
        )

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

            projectionStatus?.runningDurationMs != null -> {
                "Scheduler: evaluating renewal options for ${formatDuration(projectionStatus.runningDurationMs)}."
            }

            projectionStatus?.blockedOnGate == true -> {
                "Scheduler: waiting for the world survey to finish."
            }

            projectionStatus?.lastCompletedReason == "SCAN_COMPLETED" && projectionStatus.lastCompletedDurationMs != null -> {
                "Scheduler: renewal analysis completed after ${formatDuration(projectionStatus.lastCompletedDurationMs)}."
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

        if (projectionStatus != null) {
            lines += "Renewal target: evaluated on demand (generation ${projectionStatus.committedGeneration})"
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

    private fun committedSnapshotOrSendError(source: ServerCommandSource): RenewalCommittedSnapshot? {
        val scanner = worldScanner
        if (scanner == null || !scanner.hasInitialScanCompleted()) {
            MementoLog.info(MementoConcept.OPERATOR, "renew force/renew rejected reason=initial_scan_not_completed by={}", source.name)
            source.sendError(Text.literal("[Memento] renewal preview is unavailable before first full world scan completion."))
            return null
        }

        val projection = renewalProjection
        if (projection == null) {
            MementoLog.info(MementoConcept.OPERATOR, "renew force/renew rejected reason=projection_not_ready by={}", source.name)
            source.sendError(Text.literal("Renewal projection is not ready yet."))
            return null
        }

        val committed = projection.committedSnapshotOrNull()
        if (committed == null) {
            MementoLog.info(MementoConcept.OPERATOR, "renew force/renew rejected reason=projection_snapshot_unavailable by={}", source.name)
            source.sendError(Text.literal("[Memento] renewal analysis snapshot is unavailable."))
            return null
        }

        return committed
    }

    private fun forceRegionRenew(source: ServerCommandSource, decision: RegionKey): Int {
        MementoLog.info(
            MementoConcept.PRUNING,
            "renew force request grain=region world={} region=({}, {}) by={}",
            decision.worldId,
            decision.regionX,
            decision.regionZ,
            source.name,
        )

        val dimension = resolveWorldKey(decision.worldId)
        if (dimension == null) {
            MementoLog.info(
                MementoConcept.PRUNING,
                "renew force rejected reason=invalid_decision_world world={} by={}",
                decision.worldId,
                source.name,
            )
            source.sendError(Text.literal("[Memento] projection returned an invalid world id for force renewal."))
            return 0
        }

        return when (val result = WorldPruningService.submit(dimension, decision)) {
            is WorldPruningService.SubmitResult.Submitted -> {
                source.sendFeedback(
                    {
                        Text.literal(
                            "[Memento] renew force submitted for region ${result.target.worldId} r(${result.target.regionX},${result.target.regionZ})."
                        ).formatted(Formatting.YELLOW)
                    },
                    false
                )
                1
            }

            is WorldPruningService.SubmitResult.Completed -> {
                val c = result.completion
                source.sendFeedback(
                    {
                        Text.literal(
                            "[Memento] renew force outcome=${c.outcome.name} world=${c.target.worldId} r(${c.target.regionX},${c.target.regionZ})"
                        ).formatted(if (c.outcome == WorldPruningService.Outcome.success || c.outcome == WorldPruningService.Outcome.pruned_with_residual_backup) Formatting.YELLOW else Formatting.RED)
                    },
                    false
                )
                if (c.outcome == WorldPruningService.Outcome.success || c.outcome == WorldPruningService.Outcome.pruned_with_residual_backup) 1 else 0
            }
        }
    }

    private fun resolveWorldKey(worldId: String): RegistryKey<net.minecraft.world.World>? {
        return runCatching {
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of(worldId))
        }.getOrNull()
    }

    private fun forceChunkBatchRenew(source: ServerCommandSource, chunkKeys: List<ch.oliverlanz.memento.domain.worldmap.ChunkKey>): Int {
        val chunksByWorld = chunkKeys.groupBy { it.world }
        if (chunksByWorld.isEmpty()) {
            source.sendError(Text.literal("[Memento] no chunks available for force renewal."))
            return 0
        }

        var submitted = 0
        chunksByWorld.entries.sortedBy { it.key.value.toString() }.forEach { (world, chunks) ->
            val scope = chunks.map { ChunkPos(it.chunkX, it.chunkZ) }.toSet()
            val minX = chunks.minOf { it.chunkX }
            val minZ = chunks.minOf { it.chunkZ }
            val maxX = chunks.maxOf { it.chunkX }
            val maxZ = chunks.maxOf { it.chunkZ }
            val batchName =
                "${ch.oliverlanz.memento.MementoConstants.MEMENTO_RENEW_FORCE_BATCH_PREFIX}${world.value}_${minX}_${minZ}_${maxX}_${maxZ}_${scope.size}"

            RenewalTracker.upsertBatchDefinition(
                name = batchName,
                dimension = world,
                chunks = scope,
                trigger = RenewalTrigger.MANUAL,
            )
            submitted += scope.size
        }

        MementoLog.info(
            MementoConcept.RENEWAL,
            "renew force decision grain=chunk count={} groups={} by={}",
            submitted,
            chunksByWorld.size,
            source.name,
        )
        source.sendFeedback(
            { Text.literal("[Memento] force-renew queued ${submitted} chunks across ${chunksByWorld.size} renewal group(s). ").formatted(Formatting.YELLOW) },
            false
        )
        return 1
    }

    private fun executeCandidates(source: ServerCommandSource, candidates: List<RenewalRankedCandidate>): Int {
        var successCount = 0
        val regions = mutableListOf<RegionKey>()
        val chunks = mutableListOf<ch.oliverlanz.memento.domain.worldmap.ChunkKey>()

        candidates.forEach { candidate ->
            when (candidate.id.action) {
                RenewalCandidateAction.REGION_PRUNE -> {
                    val region = RegionKey(
                        worldId = candidate.id.worldKey,
                        regionX = candidate.id.regionX ?: return@forEach,
                        regionZ = candidate.id.regionZ ?: return@forEach,
                    )
                    regions += region
                }

                RenewalCandidateAction.CHUNK_RENEW -> {
                    val key = candidate.id.toChunkKeyOrNull() ?: return@forEach
                    chunks += key
                }
            }
        }

        regions.forEach { region ->
            val ok = forceRegionRenew(source, region)
            if (ok == 0) return 0
            successCount++
        }

        if (chunks.isNotEmpty()) {
            val ok = forceChunkBatchRenew(source, chunks)
            if (ok == 0) return 0
            successCount += chunks.size
        }

        candidates.forEach { candidate ->
            MementoLog.info(
                MementoConcept.RENEWAL,
                "renew force item outcome=submitted rank={} id={} by={}",
                candidate.rank,
                candidate.id,
                source.name,
            )
        }

        return if (successCount > 0) 1 else 0
    }

    private fun processRenewForceQueue() {
        // Hot path guard: do not evaluate gate surfaces when there is no queued work.
        val queue = renewForceQueue ?: return

        val projection = renewalProjection
        if (projection == null) {
            abortQueue(queue, "projection_unavailable")
            return
        }

        val latestCompletion = WorldPruningService.lastCompletionOrNull()
        if (latestCompletion != null) {
            val key = completionKey(latestCompletion)
            if (queue.lastSeenPruneCompletionKey != key) {
                queue.lastSeenPruneCompletionKey = key
                val success = latestCompletion.outcome == WorldPruningService.Outcome.success ||
                    latestCompletion.outcome == WorldPruningService.Outcome.pruned_with_residual_backup
                if (!success) {
                    abortQueue(queue, "prune_completion_failed outcome=${latestCompletion.outcome.name}")
                    return
                }
                MementoLog.info(
                    MementoConcept.RENEWAL,
                    "renew force sequence prune completion accepted world={} region=({}, {}) outcome={} owner={}",
                    latestCompletion.target.worldId,
                    latestCompletion.target.regionX,
                    latestCompletion.target.regionZ,
                    latestCompletion.outcome.name,
                    queue.owner,
                )
            }
        }

        val snapshot = projection.committedSnapshotOrNull()
        if (snapshot == null) {
            abortQueue(queue, "projection_snapshot_unavailable")
            return
        }

        if (snapshot.generation != queue.generation) {
            MementoLog.info(
                MementoConcept.RENEWAL,
                "renew force sequence drift detected storedGeneration={} currentGeneration={} pending={} owner={}",
                queue.generation,
                snapshot.generation,
                queue.pending.size,
                queue.owner,
            )

            val invalid = queue.pending.firstOrNull { !projection.isStillEligible(snapshot, it.id) }
            if (invalid != null) {
                MementoLog.info(
                    MementoConcept.RENEWAL,
                    "renew force sequence revalidation failed generation={} rank={} id={} owner={}",
                    snapshot.generation,
                    invalid.rank,
                    invalid.id,
                    queue.owner,
                )
                abortQueue(queue, "drift_revalidation_failed rank=${invalid.rank} id=${invalid.id}")
                return
            }

            MementoLog.info(
                MementoConcept.RENEWAL,
                "renew force sequence revalidation passed generation={} count={} owner={}",
                snapshot.generation,
                queue.pending.size,
                queue.owner,
            )
            queue.generation = snapshot.generation
        }

        if (queue.pending.isEmpty()) {
            MementoLog.info(
                MementoConcept.RENEWAL,
                "renew force sequence completed owner={} total={} submitted={}",
                queue.owner,
                queue.total,
                queue.submitted,
            )
            renewForceQueue = null
            return
        }

        if (!WorldPruningService.isIdle()) {
            logQueueWait(queue, "pruning_busy")
            return
        }

        if (projection.hasPendingChanges()) {
            logQueueWait(queue, "projection_pending")
            return
        }

        val candidate = queue.pending.first()
        if (!projection.isStillEligible(snapshot, candidate.id)) {
            MementoLog.info(
                MementoConcept.RENEWAL,
                "renew force sequence revalidation failed generation={} rank={} id={} owner={}",
                snapshot.generation,
                candidate.rank,
                candidate.id,
                queue.owner,
            )
            abortQueue(queue, "item_revalidation_failed rank=${candidate.rank} id=${candidate.id}")
            return
        }

        when (submitQueuedCandidate(candidate, queue.owner)) {
            QueueSubmitResult.SUBMITTED -> Unit
            QueueSubmitResult.WAIT_RETRY -> return
            QueueSubmitResult.HARD_FAIL -> {
                abortQueue(queue, "submit_failed rank=${candidate.rank} id=${candidate.id}")
                return
            }
        }

        queue.pending.removeFirst()
        queue.submitted++
        queue.lastWaitReason = null

        MementoLog.info(
            MementoConcept.RENEWAL,
            "renew force sequence submit generation={} rank={} id={} owner={} remaining={}",
            snapshot.generation,
            candidate.rank,
            candidate.id,
            queue.owner,
            queue.pending.size,
        )
    }

    private fun submitQueuedCandidate(candidate: RenewalRankedCandidate, owner: String): QueueSubmitResult {
        return when (candidate.id.action) {
            RenewalCandidateAction.REGION_PRUNE -> {
                val region = RegionKey(
                    worldId = candidate.id.worldKey,
                    regionX = candidate.id.regionX ?: return QueueSubmitResult.HARD_FAIL,
                    regionZ = candidate.id.regionZ ?: return QueueSubmitResult.HARD_FAIL,
                )

                val dimension = resolveWorldKey(region.worldId) ?: return QueueSubmitResult.HARD_FAIL
                when (val submit = WorldPruningService.submit(dimension, region)) {
                    is WorldPruningService.SubmitResult.Submitted -> QueueSubmitResult.SUBMITTED
                    is WorldPruningService.SubmitResult.Completed -> {
                        val completion = submit.completion
                        val temporaryBusy = completion.outcome == WorldPruningService.Outcome.rejected_busy
                        if (temporaryBusy) {
                            MementoLog.info(
                                MementoConcept.RENEWAL,
                                "renew force sequence wait reason=pruning_rejected_busy rank={} id={} owner={} detail={}",
                                candidate.rank,
                                candidate.id,
                                owner,
                                completion.detail,
                            )
                            QueueSubmitResult.WAIT_RETRY
                        } else {
                            MementoLog.info(
                                MementoConcept.RENEWAL,
                                "renew force sequence submit completed-immediate rank={} id={} owner={} outcome={} detail={}",
                                candidate.rank,
                                candidate.id,
                                owner,
                                completion.outcome.name,
                                completion.detail,
                            )
                            QueueSubmitResult.HARD_FAIL
                        }
                    }
                }
            }

            RenewalCandidateAction.CHUNK_RENEW -> {
                val key = candidate.id.toChunkKeyOrNull() ?: return QueueSubmitResult.HARD_FAIL
                if (forceChunkBatchRenewQueued(owner, listOf(key))) {
                    QueueSubmitResult.SUBMITTED
                } else {
                    QueueSubmitResult.HARD_FAIL
                }
            }
        }
    }

    private fun forceChunkBatchRenewQueued(owner: String, chunkKeys: List<ch.oliverlanz.memento.domain.worldmap.ChunkKey>): Boolean {
        val chunksByWorld = chunkKeys.groupBy { it.world }
        if (chunksByWorld.isEmpty()) return false

        var submitted = 0
        chunksByWorld.entries.sortedBy { it.key.value.toString() }.forEach { (world, chunks) ->
            val scope = chunks.map { ChunkPos(it.chunkX, it.chunkZ) }.toSet()
            val minX = chunks.minOf { it.chunkX }
            val minZ = chunks.minOf { it.chunkZ }
            val maxX = chunks.maxOf { it.chunkX }
            val maxZ = chunks.maxOf { it.chunkZ }
            val batchName =
                "${ch.oliverlanz.memento.MementoConstants.MEMENTO_RENEW_FORCE_BATCH_PREFIX}${world.value}_${minX}_${minZ}_${maxX}_${maxZ}_${scope.size}"

            RenewalTracker.upsertBatchDefinition(
                name = batchName,
                dimension = world,
                chunks = scope,
                trigger = RenewalTrigger.MANUAL,
            )
            submitted += scope.size
        }

        MementoLog.info(
            MementoConcept.RENEWAL,
            "renew force sequence decision grain=chunk count={} groups={} by={}",
            submitted,
            chunksByWorld.size,
            owner,
        )
        return submitted > 0
    }

    private fun logQueueWait(queue: ForceQueue, reason: String) {
        if (queue.lastWaitReason == reason) return
        queue.lastWaitReason = reason
        MementoLog.info(
            MementoConcept.RENEWAL,
            "renew force sequence wait reason={} owner={} pending={} submitted={}",
            reason,
            queue.owner,
            queue.pending.size,
            queue.submitted,
        )
    }

    private fun abortQueue(queue: ForceQueue, reason: String) {
        MementoLog.info(
            MementoConcept.RENEWAL,
            "renew force sequence aborted reason={} owner={} total={} submitted={} remaining={}",
            reason,
            queue.owner,
            queue.total,
            queue.submitted,
            queue.pending.size,
        )
        renewForceQueue = null
    }

    private fun completionKey(completion: WorldPruningService.Completion): String {
        return "${completion.target.worldId}:${completion.target.regionX}:${completion.target.regionZ}:${completion.outcome.name}:${completion.detail ?: ""}"
    }

    private fun formatPreviewCandidate(candidate: RenewalRankedCandidate): String {
        val prefix = "#${candidate.rank}"
        return when (candidate.id.action) {
            RenewalCandidateAction.REGION_PRUNE -> {
                "$prefix region prune world=${candidate.id.worldKey} r(${candidate.id.regionX},${candidate.id.regionZ})"
            }

            RenewalCandidateAction.CHUNK_RENEW -> {
                "$prefix chunk renew world=${candidate.id.worldKey} c(${candidate.id.chunkX},${candidate.id.chunkZ})"
            }
        }
    }
}
