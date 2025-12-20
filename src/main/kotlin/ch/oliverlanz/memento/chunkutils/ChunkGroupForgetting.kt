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
 * This slice intentionally keeps the model simple and predictable:
 * - Witherstone (FORGET) anchors carry a days counter (no per-anchor timestamps).
 * - Once per Overworld day we decrement days by 1.
 * - When a Witherstone reaches days==0, it has matured: we derive its land group and mark it for forgetting.
 * - Groups execute only when *all* their chunks are currently unloaded.
 * - Execution is triggered by chunk unload events (plus a lightweight sweep).
 *
 * Notes:
 * - The marked land set is ephemeral and can be rebuilt on server start.
 * - Lorestone protection and world-edge GC are explicitly out-of-scope for this slice.
 */
object ChunkGroupForgetting {

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

    // Land groups marked for forgetting, waiting for execution. Ephemeral.
    private val markedGroups = ConcurrentHashMap<String, Group>()

    // Active chunk forget marks used by the VersionedChunkStorage mixin.
    // Keyed by "dimensionId:chunkLong".
    private val activeForgetChunks = ConcurrentHashMap.newKeySet<String>()

    // Guard against re-entrant execution.
    private val executingGroups = ConcurrentHashMap.newKeySet<String>()

    fun snapshotMarkedGroups(): List<Group> = markedGroups.values.sortedBy { it.anchorName.lowercase() }

    /**
     * Called on SERVER_STARTED after anchors are loaded.
     * Rebuilds the marked set from anchors whose days already reached 0.
     */
    fun rebuildMarkedGroups(server: MinecraftServer) {
        markedGroups.clear()
        executingGroups.clear()
        activeForgetChunks.clear()

        for (a in MementoAnchors.list()) {
            if (a.kind != MementoAnchors.Kind.FORGET) continue
            val days = a.days ?: continue
            if (days == -1) continue // "never" / disabled
            if (days != 0) continue

            val group = deriveGroup(a)
            markedGroups[a.name] = group
        }

        if (markedGroups.isNotEmpty()) {
            // High-signal lifecycle information: helps follow the system in-game during testing.
            MementoDebug.info(server, "Rebuilt marked land set for forgetting: ${markedGroups.size} group(s)")
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
        var maturedCount = 0

        val updated = MementoAnchors.list().map { a ->
            if (a.kind != MementoAnchors.Kind.FORGET) return@map a
            val days = a.days ?: return@map a
            if (days == -1) return@map a
            if (days <= 0) return@map a

            val newDays = days - 1
            changed = true

            // High-signal observability: show the time-to-maturity countdown as it progresses.
            // This runs at most once per Overworld day.
            MementoDebug.info(
                server,
                "Witherstone '${a.name}' is maturing: $newDays day(s) remaining (dim=${a.dimension.value}, pos=${a.pos.x},${a.pos.y},${a.pos.z})"
            )

            // When we hit zero, the Witherstone has matured: mark its land immediately.
            if (newDays == 0) {
                val group = deriveGroup(a)
                markedGroups[a.name] = group
                maturedCount++
                MementoDebug.warn(server, "Witherstone '${a.name}' has matured")
                MementoDebug.info(
                    server,
                    "The surrounding land is marked for forgetting (dim=${a.dimension.value}, radiusChunks=${a.radius}, chunks=${group.chunks.size})"
                )
            }

            a.copy(days = newDays)
        }

        if (changed) {
            MementoAnchors.putAll(updated)
            // Persist anchor countdown changes.
            MementoPersistence.save(server)
            if (maturedCount > 0) {
                MementoDebug.info(server, "Daily maturation complete: $maturedCount witherstone(s) matured")
            }
        }
    }

    /**
     * Called whenever a chunk unloads.
     * The unload event is the natural place to check group readiness because it signals
     * that the server is actually releasing memory (no need for polling/retries).
     */
    fun onChunkUnloaded(server: MinecraftServer, world: ServerWorld, chunkPos: ChunkPos) {
        if (markedGroups.isEmpty()) return

        // Only groups in the same dimension can be affected.
        val candidates = markedGroups.values.filter { it.dimension == world.registryKey && it.chunks.contains(chunkPos) }
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
        if (markedGroups.isEmpty()) return
        for (g in markedGroups.values) {
            val world = server.getWorld(g.dimension) ?: continue
            // This sweep is intentionally lightweight and runs rarely (startup + once per Overworld day).
            // It helps answer "why is nothing happening?" without spamming the chat on every tick.
            val loadedCount = g.chunks.count { ChunkLoading.isChunkLoadedBestEffort(world, it) }
            if (loadedCount > 0) {
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
     * True if the chunk is part of a currently marked (matured) group.
     * This is used for sober observability logs and /memento info output.
     *
     * Note: this is not used for regeneration decisions. Those are controlled by
     * [shouldForgetNow] during an active group execution.
     */
    fun isMarked(dimension: RegistryKey<World>, pos: ChunkPos): Boolean {
        return markedGroups.values.any { it.dimension == dimension && it.chunks.contains(pos) }
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
        // Lifecycle: the moment we actively trigger regeneration. This should be visible to ops.
        MementoDebug.warn(
            server,
            "The land is being forgotten for witherstone '${group.anchorName}' (dim=${group.dimension.value}, chunks=${group.chunks.size})"
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
                // Best effort: if one chunk fails to load, we keep the group marked and try later.
                MementoDebug.warn(
                    server,
                    "Failed to load chunk during group renewal: dim=${group.dimension.value} chunk=(${pos.x}, ${pos.z}) anchor='${group.anchorName}' (${t})"
                )
                return
            }
        }

        // Once we triggered regeneration for all chunks, we can release the forget marks.
        // The regenerated chunks will be saved like any normal chunk going forward.
        unmarkActiveForget(group)

        // Forgetting a group also removes the source anchor.
        markedGroups.remove(group.anchorName)
        MementoAnchors.remove(group.anchorName)
        MementoPersistence.save(server)

        MementoDebug.info(server, "The land has renewed; witherstone consumed: '${group.anchorName}'")
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


// Observability helper
fun logGroupFootprint(logger: org.slf4j.Logger, name: String, anchorCx: Int, anchorCz: Int, chunks: Set<Pair<Int,Int>>) {
    val xs = chunks.map{it.first}; val zs = chunks.map{it.second}
    val minX = xs.minOrNull(); val maxX = xs.maxOrNull(); val minZ = zs.minOrNull(); val maxZ = zs.maxOrNull()
    val includesAnchor = chunks.contains(anchorCx to anchorCz)
    logger.info("Group footprint for '{}': chunks={} box x=[{}..{}] z=[{}..{}] includesAnchorChunk={}", name, chunks.size, minX, maxX, minZ, maxZ, includesAnchor)
    if (chunks.size <= 18) logger.info("Chunks for '{}': {}", name, chunks.joinToString())
}
