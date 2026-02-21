package ch.oliverlanz.memento.infrastructure.pruning

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.renewal.projection.RegionKey
import ch.oliverlanz.memento.infrastructure.async.GlobalAsyncExclusionGate
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
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
 * Single-operation region prune executor for `/memento renew force`.
 *
 * Design lock:
 * - exactly one in-memory operation state,
 * - no persistence,
 * - no queue and no retry loop,
 * - tick thread decides and emits outcomes,
 * - background thread performs filesystem operations only.
 */
object WorldPruningService {

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
        rename_failed,
        partial_rollback_failed,
        pruned_with_residual_backup,
    }

    data class Completion(
        val target: RegionKey,
        val outcome: Outcome,
        val detail: String? = null,
    )

    sealed interface SubmitResult {
        data class Submitted(val target: RegionKey) : SubmitResult
        data class Completed(val completion: Completion) : SubmitResult
    }

    private data class SubmittedOperation(
        val target: RegionKey,
        val dimension: RegistryKey<World>,
        val regionX: Int,
        val regionZ: Int,
        val future: Future<Completion>,
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
    private var lastCompletion: Completion? = null

    @Volatile
    private var server: MinecraftServer? = null

    @Volatile
    private var scanner: WorldScanner? = null

    fun attach(server: MinecraftServer, scanner: WorldScanner) {
        this.server = server
        this.scanner = scanner
        state = OperationState.IDLE
        submitted = null
        lastCompletion = null
    }

    fun detach() {
        server = null
        scanner = null
        state = OperationState.IDLE
        submitted = null
        lastCompletion = null
    }

    fun statusView(): Pair<OperationState, Completion?> = state to lastCompletion

    fun submit(dimension: RegistryKey<World>, region: RegionKey): SubmitResult {
        val srv = server
        if (srv == null) {
            val completion = Completion(region, Outcome.rename_failed, "server detached")
            state = OperationState.COMPLETED_FAILED
            lastCompletion = completion
            return SubmitResult.Completed(completion)
        }

        if (state == OperationState.SUBMITTED) {
            // Busy is an operator-visible command outcome only. It must not corrupt the in-flight
            // operation truth by transitioning state or overwriting completion records.
            val completion = Completion(region, Outcome.rejected_busy, "operation already submitted")
            return SubmitResult.Completed(completion)
        }

        if (dimension.value.toString() != region.worldId) {
            val completion = Completion(
                region,
                Outcome.rejected_dimension_mismatch,
                "requestedDimension=${dimension.value} decisionWorld=${region.worldId}",
            )
            state = OperationState.COMPLETED_FAILED
            lastCompletion = completion
            return SubmitResult.Completed(completion)
        }

        val world = srv.getWorld(dimension)
        if (world == null) {
            val completion = Completion(region, Outcome.rename_failed, "unknown world='${dimension.value}'")
            state = OperationState.COMPLETED_FAILED
            lastCompletion = completion
            return SubmitResult.Completed(completion)
        }

        if (hasAnyLoadedChunk(world, region.regionX, region.regionZ)) {
            val completion = Completion(region, Outcome.rejected_loaded)
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
                val completion = Completion(region, Outcome.rejected_busy, "activeOwner=${submit.activeOwner}")
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

    fun tickThreadProcess() {
        val op = submitted ?: return
        if (!op.future.isDone) return

        submitted = null

        val completion = try {
            op.future.get()
        } catch (t: Throwable) {
            Completion(op.target, Outcome.rename_failed, t.message)
        }

        val success = completion.outcome == Outcome.success || completion.outcome == Outcome.pruned_with_residual_backup
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

        val toRename = candidates.filter { Files.exists(it.livePath) }
        if (toRename.isEmpty()) {
            return Completion(target, Outcome.rename_failed, "region triple missing for world=${target.worldId} region=(${target.regionX},${target.regionZ})")
        }

        for (member in toRename) {
            if (!Files.exists(member.backupPath)) continue
            try {
                Files.delete(member.backupPath)
            } catch (t: Throwable) {
                return Completion(
                    target,
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
                        Outcome.rename_failed,
                        "atomic triple rename failed member=${member.label} path=${member.livePath} error=${t.message}",
                    )
                } else {
                    Completion(
                        target,
                        Outcome.partial_rollback_failed,
                        "atomic triple rename failed member=${member.label}; rollback failures=${rollbackFailures.joinToString(";")}",
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
            Completion(target, Outcome.success)
        } else {
            Completion(target, Outcome.pruned_with_residual_backup, "backup retained paths=${residualBackups.joinToString(",")}")
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
