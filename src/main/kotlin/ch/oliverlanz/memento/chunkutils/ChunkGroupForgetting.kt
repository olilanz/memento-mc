package ch.oliverlanz.memento.chunkutils

import ch.oliverlanz.memento.MementoAnchors
import ch.oliverlanz.memento.MementoDebug
import ch.oliverlanz.memento.MementoPersistence
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * Anchor-based group forgetting.
 *
 * This slice keeps the mechanics deterministic and explainable:
 *
 * - Witherstone anchors (Kind.FORGET) carry a "time to maturity" counter (days).
 * - Once per Overworld day, days are decremented.
 * - When days reaches 0, the anchor has matured and its derived chunk group is
 *   *marked for forgetting*.
 * - A marked group may only renew when *all its chunks are unloaded*.
 * - Unload is only the "permission edge" (land is free); regeneration happens on
 *   the subsequent load via the VersionedChunkStorage mixin.
 *
 * Important invariant:
 * - Group renewal is atomic: we only start when all chunks in the group are unloaded.
 */
object ChunkGroupForgetting {

    /**
     * One derived chunk group per Witherstone.
     *
     * Groups are ephemeral: they are rebuilt from anchors on startup.
     */
    data class Group(
        val anchorName: String,
        val dimension: RegistryKey<World>,
        val anchorPos: BlockPos,
        val radiusChunks: Int,
        val chunks: Set<ChunkPos>
    )

    /** Groups marked for forgetting, waiting to become fully unloaded. */
    private val markedGroups = ConcurrentHashMap<String, Group>()

    /**
     * Active forget marks used by the VersionedChunkStorage mixin.
     *
     * Key format: "<dimensionId>:<chunkLong>"
     */
    private val activeForgetChunks = ConcurrentHashMap.newKeySet<String>()

    /**
     * Guards groups currently executing so we don't start twice.
     *
     * Note: a group remains "executing" from the moment we start until we finalize
     * (or abort) – not just during the synchronous executeGroup method.
     */
    private val executingGroups = ConcurrentHashMap.newKeySet<String>()

    /**
     * Execution bookkeeping used to finalize safely.
     *
     * Why this exists:
     * Chunk loads / NBT reads are not guaranteed to happen synchronously for every
     * requested chunk. If we unmark forget-chunks immediately after requesting loads,
     * only the first chunk(s) may be forgotten.
     *
     * Therefore we keep marks armed until we observe that all group chunks are loaded,
     * then we consume the anchor and disarm the marks.
     */
    private data class Execution(
        val group: Group,
        var ticksWaited: Int = 0
    )

    private val executing = ConcurrentHashMap<String, Execution>()

    /** Safety bound: avoid keeping forget marks armed indefinitely. (10 seconds) */
    private const val FINALIZE_MAX_TICKS = 200

    fun snapshotMarkedGroups(): List<Group> =
        markedGroups.values.sortedBy { it.anchorName.lowercase() }

    /**
     * Rebuild marked groups on startup from already-matured anchors.
     */
    fun rebuildMarkedGroups(server: MinecraftServer) {
        markedGroups.clear()
        activeForgetChunks.clear()
        executingGroups.clear()
        executing.clear()

        var rebuilt = 0
        for (a in MementoAnchors.list()) {
            if (a.kind != MementoAnchors.Kind.FORGET) continue
            val days = a.days ?: continue
            if (days == -1) continue
            if (days != 0) continue

            val g = deriveGroup(a)
            markedGroups[a.name] = g
            rebuilt++
            // Treat loaded anchors like "just evaluated" for observability.
            MementoDebug.info(
                server,
                "Loaded matured witherstone: name='${a.name}' dim=${a.dimension.value} pos=${a.pos.x} ${a.pos.y} ${a.pos.z} r=${a.radius} chunks=${g.chunks.size}"
            )
        }

        if (rebuilt > 0) {
            MementoDebug.info(server, "Rebuilt marked land set for forgetting: $rebuilt group(s)")
        }
    }

    /**
     * Decrement time-to-maturity once per Overworld day.
     * When a witherstone reaches 0, it matures and its land is marked for forgetting.
     */
    fun ageAnchorsOnce(server: MinecraftServer) {
        var changed = false
        var maturedCount = 0

        val updated = MementoAnchors.list().map { a ->
            if (a.kind != MementoAnchors.Kind.FORGET) return@map a
            val days = a.days ?: return@map a
            if (days == -1) return@map a
            if (days <= 0) return@map a

            val newDays = days - 1
            changed = true

            MementoDebug.info(
                server,
                "Witherstone '${a.name}' is maturing: $newDays day(s) remaining (dim=${a.dimension.value}, pos=${a.pos.x},${a.pos.y},${a.pos.z})"
            )

            if (newDays == 0) {
                val g = deriveGroup(a)
                markedGroups[a.name] = g
                maturedCount++

                MementoDebug.warn(server, "Witherstone '${a.name}' has matured")
                MementoDebug.info(
                    server,
                    "The surrounding land is marked for forgetting (dim=${a.dimension.value}, radiusChunks=${a.radius}, chunks=${g.chunks.size})"
                )
            }

            a.copy(days = newDays)
        }

        if (changed) {
            MementoAnchors.putAll(updated)
            MementoPersistence.save(server)
            if (maturedCount > 0) {
                MementoDebug.info(server, "Daily maturation complete: $maturedCount witherstone(s) matured")
            }
        }
    }

    /**
     * Chunk unload trigger: re-check any marked groups affected by this chunk.
     */
    fun onChunkUnloaded(server: MinecraftServer, world: ServerWorld, chunkPos: ChunkPos) {
        if (markedGroups.isEmpty()) return

        val candidates = markedGroups.values.filter {
            it.dimension == world.registryKey && it.chunks.contains(chunkPos)
        }
        if (candidates.isEmpty()) return

        for (g in candidates) {
            tryExecuteIfReady(server, world, g)
        }
    }

    /**
     * Day-change sweep: emits a clear "blocked" message and attempts execution for already-free land.
     */
    fun sweep(server: MinecraftServer) {
        if (markedGroups.isEmpty()) return

        for (g in markedGroups.values) {
            // If already executing/finalizing, don't spam; the finalize loop owns progress.
            if (executingGroups.contains(g.anchorName)) continue

            val world = server.getWorld(g.dimension) ?: continue
            val loadedCount = g.chunks.count { ChunkLoading.isChunkLoadedBestEffort(world, it) }

            if (loadedCount > 0) {
                // We do not guess the reason (players/mobs/spawn/ticking blocks). We report the fact.
                MementoDebug.info(
                    server,
                    "The land cannot be forgotten yet: some affected chunks are still loaded (anchor='${g.anchorName}' dim=${g.dimension.value} loadedChunks=$loadedCount/${g.chunks.size})"
                )
            }

            tryExecuteIfReady(server, world, g)
        }
    }

    /**
     * The VersionedChunkStorage mixin consults this predicate.
     * If true, we return Optional.empty() for chunk NBT, forcing regeneration.
     */
    fun shouldForgetNow(dimension: RegistryKey<World>, pos: ChunkPos): Boolean {
        val key = "${dimension.value}:" + pos.toLong()
        return activeForgetChunks.contains(key)
    }

    /**
     * For observability and /memento info: is this chunk part of any currently marked group?
     */
    fun isMarked(dimension: RegistryKey<World>, pos: ChunkPos): Boolean {
        return markedGroups.values.any { it.dimension == dimension && it.chunks.contains(pos) }
    }

    private fun tryExecuteIfReady(server: MinecraftServer, world: ServerWorld, group: Group) {
        val name = group.anchorName

        // If already executing/finalizing, do nothing.
        if (!executingGroups.add(name)) return

        // Core invariant: only start when the entire group is currently unloaded.
        if (!allChunksUnloaded(world, group)) {
            executingGroups.remove(name)
            return
        }

        executeGroup(server, world, group)
        // Note: executingGroups remains set until finalize/abort.
    }

    private fun executeGroup(server: MinecraftServer, world: ServerWorld, group: Group) {
        MementoDebug.warn(
            server,
            "The land is being forgotten for witherstone '${group.anchorName}' (dim=${group.dimension.value}, chunks=${group.chunks.size})"
        )

        // Arm forget marks before requesting loads so the storage mixin can act.
        markActiveForget(group)

        // Track execution for finalize.
        executing[group.anchorName] = Execution(group)

        // Actively load all group chunks (one-shot). This triggers regeneration via mixin.
        for (pos in group.chunks) {
            try {
                world.chunkManager.getChunk(pos.x, pos.z, ChunkStatus.FULL, true)
            } catch (t: Throwable) {
                MementoDebug.warn(
                    server,
                    "Failed to load chunk during group renewal: dim=${group.dimension.value}, chunk=(${pos.x}, ${pos.z}) anchor='${group.anchorName}' ($t)"
                )
                abortExecution(group)
                return
            }
        }

        // Do NOT unmark / consume immediately.
        // Loads/NBT reads may complete later; we finalize when all group chunks are observed loaded.
        scheduleFinalize(server, group.anchorName)
    }

    private fun scheduleFinalize(server: MinecraftServer, anchorName: String) {
        server.execute {
            val exec = executing[anchorName] ?: return@execute
            val group = exec.group
            val world = server.getWorld(group.dimension)

            if (world == null) {
                MementoDebug.warn(server, "Finalize aborted: world not available for '${group.anchorName}'")
                abortExecution(group)
                return@execute
            }

            val loadedCount = group.chunks.count { ChunkLoading.isChunkLoadedBestEffort(world, it) }
            if (loadedCount < group.chunks.size) {
                exec.ticksWaited++

                // Low-noise progress: log on first tick and then every second.
                if (exec.ticksWaited == 1 || exec.ticksWaited % 20 == 0) {
                    MementoDebug.info(
                        server,
                        "Finalizing forgetting for '${group.anchorName}': waiting for chunks to load ($loadedCount/${group.chunks.size})"
                    )
                }

                if (exec.ticksWaited >= FINALIZE_MAX_TICKS) {
                    MementoDebug.warn(
                        server,
                        "Finalizing forgetting for '${group.anchorName}' timed out; aborting this attempt (loaded $loadedCount/${group.chunks.size}). The land remains marked."
                    )
                    abortExecution(group)
                    // Keep the group marked so it can be retried on future unload/sweep.
                    return@execute
                }

                scheduleFinalize(server, anchorName)
                return@execute
            }

            // All chunks are loaded now; it's safe to disarm marks and consume anchor.
            finalizeForgetting(server, group)
        }
    }

    private fun finalizeForgetting(server: MinecraftServer, group: Group) {
        unmarkActiveForget(group)

        markedGroups.remove(group.anchorName)
        MementoAnchors.remove(group.anchorName)
        executing.remove(group.anchorName)
        executingGroups.remove(group.anchorName)

        MementoPersistence.save(server)

        MementoDebug.info(server, "The land has renewed; witherstone consumed: '${group.anchorName}'")
    }

    private fun abortExecution(group: Group) {
        unmarkActiveForget(group)
        executing.remove(group.anchorName)
        executingGroups.remove(group.anchorName)
    }

    private fun markActiveForget(group: Group) {
        for (pos in group.chunks) {
            val key = "${group.dimension.value}:" + pos.toLong()
            activeForgetChunks.add(key)
        }
    }

    private fun unmarkActiveForget(group: Group) {
        for (pos in group.chunks) {
            val key = "${group.dimension.value}:" + pos.toLong()
            activeForgetChunks.remove(key)
        }
    }

    private fun allChunksUnloaded(world: ServerWorld, group: Group): Boolean {
        for (pos in group.chunks) {
            if (ChunkLoading.isChunkLoadedBestEffort(world, pos)) return false
        }
        return true
    }

    private fun deriveGroup(anchor: MementoAnchors.Anchor): Group {
        return Group(
            anchorName = anchor.name,
            dimension = anchor.dimension,
            anchorPos = anchor.pos,
            radiusChunks = anchor.radius,
            chunks = MementoAnchors.computeChunksInRadius(anchor.pos, anchor.radius)
        )
    }
}
