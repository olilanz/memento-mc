package ch.oliverlanz.memento.infrastructure.pruning

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.renewal.projection.RegionKey
import ch.oliverlanz.memento.infrastructure.async.GlobalAsyncExclusionGate
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import ch.oliverlanz.memento.infrastructure.worldscan.RegionDiscovery
import ch.oliverlanz.memento.infrastructure.worldscan.WorldScanner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Future
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
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
        rename_failed,
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
        val worldKey: RegistryKey<World>,
        val regionX: Int,
        val regionZ: Int,
        val future: Future<Completion>,
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

    fun submit(region: RegionKey): SubmitResult {
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

        val worldKey = resolveWorldKey(region.worldId)
        val world = worldKey?.let { srv.getWorld(it) }
        if (worldKey == null || world == null) {
            val completion = Completion(region, Outcome.rename_failed, "unknown world='${region.worldId}'")
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
                pruneRegion(root, region, worldKey)
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
                    worldKey = worldKey,
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
                world = op.worldKey,
                regionX = op.regionX,
                regionZ = op.regionZ,
                reason = "renew_force_prune",
                scanTick = scanTick,
            )
        }
    }

    private fun pruneRegion(root: Path, target: RegionKey, worldKey: RegistryKey<World>): Completion {
        val regionFile = RegionDiscovery.resolveRegionFile(root, worldKey, target.regionX, target.regionZ)
        val backup = regionFile.resolveSibling(regionFile.fileName.toString() + MementoConstants.MEMENTO_RENEW_FORCE_BACKUP_SUFFIX)

        if (Files.exists(backup)) {
            try {
                Files.delete(backup)
            } catch (t: Throwable) {
                return Completion(target, Outcome.rename_failed, "backup not removable path=$backup error=${t.message}")
            }
        }

        if (!Files.exists(regionFile)) {
            return Completion(target, Outcome.rename_failed, "region file missing path=$regionFile")
        }

        try {
            Files.move(regionFile, backup, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            return Completion(target, Outcome.rename_failed, "atomic rename failed path=$regionFile error=${t.message}")
        }

        return try {
            Files.deleteIfExists(backup)
            Completion(target, Outcome.success)
        } catch (_: Throwable) {
            Completion(target, Outcome.pruned_with_residual_backup, "backup retained path=$backup")
        }
    }

    private fun resolveWorldKey(worldId: String): RegistryKey<World>? {
        return runCatching {
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of(worldId))
        }.getOrNull()
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
