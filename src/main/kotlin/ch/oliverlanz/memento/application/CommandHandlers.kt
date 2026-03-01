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
import ch.oliverlanz.memento.domain.stones.WitherstoneState
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
import ch.oliverlanz.memento.infrastructure.observability.OperatorMessages
import ch.oliverlanz.memento.infrastructure.worldscan.MementoCsvWriter
import ch.oliverlanz.memento.infrastructure.worldscan.WorldScanner
import java.util.Locale

/**
 * Application-layer command handlers.
 *
 * Commands.kt defines the authoritative command grammar.
 * This file contains the execution logic and delegates to the domain layer
 * (StoneAuthority + RenewalTracker).
 *
 * Local policy ownership:
 * - `/memento do renew [N]` consumes the current committed projection snapshot at submit-time.
 * - No command-owned preview queue, stored-plan lifecycle, or generation-bound worklist is kept.
 * - Per-iteration revalidation happens against current committed snapshot before each submission.
 * - Region-prune request batching is bounded by `DO_RENEWAL_MAX_REGION_BATCH`.
 */
object CommandHandlers {

    private const val INSPECT_MAX_CHUNK_PROBES = 4
    private const val INSPECT_MAX_OTHER_IDENTIFIERS = 7
    private const val INSPECT_MAX_PLAYERS = 6
    private const val LIST_MAX_ROWS = 4
    private const val DEFAULT_EXPLAIN_TOP_LIMIT = 10
    private const val DO_RENEWAL_MAX_REGION_BATCH = 10

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

    fun onMediumPulse() {
        // No-op by doctrine: operator command execution no longer owns queued lifecycle state.
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

    fun explain(source: ServerCommandSource): Int {
        return try {
            val lines = formatExplainSummary(source)
            sendCompactReport(
                source = source,
                header = lines.first(),
                body = lines.drop(1),
                maxBodyLines = 9,
            )
            1
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=explain failed", e)
            source.sendError(Text.literal("[Memento] could not explain status (see server log)."))
            0
        }
    }

    fun explainStones(source: ServerCommandSource, kind: StoneKind?): Int {
        return list(kind, source)
    }

    fun explainWorld(source: ServerCommandSource): Int {
        return try {
            val lines = formatExplainWorld(source)
            sendCompactReport(
                source = source,
                header = lines.first(),
                body = lines.drop(1),
                maxBodyLines = 9,
            )
            1
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=explain world failed", e)
            source.sendError(Text.literal("[Memento] could not explain world status (see server log)."))
            0
        }
    }

    fun explainRenewal(source: ServerCommandSource): Int {
        return try {
            val projection = renewalProjection
            val snapshot = projection?.committedSnapshotOrNull()
            // Presentation boundary only:
            // This explain surface aggregates already-derived mechanism outputs for operators.
            // It must not merge, alter, or feed back into region/chunk derivation semantics.
            val topCandidates = snapshot?.electionCandidates?.take(DEFAULT_EXPLAIN_TOP_LIMIT).orEmpty()
            val topRegionCandidates = topCandidates.filter { it.id.action == RenewalCandidateAction.REGION_PRUNE }
            val topChunkCandidates = topCandidates.filter { it.id.action == RenewalCandidateAction.CHUNK_RENEW }
            val witherstones = StoneAuthority.list().filterIsInstance<Witherstone>().sortedBy { it.name }
            val batchesByName = RenewalTracker.snapshotBatches().associateBy { it.name }

            val waitingToMature = witherstones
                .filter { it.state == WitherstoneState.PLACED || it.state == WitherstoneState.MATURING }
                .map { "- ${it.name}: state=${it.state.name.lowercase(Locale.ROOT)} daysToMaturity=${it.daysToMaturity}" }

            val waitingForConsumption = witherstones
                .filter { it.state == WitherstoneState.MATURED }
                .map { stone ->
                    val batch = batchesByName[stone.name]
                    when {
                        batch == null -> "- ${stone.name}: matured (renewal batch pending)"
                        batch.state == RenewalBatchState.RENEWAL_COMPLETE -> "- ${stone.name}: renewal complete (consumption pending)"
                        else -> "- ${stone.name}: batch=${batch.state.name.lowercase(Locale.ROOT)}"
                    }
                }

            val blockers = witherstones
                .asSequence()
                .filter { it.state == WitherstoneState.MATURED }
                .mapNotNull { stone ->
                    val batch = batchesByName[stone.name] ?: return@mapNotNull null
                    if (batch.state != RenewalBatchState.WAITING_FOR_UNLOAD) return@mapNotNull null
                    val found = probeUnloadBlockers(source.server, stone, batch)
                    val detail = buildList {
                        if (found.players.isNotEmpty()) add("players=${found.players.joinToString(",")}")
                        if (found.otherNames.isNotEmpty()) add("other=${found.otherNames.joinToString(",")}")
                    }
                    if (detail.isEmpty()) {
                        "- ${stone.name}: waiting for natural unload"
                    } else {
                        "- ${stone.name}: ${detail.joinToString("; ")}"
                    }
                }
                .toList()

            source.sendFeedback({ Text.literal("Renewal explanation").formatted(Formatting.GOLD) }, false)

            source.sendFeedback({ Text.literal("1) Natural renewal (region prune candidates)").formatted(Formatting.YELLOW) }, false)
            if (topRegionCandidates.isEmpty()) {
                val waitingInitialScan = worldScanner?.hasInitialScanCompleted() != true
                if (waitingInitialScan) {
                    source.sendFeedback(
                        { Text.literal("waiting: initial world scan must complete before operator renewal candidates are authoritative").formatted(Formatting.GRAY) },
                        false,
                    )
                } else {
                    source.sendFeedback({ Text.literal("none").formatted(Formatting.GRAY) }, false)
                }
            } else {
                topRegionCandidates.forEach { candidate ->
                    source.sendFeedback({ Text.literal(formatCandidateForExplain(candidate)).formatted(Formatting.GRAY) }, false)
                }
            }

            source.sendFeedback({ Text.literal("2) Stone-driven chunk renewal candidates").formatted(Formatting.YELLOW) }, false)
            if (topChunkCandidates.isEmpty()) {
                source.sendFeedback({ Text.literal("none").formatted(Formatting.GRAY) }, false)
            } else {
                topChunkCandidates.forEach { candidate ->
                    source.sendFeedback({ Text.literal(formatCandidateForExplain(candidate)).formatted(Formatting.GRAY) }, false)
                }
            }

            source.sendFeedback({ Text.literal("3) Stones waiting to mature").formatted(Formatting.YELLOW) }, false)
            if (waitingToMature.isEmpty()) {
                source.sendFeedback({ Text.literal("none").formatted(Formatting.GRAY) }, false)
            } else {
                waitingToMature.take(DEFAULT_EXPLAIN_TOP_LIMIT).forEach { line ->
                    source.sendFeedback({ Text.literal(line).formatted(Formatting.GRAY) }, false)
                }
            }

            source.sendFeedback({ Text.literal("4) Stones waiting for consumption").formatted(Formatting.YELLOW) }, false)
            if (waitingForConsumption.isEmpty()) {
                source.sendFeedback({ Text.literal("none").formatted(Formatting.GRAY) }, false)
            } else {
                waitingForConsumption.take(DEFAULT_EXPLAIN_TOP_LIMIT).forEach { line ->
                    source.sendFeedback({ Text.literal(line).formatted(Formatting.GRAY) }, false)
                }
            }

            source.sendFeedback({ Text.literal("5) Blocking conditions").formatted(Formatting.YELLOW) }, false)
            val blockingLines = mutableListOf<String>()
            if (worldScanner?.hasInitialScanCompleted() != true) {
                blockingLines += "- initial world scan is not completed"
            }
            if (projection == null) {
                blockingLines += "- renewal projection is not ready"
            } else {
                if (projection.hasPendingChanges()) blockingLines += "- projection update in progress"
                if (!WorldPruningService.isIdle()) blockingLines += "- pruning service is busy"
            }
            blockingLines += blockers.take(DEFAULT_EXPLAIN_TOP_LIMIT)

            if (blockingLines.isEmpty()) {
                source.sendFeedback({ Text.literal("none").formatted(Formatting.GRAY) }, false)
            } else {
                blockingLines.take(DEFAULT_EXPLAIN_TOP_LIMIT).forEach { line ->
                    source.sendFeedback({ Text.literal(line).formatted(Formatting.GRAY) }, false)
                }
            }

            1
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=explain renewal failed", e)
            source.sendError(Text.literal("[Memento] could not explain renewal status (see server log)."))
            0
        }
    }

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
            val lines = formatExplainSummary(source)
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

    fun csv(source: ServerCommandSource): Int {
        MementoLog.info(MementoConcept.OPERATOR, "command=csv by={}", source.name)

        val scanner = worldScanner
        if (scanner == null) {
            source.sendError(Text.literal("Scanner is not ready yet."))
            return 0
        }

        val committed = committedSnapshotOrSendError(source) ?: return 0

        return try {
            val path = MementoCsvWriter.writeOperatorWorldviewSnapshot(
                server = source.server,
                worldSnapshot = scanner.committedWorldSnapshot(),
                projectionSnapshot = committed,
            )
            source.sendFeedback(
                { Text.literal("[Memento] worldview CSV exported to $path").formatted(Formatting.YELLOW) },
                false,
            )
            1
        } catch (e: Exception) {
            MementoLog.error(MementoConcept.OPERATOR, "command=csv failed", e)
            source.sendError(Text.literal("[Memento] could not export csv (see server log)."))
            0
        }
    }

    fun doRenewal(source: ServerCommandSource): Int {
        return doRenewal(source, 1)
    }

    fun doRenewal(source: ServerCommandSource, count: Int): Int {
        if (count <= 0) {
            source.sendError(Text.literal("[Memento] renewal count must be > 0."))
            return 0
        }

        val snapshot = committedSnapshotOrSendError(source) ?: return 0
        val boundedRequested = count.coerceAtMost(DO_RENEWAL_MAX_REGION_BATCH)
        val requestedCandidates = snapshot.electionCandidates.take(boundedRequested)
        if (requestedCandidates.isEmpty()) {
            source.sendFeedback(
                { Text.literal("[Memento] no renewal candidates found.").formatted(Formatting.YELLOW) },
                false,
            )
            return 1
        }

        val orderedRegions = mutableListOf<Pair<RegistryKey<net.minecraft.world.World>, RegionKey>>()
        val orderedChunks = mutableListOf<ch.oliverlanz.memento.domain.worldmap.ChunkKey>()
        var invalidTargets = 0

        val regionRequestCountsByDimension = linkedMapOf<String, Int>()
        val chunkRequestCountsByDimension = linkedMapOf<String, Int>()

        requestedCandidates.forEach { candidate ->
            when (candidate.id.action) {
                RenewalCandidateAction.REGION_PRUNE -> {
                    val region = RegionKey(
                        worldId = candidate.id.worldKey,
                        regionX = candidate.id.regionX ?: run { invalidTargets++; return@forEach },
                        regionZ = candidate.id.regionZ ?: run { invalidTargets++; return@forEach },
                    )
                    val dimension = resolveWorldKey(region.worldId)
                    if (dimension == null) {
                        invalidTargets++
                        return@forEach
                    }
                    orderedRegions += dimension to region
                    regionRequestCountsByDimension[region.worldId] = (regionRequestCountsByDimension[region.worldId] ?: 0) + 1
                }

                RenewalCandidateAction.CHUNK_RENEW -> {
                    val key = candidate.id.toChunkKeyOrNull()
                    if (key == null) {
                        invalidTargets++
                        return@forEach
                    }
                    orderedChunks += key
                    val dim = key.world.value.toString()
                    chunkRequestCountsByDimension[dim] = (chunkRequestCountsByDimension[dim] ?: 0) + 1
                }
            }
        }

        val allActionDimensions = (regionRequestCountsByDimension.keys + chunkRequestCountsByDimension.keys).toSortedSet()
        allActionDimensions.forEach { dim ->
            MementoLog.info(
                MementoConcept.RENEWAL,
                "do renewal summary action=AGGREGATE dimension={} regionPrunes={} chunkRenews={} by={}",
                dim,
                regionRequestCountsByDimension[dim] ?: 0,
                chunkRequestCountsByDimension[dim] ?: 0,
                source.name,
            )
        }

        var regionPathResult: Int? = null
        if (orderedRegions.isNotEmpty()) {
            regionPathResult = when (val batch = WorldPruningService.submitBatch(orderedRegions)) {
                is WorldPruningService.BatchSubmitResult.Submitted -> {
                    source.sendFeedback(
                        {
                            Text.literal(
                                "[Memento] renewal batch submitted: requested=${batch.requested}, submitted=${batch.submitted}, pending outcomes after completion."
                            ).formatted(Formatting.YELLOW)
                        },
                        false,
                    )
                    if (invalidTargets > 0) {
                        source.sendFeedback(
                            { Text.literal("[Memento] ignored $invalidTargets invalid candidate target(s). ").formatted(Formatting.GRAY) },
                            false,
                        )
                    }
                    MementoLog.info(
                        MementoConcept.RENEWAL,
                        "do renewal action=REGION_PRUNE result=submitted requested={} submitted={} invalidTargets={} by={}",
                        batch.requested,
                        batch.submitted,
                        invalidTargets,
                        source.name,
                    )

                    orderedRegions.forEach { (_, region) ->
                        MementoLog.info(
                            MementoConcept.RENEWAL,
                            "do renewal action=REGION_PRUNE dimension={} region=({}, {}) result=submitted by={}",
                            region.worldId,
                            region.regionX,
                            region.regionZ,
                            source.name,
                        )
                    }
                    1
                }

                is WorldPruningService.BatchSubmitResult.Completed -> {
                    val succeeded = batch.completions.count {
                        it.category == WorldPruningService.OutcomeCategory.SUCCESS
                    }
                    val failed = batch.completions.size - succeeded
                    val partialStateCount = batch.completions.count {
                        it.category == WorldPruningService.OutcomeCategory.PARTIAL_STATE_REQUIRES_MANUAL_ACTION
                    }

                    source.sendFeedback(
                        {
                            Text.literal(
                                "[Memento] renewal batch outcome: requested=${batch.requested}, succeeded=$succeeded, failed=$failed, partialState=$partialStateCount (${batch.detail})."
                            ).formatted(
                                if (partialStateCount > 0 || failed > 0) Formatting.RED else Formatting.YELLOW,
                            )
                        },
                        false,
                    )

                    batch.completions
                        .filter { it.category == WorldPruningService.OutcomeCategory.PARTIAL_STATE_REQUIRES_MANUAL_ACTION }
                        .forEach { completion ->
                            source.sendFeedback(
                                {
                                    Text.literal(
                                        "[Memento] region ${completion.target.worldId} r(${completion.target.regionX},${completion.target.regionZ}) requires manual action guidance below."
                                    ).formatted(Formatting.RED)
                                },
                                false,
                            )
                            completion.operatorGuidance.forEach { line ->
                                source.sendFeedback({ Text.literal(line).formatted(Formatting.RED) }, false)
                            }
                        }

                    MementoLog.info(
                        MementoConcept.RENEWAL,
                        "do renewal action=REGION_PRUNE result=completed requested={} succeeded={} failed={} partialState={} detail={} invalidTargets={} by={}",
                        batch.requested,
                        succeeded,
                        failed,
                        partialStateCount,
                        batch.detail,
                        invalidTargets,
                        source.name,
                    )

                    batch.completions.forEach { completion ->
                        MementoLog.info(
                            MementoConcept.RENEWAL,
                            "do renewal action=REGION_PRUNE dimension={} region=({}, {}) result={} category={} by={}",
                            completion.target.worldId,
                            completion.target.regionX,
                            completion.target.regionZ,
                            completion.outcome.name,
                            completion.category.name,
                            source.name,
                        )
                    }
                    if (failed == 0) 1 else 0
                }
            }

            if (regionPathResult == 0) {
                return 0
            }
            if (orderedChunks.isEmpty()) {
                return regionPathResult
            }
        }

        val chunkGroups = orderedChunks.groupBy { it.world }.size
        val submittedChunks = submitChunkBatchNow(source.name, orderedChunks)
        source.sendFeedback(
            {
                Text.literal(
                    "[Memento] renewal chunk batch submitted: requested=${requestedCandidates.size}, submitted=$submittedChunks, groups=$chunkGroups."
                ).formatted(Formatting.YELLOW)
            },
            false,
        )
        OperatorMessages.info(
            source.server,
            "renewal chunk batch submitted: requested=${requestedCandidates.size}, submitted=$submittedChunks, groups=$chunkGroups.",
        )
        if (submittedChunks > 0) {
            OperatorMessages.info(
                source.server,
                "renewal chunk execution is asynchronous; completion is reported by renewal lifecycle events.",
            )
        }
        if (invalidTargets > 0) {
            source.sendFeedback(
                { Text.literal("[Memento] ignored $invalidTargets invalid candidate target(s). ").formatted(Formatting.GRAY) },
                false,
            )
        }
        MementoLog.info(
            MementoConcept.RENEWAL,
            "do renewal action=CHUNK_RENEW result=submitted requested={} submitted={} invalidTargets={} by={}",
            requestedCandidates.size,
            submittedChunks,
            invalidTargets,
            source.name,
        )

        orderedChunks.forEach { key ->
            MementoLog.info(
                MementoConcept.RENEWAL,
                "do renewal action=CHUNK_RENEW dimension={} chunk=({}, {}) result=submitted by={}",
                key.world.value.toString(),
                key.chunkX,
                key.chunkZ,
                source.name,
            )
        }

        return if (submittedChunks > 0) 1 else 0
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

    private fun formatExplainSummary(source: ServerCommandSource): List<String> {
        val lines = mutableListOf("Memento explain")

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
                "Scheduler: scanning the world$durationText."
            }

            projectionStatus?.runningDurationMs != null -> {
                "Scheduler: evaluating renewal options for ${formatDuration(projectionStatus.runningDurationMs)}."
            }

            projectionStatus?.blockedOnGate == true -> {
                "Scheduler: waiting for the world scan to finish."
            }

            projectionStatus?.lastCompletedReason == "SCAN_COMPLETED" && projectionStatus.lastCompletedDurationMs != null -> {
                "Scheduler: renewal analysis completed after ${formatDuration(projectionStatus.lastCompletedDurationMs)}."
            }

            scannerStatus?.lastCompletedDurationMs != null -> {
                "Scheduler: world scan completed after ${formatDuration(scannerStatus.lastCompletedDurationMs)}."
            }

            else -> "Scheduler: idle."
        }
        lines += schedulerLine

        if (scannerStatus != null && scannerStatus.worldMapTotal > 0) {
            lines += "World memory: ${scannerStatus.worldMapScanned}/${scannerStatus.worldMapTotal} chunks confirmed, ${scannerStatus.worldMapMissing} still uncertain"
        }

        if (projectionStatus != null) {
            lines += "Renewal target: evaluated on demand"
        }

        return lines
    }

    private fun formatExplainWorld(source: ServerCommandSource): List<String> {
        val lines = mutableListOf("World explanation")
        val scannerStatus = worldScanner?.statusView()
        val projection = renewalProjection
        val projectionStatus = projection?.statusView()
        val snapshot = projection?.committedSnapshotOrNull()

        if (scannerStatus == null) {
            lines += "World knowledge completeness: scanner unavailable"
        } else {
            val total = scannerStatus.worldMapTotal
            val scanned = scannerStatus.worldMapScanned
            val missing = scannerStatus.worldMapMissing
            lines += if (total > 0) {
                "World knowledge completeness: $scanned/$total known, $missing uncertain"
            } else {
                "World knowledge completeness: no world facts collected yet"
            }
        }

        val candidates = snapshot?.electionCandidates.orEmpty()
        val regionCandidates = candidates.count { it.id.action == RenewalCandidateAction.REGION_PRUNE }
        val chunkCandidates = candidates.count { it.id.action == RenewalCandidateAction.CHUNK_RENEW }
        lines += "Renewal candidate totals: all=${candidates.size}, regions=$regionCandidates, chunks=$chunkCandidates"
        lines += "Mechanism activity: natural=${if (regionCandidates > 0) "ready" else "none"}, stone-driven=${if (chunkCandidates > 0) "ready" else "none"}"

        val health = when {
            projection == null -> "projection unavailable"
            projection.hasPendingChanges() -> "projection recomputation pending"
            projectionStatus?.blockedOnGate == true -> "projection waiting for async gate"
            projectionStatus?.runningDurationMs != null -> "projection recomputation running"
            else -> "projection stable"
        }
        lines += "Projection health: $health"

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
            MementoLog.info(MementoConcept.OPERATOR, "do renewal rejected reason=initial_scan_not_completed by={}", source.name)
            source.sendError(Text.literal("[Memento] renewal action is unavailable before first full world scan completion."))
            return null
        }

        val projection = renewalProjection
        if (projection == null) {
            MementoLog.info(MementoConcept.OPERATOR, "do renewal rejected reason=projection_not_ready by={}", source.name)
            source.sendError(Text.literal("Renewal projection is not ready yet."))
            return null
        }

        val committed = projection.committedSnapshotOrNull()
        if (committed == null) {
            MementoLog.info(MementoConcept.OPERATOR, "do renewal rejected reason=projection_snapshot_unavailable by={}", source.name)
            source.sendError(Text.literal("[Memento] renewal analysis snapshot is unavailable."))
            return null
        }

        return committed
    }

    private fun forceRegionRenew(source: ServerCommandSource, decision: RegionKey): Int {
        MementoLog.info(
            MementoConcept.PRUNING,
            "do renewal request grain=region world={} region=({}, {}) by={}",
            decision.worldId,
            decision.regionX,
            decision.regionZ,
            source.name,
        )

        val dimension = resolveWorldKey(decision.worldId)
        if (dimension == null) {
            MementoLog.info(
                MementoConcept.PRUNING,
                "do renewal rejected reason=invalid_decision_world world={} by={}",
                decision.worldId,
                source.name,
            )
            source.sendError(Text.literal("[Memento] projection returned an invalid world id for renewal action."))
            return 0
        }

        return when (val result = WorldPruningService.submit(dimension, decision)) {
            is WorldPruningService.SubmitResult.Submitted -> {
                source.sendFeedback(
                    {
                        Text.literal(
                            "[Memento] renewal action submitted for region ${result.target.worldId} r(${result.target.regionX},${result.target.regionZ})."
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
                            "[Memento] renewal action outcome=${c.outcome.name} class=${c.category.name} world=${c.target.worldId} r(${c.target.regionX},${c.target.regionZ})"
                        ).formatted(if (c.category == WorldPruningService.OutcomeCategory.SUCCESS) Formatting.YELLOW else Formatting.RED)
                    },
                    false
                )
                if (c.category == WorldPruningService.OutcomeCategory.PARTIAL_STATE_REQUIRES_MANUAL_ACTION) {
                    c.operatorGuidance.forEach { line ->
                        source.sendFeedback({ Text.literal(line).formatted(Formatting.RED) }, false)
                    }
                }
                if (c.category == WorldPruningService.OutcomeCategory.SUCCESS) 1 else 0
            }
        }
    }

    private fun resolveWorldKey(worldId: String): RegistryKey<net.minecraft.world.World>? {
        return runCatching {
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of(worldId))
        }.getOrNull()
    }

    private fun forceChunkBatchRenew(source: ServerCommandSource, chunkKeys: List<ch.oliverlanz.memento.domain.worldmap.ChunkKey>): Int {
        val submission = submitChunkBatchDefinitions(chunkKeys)
        if (submission.groupCount == 0) {
            source.sendError(Text.literal("[Memento] no chunks available for renewal action."))
            return 0
        }

        MementoLog.info(
            MementoConcept.RENEWAL,
            "do renewal decision grain=chunk count={} groups={} by={}",
            submission.submittedCount,
            submission.groupCount,
            source.name,
        )
        source.sendFeedback(
            { Text.literal("[Memento] renewal action queued ${submission.submittedCount} chunks across ${submission.groupCount} renewal group(s). ").formatted(Formatting.YELLOW) },
            false
        )
        return 1
    }
    private fun submitChunkBatchNow(owner: String, chunkKeys: List<ch.oliverlanz.memento.domain.worldmap.ChunkKey>): Int {
        val submission = submitChunkBatchDefinitions(chunkKeys)
        if (submission.groupCount == 0) return 0

        MementoLog.info(
            MementoConcept.RENEWAL,
            "do renewal decision grain=chunk count={} groups={} by={}",
            submission.submittedCount,
            submission.groupCount,
            owner,
        )
        return submission.submittedCount
    }

    private data class ChunkBatchSubmission(
        val submittedCount: Int,
        val groupCount: Int,
    )

    private fun submitChunkBatchDefinitions(
        chunkKeys: List<ch.oliverlanz.memento.domain.worldmap.ChunkKey>,
    ): ChunkBatchSubmission {
        val chunksByWorld = chunkKeys.groupBy { it.world }
        if (chunksByWorld.isEmpty()) {
            return ChunkBatchSubmission(submittedCount = 0, groupCount = 0)
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

        return ChunkBatchSubmission(
            submittedCount = submitted,
            groupCount = chunksByWorld.size,
        )
    }

    private fun formatCandidateForExplain(candidate: RenewalRankedCandidate): String {
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
