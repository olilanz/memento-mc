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
    enum class GroupState {
    /** Land is subject to forgetting. */
    MARKED,

    /** One or more affected chunks are still loaded; forgetting is not safe yet. */
    BLOCKED,

    /** All affected chunks are unloaded; forgetting is now safe. */
    FREE,

    /** Forget marks are armed; we are observing chunk renewals. */
    FORGETTING,

    /** All chunks in the group have been observed to renew. */
    RENEWED
}

data class Group(
    val anchorName: String,
    val dimension: RegistryKey<World>,
    val anchorPos: BlockPos,
    val radiusChunks: Int,
    val chunks: Set<ChunkPos>,
    @Volatile var state: GroupState = GroupState.MARKED
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
 * Reverse lookup used for observability and completion tracking.
 *
 * When a chunk is marked for forgetting, we also remember which anchor/group owns it.
 * The chunk NBT predicate (via mixin) can then report "this chunk was forgotten" and
 * we can attribute that observation to the correct executing group without scanning.
 *
 * Key format: "<dimensionId>:<chunkLong>"
 * Value: anchorName
 */
private val activeChunkOwner = ConcurrentHashMap<String, String>()

/**
 * Current server instance (attached on SERVER_STARTED).
 *
 * The VersionedChunkStorage mixin calls into ChunkForgetPredicate without giving us a server reference.
 * We keep a best-effort reference here so that completion (Renewed -> Consumed) can be finalized
 * on the main thread with correct persistence and OP messaging.
 */
@Volatile
private var serverRef: MinecraftServer? = null

fun attachServer(server: MinecraftServer) {
    serverRef = server
}

fun detachServer(server: MinecraftServer) {
    if (serverRef === server) serverRef = null
}

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
    /** Chunks for which we have observed a regeneration decision (NBT treated as missing). */
    val renewedChunks: MutableSet<ChunkPos> = ConcurrentHashMap.newKeySet<ChunkPos>(),
    /** Used to rate-limit progress logging. Incremented opportunistically. */
    var progressPings: Int = 0
)

    private val executing = ConcurrentHashMap<String, Execution>()

    
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
            val loadedCount = g.chunks.count { isChunkCurrentlyLoaded(world, it) }

// State transitions for the group lifecycle (Marked/Blocked/Free).
val nextState = if (loadedCount > 0) GroupState.BLOCKED else GroupState.FREE
if (g.state != nextState && g.state != GroupState.FORGETTING && g.state != GroupState.RENEWED) {
    val prev = g.state
    g.state = nextState
    MementoDebug.info(
        server,
        "Chunk group state: '${g.anchorName}' ${prev} -> ${nextState} (loadedChunks=$loadedCount/${g.chunks.size})"
    )
}

if (loadedCount > 0) {
                // We do not guess the reason (players/mobs/spawn/ticking blocks). We report the fact.
                MementoDebug.info(
                    server,
                    "The land cannot be forgotten yet: some affected chunks are still loaded or force-ticked (spawn chunks, entities, block ticking, etc.) (anchor='${g.anchorName}' dim=${g.dimension.value} loadedChunks=$loadedCount/${g.chunks.size})"
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
 * Called from ChunkForgetPredicate when the storage mixin decided to treat this chunk as missing.
 *
 * This is our only reliable "observation" that the chunk will regenerate.
 * We use it to drive the final state transitions:
 *
 *   Group: FORGETTING -> RENEWED
 *   Witherstone: Matured -> Consumed
 */
fun onChunkRenewalObserved(dimension: RegistryKey<World>, pos: ChunkPos) {
    val key = "${dimension.value}:" + pos.toLong()
    val owner = activeChunkOwner[key] ?: return

    val exec = executing[owner] ?: return
    val group = exec.group
    if (group.dimension != dimension) return
    if (!group.chunks.contains(pos)) return

    // Record observation.
    val added = exec.renewedChunks.add(pos)
    if (!added) return

    val total = group.chunks.size
    val done = exec.renewedChunks.size

    // Low-noise progress logging: first observation and then every 5.
    val server = serverRef
    if (server != null) {
        if (done == 1 || done % 5 == 0 || done == total) {
            MementoDebug.info(
                server,
                "Renewal observed for '${group.anchorName}': $done/$total chunk(s) renewed"
            )
        }
    }

    // Completion condition: all chunks observed renewed.
    if (done == total) {
        group.state = GroupState.RENEWED
        val srv = serverRef ?: return
        // Ensure finalization happens on the server thread.
        srv.execute {
            finalizeForgetting(srv, group)
        }
    }
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

        // Only start when the group is explicitly FREE.
        if (group.state != GroupState.FREE) {
            executingGroups.remove(name)
            return
        }

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

        // Observability: include the anchor chunk and a compact sample of group chunks.
        val anchorChunk = ChunkPos(group.anchorPos)
        val sample = group.chunks.take(6).joinToString(", ") { "(${it.x},${it.z})" }
        val suffix = if (group.chunks.size > 6) ", ..." else ""
        MementoDebug.info(
            server,
            "Forgetting group details: anchorChunk=(${anchorChunk.x},${anchorChunk.z}) sampleChunks=$sample$suffix"
        )


        // Arm forget marks before requesting loads so the storage mixin can act.
        markActiveForget(group)

        // Track execution for finalize.
        group.state = GroupState.FORGETTING
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
    }

    private fun finalizeForgetting(server: MinecraftServer, group: Group) {
    // Defensive: we only consume the witherstone after *all* chunks were observed renewed.
    if (group.state != GroupState.RENEWED) {
        MementoDebug.warn(
            server,
            "Finalize refused for '${group.anchorName}': group not renewed yet (state=${group.state})"
        )
        return
    }

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
            activeChunkOwner[key] = group.anchorName
        }
    }

    private fun unmarkActiveForget(group: Group) {
        for (pos in group.chunks) {
            val key = "${group.dimension.value}:" + pos.toLong()
            activeForgetChunks.remove(key)
            activeChunkOwner.remove(key)
        }
    }

    private fun allChunksUnloaded(world: ServerWorld, group: Group): Boolean {
        // For correctness we must avoid false negatives here.
        // Using ServerChunkManager.getChunk(..., create=false) does not load chunks;
        // it only reports chunks that are already loaded/tracked by the server.
        for (pos in group.chunks) {
            if (isChunkCurrentlyLoaded(world, pos)) return false
        }
        return true
    }

    private fun isChunkCurrentlyLoaded(world: ServerWorld, pos: ChunkPos): Boolean {
        // ChunkStatus.EMPTY is the lowest status; if the chunk is present in memory at any status,
        // this should return non-null. create=false guarantees we never force-load.
        return world.chunkManager.getChunk(pos.x, pos.z, ChunkStatus.EMPTY, false) != null
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