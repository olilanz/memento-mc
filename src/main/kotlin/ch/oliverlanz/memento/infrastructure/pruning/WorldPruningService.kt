package ch.oliverlanz.memento.infrastructure.pruning

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.renewal.projection.RegionKey
import ch.oliverlanz.memento.infrastructure.async.GlobalAsyncExclusionGate
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import ch.oliverlanz.memento.infrastructure.observability.OperatorMessages
import ch.oliverlanz.memento.infrastructure.worldscan.WorldScanner
import ch.oliverlanz.memento.infrastructure.worldstorage.WorldStorageService
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Future
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.WorldSavePath
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkStatus

/**
 * Region-prune executor for operator-triggered `/memento do renew [N]` region candidates.
 *
 * Design lock:
 * - exactly one in-memory operation state,
 * - no persistence,
 * - no queue and no retry loop,
 * - submit-time bounded batching for region prune targets,
 * - tick thread decides and emits outcomes,
 * - background thread performs filesystem operations only.
 */
object WorldPruningService {
    private const val MAX_BATCH_TARGETS: Int = 10

    enum class OutcomeCategory {
        SUCCESS,
        SKIPPED_ERROR_RECOVERED,
        PARTIAL_STATE_REQUIRES_MANUAL_ACTION,
    }

    enum class OperationState {
        IDLE,
        SUBMITTED,
        COMPLETED_SUCCESS,
        COMPLETED_FAILED,
    }

    enum class Outcome {
        success,
        rejected_loaded,
        rejected_busy,
        rejected_dimension_mismatch,
        preflight_failed,
        aborted_due_to_partial_state,
        rename_failed,
        partial_rollback_failed,
        pruned_with_residual_backup,
    }

    data class Completion(
        val target: RegionKey,
        val category: OutcomeCategory,
        val outcome: Outcome,
        val detail: String? = null,
        val operatorGuidance: List<String> = emptyList(),
    )

    private sealed interface PreflightResult {
        data class Ready(val toRename: List<RenameMember>) : PreflightResult
        data class Failed(val completion: Completion) : PreflightResult
    }

    data class BatchCompletion(
        val requested: Int,
        val submitted: Int,
        val succeeded: Int,
        val failed: Int,
        val completions: List<Completion>,
    )

    sealed interface SubmitResult {
        data class Submitted(val target: RegionKey) : SubmitResult
        data class Completed(val completion: Completion) : SubmitResult
    }

    sealed interface BatchSubmitResult {
        data class Submitted(
            val requested: Int,
            val submitted: Int,
            val acceptedTargets: List<RegionKey>,
        ) : BatchSubmitResult

        data class Completed(
            val requested: Int,
            val completions: List<Completion>,
            val detail: String,
        ) : BatchSubmitResult
    }

    private data class SubmittedOperation(
        val target: RegionKey,
        val dimension: RegistryKey<World>,
        val regionX: Int,
        val regionZ: Int,
        val future: Future<Completion>,
    )

    private data class SubmittedBatchOperation(
        val requested: Int,
        val submitted: Int,
        val acceptedTargets: List<Pair<RegistryKey<World>, RegionKey>>,
        val future: Future<BatchCompletion>,
    )

    private data class RenameMember(
        val label: String,
        val livePath: Path,
        val backupPath: Path,
    )

    @Volatile
    private var state: OperationState = OperationState.IDLE

    @Volatile
    private var submitted: SubmittedOperation? = null

    @Volatile
    private var submittedBatch: SubmittedBatchOperation? = null

    @Volatile
    private var lastCompletion: Completion? = null

    @Volatile
    private var lastBatchCompletion: BatchCompletion? = null

    @Volatile
    private var server: MinecraftServer? = null

    @Volatile
    private var scanner: WorldScanner? = null

    fun attach(server: MinecraftServer, scanner: WorldScanner) {
        this.server = server
        this.scanner = scanner
        state = OperationState.IDLE
        submitted = null
        submittedBatch = null
        lastCompletion = null
        lastBatchCompletion = null
    }

    fun detach() {
        server = null
        scanner = null
        state = OperationState.IDLE
        submitted = null
        submittedBatch = null
        lastCompletion = null
        lastBatchCompletion = null
    }

    fun statusView(): Pair<OperationState, Completion?> = state to lastCompletion

    /**
     * Hot-path guard for command-layer force queueing.
     *
     * true => no prune operation is currently in-flight.
     */
    fun isIdle(): Boolean = state != OperationState.SUBMITTED

    /** Latest completed prune outcome (if any). */
    fun lastCompletionOrNull(): Completion? = lastCompletion

    /** Latest completed batch prune outcome (if any). */
    fun lastBatchCompletionOrNull(): BatchCompletion? = lastBatchCompletion

    fun submit(dimension: RegistryKey<World>, region: RegionKey): SubmitResult {
        val srv = server
        if (srv == null) {
            val completion = Completion(region, OutcomeCategory.SKIPPED_ERROR_RECOVERED, Outcome.rename_failed, "server detached")
            state = OperationState.COMPLETED_FAILED
            lastCompletion = completion
            return SubmitResult.Completed(completion)
        }

        if (state == OperationState.SUBMITTED) {
            // Busy is an operator-visible command outcome only. It must not corrupt the in-flight
            // operation truth by transitioning state or overwriting completion records.
            val completion = Completion(region, OutcomeCategory.SKIPPED_ERROR_RECOVERED, Outcome.rejected_busy, "operation already submitted")
            return SubmitResult.Completed(completion)
        }

        if (dimension.value.toString() != region.worldId) {
            val completion = Completion(
                region,
                OutcomeCategory.SKIPPED_ERROR_RECOVERED,
                Outcome.rejected_dimension_mismatch,
                "requestedDimension=${dimension.value} decisionWorld=${region.worldId}",
            )
            state = OperationState.COMPLETED_FAILED
            lastCompletion = completion
            return SubmitResult.Completed(completion)
        }

        val world = srv.getWorld(dimension)
        if (world == null) {
            val completion = Completion(region, OutcomeCategory.SKIPPED_ERROR_RECOVERED, Outcome.rename_failed, "unknown world='${dimension.value}'")
            state = OperationState.COMPLETED_FAILED
            lastCompletion = completion
            return SubmitResult.Completed(completion)
        }

        if (hasAnyLoadedChunk(world, region.regionX, region.regionZ)) {
            val completion = Completion(region, OutcomeCategory.SKIPPED_ERROR_RECOVERED, Outcome.rejected_loaded)
            state = OperationState.COMPLETED_FAILED
            lastCompletion = completion
            return SubmitResult.Completed(completion)
        }

        val root = srv.getSavePath(WorldSavePath.ROOT)
        val submit = GlobalAsyncExclusionGate.submitIfIdle(
            concept = MementoConcept.PRUNING,
            owner = "world-pruning",
        ) {
            java.util.concurrent.Callable {
                pruneRegionTriple(root, dimension, region)
            }
        }

        return when (submit) {
            is GlobalAsyncExclusionGate.SubmitResult.Busy -> {
                val completion = Completion(region, OutcomeCategory.SKIPPED_ERROR_RECOVERED, Outcome.rejected_busy, "activeOwner=${submit.activeOwner}")
                SubmitResult.Completed(completion)
            }

            is GlobalAsyncExclusionGate.SubmitResult.Accepted -> {
                state = OperationState.SUBMITTED
                lastCompletion = null
                submitted = SubmittedOperation(
                    target = region,
                    dimension = dimension,
                    regionX = region.regionX,
                    regionZ = region.regionZ,
                    future = submit.future,
                )
                SubmitResult.Submitted(region)
            }
        }
    }

    fun submitBatch(targets: List<Pair<RegistryKey<World>, RegionKey>>): BatchSubmitResult {
        val requested = targets.size
        if (requested == 0) {
            return BatchSubmitResult.Completed(
                requested = 0,
                completions = emptyList(),
                detail = "empty_request",
            )
        }

        if (state == OperationState.SUBMITTED) {
            return BatchSubmitResult.Completed(
                requested = requested,
                completions = targets.map { (_, region) ->
                    Completion(region, OutcomeCategory.SKIPPED_ERROR_RECOVERED, Outcome.rejected_busy, "operation already submitted")
                },
                detail = "busy",
            )
        }

        val boundedTargets = targets.take(MAX_BATCH_TARGETS)
        val srv = server
        if (srv == null) {
            return BatchSubmitResult.Completed(
                requested = requested,
                completions = boundedTargets.map { (_, region) ->
                    Completion(region, OutcomeCategory.SKIPPED_ERROR_RECOVERED, Outcome.rename_failed, "server detached")
                },
                detail = "server_detached",
            )
        }

        val submit = GlobalAsyncExclusionGate.submitIfIdle(
            concept = MementoConcept.PRUNING,
            owner = "world-pruning",
        ) {
            java.util.concurrent.Callable {
                pruneBatch(root = srv.getSavePath(WorldSavePath.ROOT), targets = boundedTargets)
            }
        }

        return when (submit) {
            is GlobalAsyncExclusionGate.SubmitResult.Busy -> {
                BatchSubmitResult.Completed(
                    requested = requested,
                    completions = boundedTargets.map { (_, region) ->
                        Completion(region, OutcomeCategory.SKIPPED_ERROR_RECOVERED, Outcome.rejected_busy, "activeOwner=${submit.activeOwner}")
                    },
                    detail = "busy",
                )
            }

            is GlobalAsyncExclusionGate.SubmitResult.Accepted -> {
                state = OperationState.SUBMITTED
                lastCompletion = null
                lastBatchCompletion = null
                submittedBatch = SubmittedBatchOperation(
                    requested = requested,
                    submitted = boundedTargets.size,
                    acceptedTargets = boundedTargets,
                    future = submit.future,
                )
                BatchSubmitResult.Submitted(
                    requested = requested,
                    submitted = boundedTargets.size,
                    acceptedTargets = boundedTargets.map { it.second },
                )
            }
        }
    }

    fun tickThreadProcess() {
        val batch = submittedBatch
        if (batch != null) {
            if (!batch.future.isDone) return

            submittedBatch = null
            submitted = null

            val result = try {
                batch.future.get()
            } catch (t: Throwable) {
                BatchCompletion(
                    requested = batch.requested,
                    submitted = batch.submitted,
                    succeeded = 0,
                    failed = batch.submitted,
                    completions = batch.acceptedTargets.map { (_, region) ->
                        Completion(region, OutcomeCategory.SKIPPED_ERROR_RECOVERED, Outcome.rename_failed, t.message)
                    },
                )
            }

            val success = result.failed == 0
            state = if (success) OperationState.COMPLETED_SUCCESS else OperationState.COMPLETED_FAILED
            lastBatchCompletion = result
            lastCompletion = result.completions.lastOrNull()

            MementoLog.info(
                MementoConcept.PRUNING,
                "world pruning batch result requested={} submitted={} succeeded={} failed={}",
                result.requested,
                result.submitted,
                result.succeeded,
                result.failed,
            )

            if (result.failed == 0) {
                OperatorMessages.info(
                    server,
                    "renewal batch completed: submitted=${result.submitted}, succeeded=${result.succeeded}, failed=${result.failed}.",
                )
            } else {
                OperatorMessages.warn(
                    server,
                    "renewal batch completed with skipped regions: submitted=${result.submitted}, succeeded=${result.succeeded}, failed=${result.failed}.",
                )
            }

            result.completions
                .filter { it.category != OutcomeCategory.SUCCESS }
                .forEach { completion ->
                    val summary =
                        "renewal region outcome class=${completion.category.name} outcome=${completion.outcome.name} world=${completion.target.worldId} r(${completion.target.regionX},${completion.target.regionZ}) detail=${completion.detail ?: "none"}"
                    when (completion.category) {
                        OutcomeCategory.PARTIAL_STATE_REQUIRES_MANUAL_ACTION -> OperatorMessages.warn(server, summary)
                        OutcomeCategory.SKIPPED_ERROR_RECOVERED -> OperatorMessages.info(server, summary)
                        OutcomeCategory.SUCCESS -> Unit
                    }

                    if (completion.category == OutcomeCategory.PARTIAL_STATE_REQUIRES_MANUAL_ACTION) {
                        completion.operatorGuidance.forEach { line ->
                            OperatorMessages.warn(server, line.removePrefix("[Memento] ").trim())
                        }
                    }
                }

            val scanTick = server?.overworld?.time ?: 0L
            result.completions
                .asSequence()
                .filter { completion ->
                    completion.outcome == Outcome.success || completion.outcome == Outcome.pruned_with_residual_backup
                }
                .forEach { completion ->
                    val dimension = runCatching {
                        RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, net.minecraft.util.Identifier.of(completion.target.worldId))
                    }.getOrNull() ?: return@forEach

                    scanner?.startRegionRescan(
                        world = dimension,
                        regionX = completion.target.regionX,
                        regionZ = completion.target.regionZ,
                        reason = "renew_force_prune",
                        scanTick = scanTick,
                    )
                }

            return
        }

        val op = submitted ?: return
        if (!op.future.isDone) return

        submitted = null

        val completion = try {
            op.future.get()
        } catch (t: Throwable) {
            Completion(op.target, OutcomeCategory.SKIPPED_ERROR_RECOVERED, Outcome.rename_failed, t.message)
        }

        val success = completion.category == OutcomeCategory.SUCCESS
        state = if (success) OperationState.COMPLETED_SUCCESS else OperationState.COMPLETED_FAILED
        lastCompletion = completion

        MementoLog.info(
            MementoConcept.PRUNING,
            "world pruning result world={} region=({}, {}) outcome={} detail={}",
            completion.target.worldId,
            completion.target.regionX,
            completion.target.regionZ,
            completion.outcome.name,
            completion.detail,
        )

        if (completion.category == OutcomeCategory.SUCCESS) {
            OperatorMessages.info(
                server,
                "renewal region completed world=${completion.target.worldId} r(${completion.target.regionX},${completion.target.regionZ}) outcome=${completion.outcome.name}.",
            )
        } else {
            val summary =
                "renewal region outcome class=${completion.category.name} outcome=${completion.outcome.name} world=${completion.target.worldId} r(${completion.target.regionX},${completion.target.regionZ}) detail=${completion.detail ?: "none"}"
            when (completion.category) {
                OutcomeCategory.PARTIAL_STATE_REQUIRES_MANUAL_ACTION -> OperatorMessages.warn(server, summary)
                OutcomeCategory.SKIPPED_ERROR_RECOVERED -> OperatorMessages.info(server, summary)
                OutcomeCategory.SUCCESS -> Unit
            }
            if (completion.category == OutcomeCategory.PARTIAL_STATE_REQUIRES_MANUAL_ACTION) {
                completion.operatorGuidance.forEach { line ->
                    OperatorMessages.warn(server, line.removePrefix("[Memento] ").trim())
                }
            }
        }

        if (success) {
            val scanTick = server?.overworld?.time ?: 0L
            scanner?.startRegionRescan(
                world = op.dimension,
                regionX = op.regionX,
                regionZ = op.regionZ,
                reason = "renew_force_prune",
                scanTick = scanTick,
            )
        }
    }

    private fun pruneBatch(
        root: Path,
        targets: List<Pair<RegistryKey<World>, RegionKey>>,
    ): BatchCompletion {
        val completions = mutableListOf<Completion>()
        var succeeded = 0
        var failed = 0

        targets.forEach { (dimension, region) ->
            val completion = pruneRegionTriple(root, dimension, region)
            completions += completion
            val success = completion.category == OutcomeCategory.SUCCESS
            if (success) succeeded++ else failed++

            if (completion.category == OutcomeCategory.PARTIAL_STATE_REQUIRES_MANUAL_ACTION) {
                val remaining = targets.drop(completions.size)
                remaining.forEach { (_, remainingRegion) ->
                    completions += Completion(
                        target = remainingRegion,
                        category = OutcomeCategory.SKIPPED_ERROR_RECOVERED,
                        outcome = Outcome.aborted_due_to_partial_state,
                        detail =
                            "not attempted because previous region entered partial state requiring manual action",
                    )
                    failed++
                }
                return BatchCompletion(
                    requested = targets.size,
                    submitted = completions.size,
                    succeeded = succeeded,
                    failed = failed,
                    completions = completions,
                )
            }
        }

        return BatchCompletion(
            requested = targets.size,
            submitted = targets.size,
            succeeded = succeeded,
            failed = failed,
            completions = completions,
        )
    }

    private fun pruneRegionTriple(
        root: Path,
        dimension: RegistryKey<World>,
        target: RegionKey,
    ): Completion {
        val triple = WorldStorageService.resolveRegionTriple(root, dimension, target.regionX, target.regionZ)
        val candidates = listOf(
            RenameMember(
                label = "region",
                livePath = triple.region,
                backupPath =
                    triple.region.resolveSibling(
                        triple.region.fileName.toString() + MementoConstants.MEMENTO_RENEW_FORCE_BACKUP_SUFFIX
                    ),
            ),
            RenameMember(
                label = "entities",
                livePath = triple.entities,
                backupPath =
                    triple.entities.resolveSibling(
                        triple.entities.fileName.toString() + MementoConstants.MEMENTO_RENEW_FORCE_BACKUP_SUFFIX
                    ),
            ),
            RenameMember(
                label = "poi",
                livePath = triple.poi,
                backupPath =
                    triple.poi.resolveSibling(
                        triple.poi.fileName.toString() + MementoConstants.MEMENTO_RENEW_FORCE_BACKUP_SUFFIX
                    ),
            ),
        )

        val preflight = preflightRegionMembers(target = target, members = candidates)
        val toRename = when (preflight) {
            is PreflightResult.Failed -> return preflight.completion
            is PreflightResult.Ready -> preflight.toRename
        }

        for (member in toRename) {
            if (!Files.exists(member.backupPath)) continue
            try {
                Files.delete(member.backupPath)
            } catch (t: Throwable) {
                return Completion(
                    target,
                    OutcomeCategory.SKIPPED_ERROR_RECOVERED,
                    Outcome.rename_failed,
                    "backup not removable member=${member.label} path=${member.backupPath} error=${t.message}",
                )
            }
        }

        val renamed = mutableListOf<RenameMember>()
        for (member in toRename) {
            try {
                Files.move(member.livePath, member.backupPath, StandardCopyOption.ATOMIC_MOVE)
                renamed += member
            } catch (t: Throwable) {
                val rollbackFailures = rollbackRenames(renamed.asReversed())
                return if (rollbackFailures.isEmpty()) {
                    Completion(
                        target,
                        OutcomeCategory.SKIPPED_ERROR_RECOVERED,
                        Outcome.rename_failed,
                        "atomic triple rename failed member=${member.label} path=${member.livePath} error=${t.message}",
                    )
                } else {
                    val guidance = buildPartialStateOperatorGuidance(target, candidates)
                    Completion(
                        target,
                        OutcomeCategory.PARTIAL_STATE_REQUIRES_MANUAL_ACTION,
                        Outcome.partial_rollback_failed,
                        "atomic triple rename failed member=${member.label}; rollback failures=${rollbackFailures.joinToString(";")}",
                        operatorGuidance = guidance,
                    )
                }
            }
        }

        val residualBackups = mutableListOf<Path>()
        for (member in renamed) {
            try {
                Files.deleteIfExists(member.backupPath)
            } catch (t: Throwable) {
                MementoLog.warn(
                    MementoConcept.PRUNING,
                    "world pruning backup delete failed member={} path={} error={}",
                    member.label,
                    member.backupPath,
                    t.message,
                )
                residualBackups.add(member.backupPath)
                continue
            }
            if (Files.exists(member.backupPath)) {
                residualBackups.add(member.backupPath)
            }
        }

        return if (residualBackups.isEmpty()) {
            Completion(target, OutcomeCategory.SUCCESS, Outcome.success)
        } else {
            Completion(
                target,
                OutcomeCategory.SUCCESS,
                Outcome.pruned_with_residual_backup,
                "backup retained paths=${residualBackups.joinToString(",")}",
            )
        }
    }

    private fun preflightRegionMembers(
        target: RegionKey,
        members: List<RenameMember>,
    ): PreflightResult {
        val toRename = members.filter { Files.exists(it.livePath) }
        // Missing region members are valid and deterministic: non-existing files do not require
        // deletion or rollback. Preflight validates only members that currently exist.

        for (member in toRename) {
            if (!member.livePath.fileSystem.equals(member.backupPath.fileSystem)) {
                return PreflightResult.Failed(
                    Completion(
                        target,
                        OutcomeCategory.SKIPPED_ERROR_RECOVERED,
                        Outcome.preflight_failed,
                        "preflight failed: atomic move requires same filesystem member=${member.label}",
                    ),
                )
            }

            val liveParent = member.livePath.parent
            val backupParent = member.backupPath.parent
            if (liveParent == null || backupParent == null) {
                return PreflightResult.Failed(
                    Completion(
                        target,
                        OutcomeCategory.SKIPPED_ERROR_RECOVERED,
                        Outcome.preflight_failed,
                        "preflight failed: missing parent path member=${member.label}",
                    ),
                )
            }

            if (!Files.isWritable(liveParent)) {
                return PreflightResult.Failed(
                    Completion(
                        target,
                        OutcomeCategory.SKIPPED_ERROR_RECOVERED,
                        Outcome.preflight_failed,
                        "preflight failed: live parent not writable member=${member.label} path=$liveParent",
                    ),
                )
            }

            if (!Files.isWritable(backupParent)) {
                return PreflightResult.Failed(
                    Completion(
                        target,
                        OutcomeCategory.SKIPPED_ERROR_RECOVERED,
                        Outcome.preflight_failed,
                        "preflight failed: backup parent not writable member=${member.label} path=$backupParent",
                    ),
                )
            }

            if (!Files.isWritable(member.livePath)) {
                return PreflightResult.Failed(
                    Completion(
                        target,
                        OutcomeCategory.SKIPPED_ERROR_RECOVERED,
                        Outcome.preflight_failed,
                        "preflight failed: live file not writable member=${member.label} path=${member.livePath}",
                    ),
                )
            }

            if (Files.exists(member.backupPath) && !Files.isWritable(member.backupPath)) {
                return PreflightResult.Failed(
                    Completion(
                        target,
                        OutcomeCategory.SKIPPED_ERROR_RECOVERED,
                        Outcome.preflight_failed,
                        "preflight failed: backup file not writable/removable member=${member.label} path=${member.backupPath}",
                    ),
                )
            }
        }

        return PreflightResult.Ready(toRename)
    }

    private fun buildPartialStateOperatorGuidance(target: RegionKey, members: List<RenameMember>): List<String> {
        val sorted = members.sortedBy { it.label }
        val memberLines = sorted.map { member ->
            "  ${member.label}: live=${member.livePath} backup=${member.backupPath}"
        }
        return buildList {
            add("[Memento] region ${target.worldId} r(${target.regionX},${target.regionZ}) entered partial file state while pruning.")
            add("[Memento] If you later observe server issues for this region, use one of these calm recovery options:")
            add("[Memento] 1) restore from backup files, or")
            add("[Memento] 2) remove the listed files while server is down.")
            add("[Memento] affected files:")
            addAll(memberLines)
        }
    }

    private fun rollbackRenames(renamed: List<RenameMember>): List<String> {
        if (renamed.isEmpty()) return emptyList()

        val failures = mutableListOf<String>()
        for (member in renamed) {
            try {
                Files.move(member.backupPath, member.livePath, StandardCopyOption.ATOMIC_MOVE)
            } catch (t: Throwable) {
                failures += "member=${member.label} backup=${member.backupPath} live=${member.livePath} error=${t.message}"
            }
        }
        return failures
    }

    private fun hasAnyLoadedChunk(world: ServerWorld, regionX: Int, regionZ: Int): Boolean {
        // Intentionally O(32*32): this executes only on manual operator commands and remains
        // bounded, deterministic, and observable.
        val baseX = regionX * 32
        val baseZ = regionZ * 32
        for (dx in 0 until 32) {
            val chunkX = baseX + dx
            for (dz in 0 until 32) {
                val chunkZ = baseZ + dz
                val loaded = world.chunkManager.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false)
                if (loaded != null) return true
            }
        }
        return false
    }
}
