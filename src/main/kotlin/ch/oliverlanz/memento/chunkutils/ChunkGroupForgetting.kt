package ch.oliverlanz.memento.chunkutils

import ch.oliverlanz.memento.MementoAnchors
import ch.oliverlanz.memento.MementoPersistence
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Anchor-based group forgetting.
 *
 * This slice intentionally keeps the model simple and predictable:
 * - Witherstone (FORGET) anchors carry a days counter (no per-anchor timestamps).
 * - Once per Overworld day we decrement days by 1.
 * - When an anchor reaches days==0, we derive a chunk group (radius) and queue it.
 * - Groups execute only when *all* their chunks are currently unloaded.
 * - Execution is triggered by chunk unload events (plus a lightweight sweep).
 *
 * Notes:
 * - The group queue is ephemeral and can be rebuilt on server start.
 * - Lorestone protection and world-edge GC are explicitly out-of-scope for this slice.
 */
object ChunkGroupForgetting {

    private val logger = LoggerFactory.getLogger("memento")

    /**
     * We identify groups by their originating anchor name.
     * (Anchor names are already unique per the command semantics.)
     */
    data class Group(
        val anchorName: String,
        val dimension: RegistryKey<World>,
        val anchorPos: BlockPos,
        val radiusChunks: Int,
        val chunks: Set<ChunkPos>
    )

    // Eligible groups waiting for execution. Ephemeral.
    private val eligibleGroups = ConcurrentHashMap<String, Group>()

    // Active chunk forget marks used by the VersionedChunkStorage mixin.
    // Keyed by "dimensionId:chunkLong".
    private val activeForgetChunks = ConcurrentHashMap.newKeySet<String>()

    // Guard against re-entrant execution.
    private val executingGroups = ConcurrentHashMap.newKeySet<String>()

    fun snapshotEligibleGroups(): List<Group> = eligibleGroups.values.sortedBy { it.anchorName.lowercase() }

    /**
     * Called on SERVER_STARTED after anchors are loaded.
     * Rebuilds the eligible queue from anchors whose days already reached 0.
     */
    fun rebuildEligibleGroups(server: MinecraftServer) {
        eligibleGroups.clear()
        executingGroups.clear()
        activeForgetChunks.clear()

        for (a in MementoAnchors.list()) {
            if (a.kind != MementoAnchors.Kind.FORGET) continue
            val days = a.days ?: continue
            if (days == -1) continue // "never" / disabled
            if (days != 0) continue

            val group = deriveGroup(a)
            eligibleGroups[a.name] = group
        }

        if (eligibleGroups.isNotEmpty()) {
            logger.info("(memento) Rebuilt eligible forgetting queue: {} group(s)", eligibleGroups.size)
        }
    }

    /**
     * Nightly/daily aging hook.
     *
     * We intentionally use *world days* rather than real time or per-anchor timestamps.
     * This keeps the behavior deterministic even if players sleep or ops set the time.
     */
    fun ageAnchorsOnce(server: MinecraftServer) {
        var changed = false

        val updated = MementoAnchors.list().map { a ->
            if (a.kind != MementoAnchors.Kind.FORGET) return@map a
            val days = a.days ?: return@map a
            if (days == -1) return@map a
            if (days <= 0) return@map a

            val newDays = days - 1
            changed = true

            // When we hit zero, enqueue the group immediately.
            if (newDays == 0) {
                val group = deriveGroup(a)
                eligibleGroups[a.name] = group
                logger.info(
                    "(memento) Witherstone '{}' is due: queued group (dim={}, radiusChunks={}, chunks={}).",
                    a.name,
                    a.dimension.value,
                    a.radius,
                    group.chunks.size
                )
            }

            a.copy(days = newDays)
        }

        if (changed) {
            MementoAnchors.putAll(updated)
            // Persist anchor countdown changes.
            MementoPersistence.save(server)
        }
    }

    /**
     * Called whenever a chunk unloads.
     * The unload event is the natural place to check group readiness because it signals
     * that the server is actually releasing memory (no need for polling/retries).
     */
    fun onChunkUnloaded(server: MinecraftServer, world: ServerWorld, chunkPos: ChunkPos) {
        if (eligibleGroups.isEmpty()) return

        // Only groups in the same dimension can be affected.
        val candidates = eligibleGroups.values.filter { it.dimension == world.registryKey && it.chunks.contains(chunkPos) }
        if (candidates.isEmpty()) return

        for (g in candidates) {
            tryExecuteIfReady(server, world, g)
        }
    }

    /**
     * Lightweight check to allow groups that are *already* fully unloaded to execute without
     * waiting for the next unload event (e.g. after server start or after nightly aging).
     */
    fun sweep(server: MinecraftServer) {
        if (eligibleGroups.isEmpty()) return
        for (g in eligibleGroups.values) {
            val world = server.getWorld(g.dimension) ?: continue
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
     * True if the chunk is part of a currently eligible (due) group.
     * This is used for sober observability logs and /memento info output.
     *
     * Note: this is not used for regeneration decisions. Those are controlled by
     * [shouldForgetNow] during an active group execution.
     */
    fun isQueued(dimension: RegistryKey<World>, pos: ChunkPos): Boolean {
        return eligibleGroups.values.any { it.dimension == dimension && it.chunks.contains(pos) }
    }

    private fun tryExecuteIfReady(server: MinecraftServer, world: ServerWorld, group: Group) {
        val name = group.anchorName
        if (!executingGroups.add(name)) return

        try {
            // The core invariant: we only regenerate when the entire group is currently unloaded.
            if (!allChunksUnloaded(world, group)) return

            executeGroup(server, world, group)
        } finally {
            executingGroups.remove(name)
        }
    }

    private fun executeGroup(server: MinecraftServer, world: ServerWorld, group: Group) {
        logger.info(
            "(memento) Renewing chunk group for witherstone '{}' (dim={}, chunks={}).",
            group.anchorName,
            group.dimension.value,
            group.chunks.size
        )

        // Mark all chunks as forgotten *before* we load them.
        // This ensures the mixin sees the decision and returns Optional.empty().
        markActiveForget(group)

        // Actively load the group so regeneration happens together.
        // This is intentionally a one-shot action; we do not keep the chunks forced.
        for (pos in group.chunks) {
            try {
                world.chunkManager.getChunk(pos.x, pos.z, ChunkStatus.FULL, true)
            } catch (t: Throwable) {
                // Best effort: if one chunk fails to load, we keep the group queued and try later.
                logger.warn(
                    "(memento) Failed to load chunk during group renewal: dim={}, chunk=({}, {}), anchor='{}': {}",
                    group.dimension.value,
                    pos.x,
                    pos.z,
                    group.anchorName,
                    t.toString()
                )
                return
            }
        }

        // Once we triggered regeneration for all chunks, we can release the forget marks.
        // The regenerated chunks will be saved like any normal chunk going forward.
        unmarkActiveForget(group)

        // Forgetting a group also removes the source anchor.
        eligibleGroups.remove(group.anchorName)
        MementoAnchors.remove(group.anchorName)
        MementoPersistence.save(server)

        logger.info("(memento) Completed renewal for witherstone '{}'. Anchor removed.", group.anchorName)
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
            // Use a best-effort loaded check that does not force a load.
            if (ChunkLoading.isChunkLoadedBestEffort(world, pos)) {
                return false
            }
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
