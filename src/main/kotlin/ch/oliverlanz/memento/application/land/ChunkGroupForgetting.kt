package ch.oliverlanz.memento.application.land

import ch.oliverlanz.memento.application.MementoAnchors
import ch.oliverlanz.memento.domain.land.ChunkGroup
import ch.oliverlanz.memento.domain.land.GroupState
import ch.oliverlanz.memento.infrastructure.MementoDebug
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkStatus
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Application service managing derived ChunkGroups and their regeneration execution.
 *
 * Constraints (locked for this slice):
 * - Derived groups are not persisted
 * - Anchor mutation / persistence is handled by the stone lifecycle facade
 */
object ChunkGroupForgetting {

    private val groups = ConcurrentHashMap<String, ChunkGroup>()
    private val executions = ConcurrentHashMap<String, Execution>()
    private val activeForgetChunks = ConcurrentHashMap<String, String>()

    private var tickCounter: Int = 0

    private data class Execution(
        val group: ChunkGroup,
        val remaining: ArrayDeque<ChunkPos>,
        var renewed: Int = 0
    )

    // ---------------------------------------------------------------------
    // Public API (used by Commands / Inspection / Lifecycle)
    // ---------------------------------------------------------------------

    fun getGroupByAnchorName(name: String): ChunkGroup? =
        groups[name]

    fun discardGroup(anchorName: String) {
        val g = groups.remove(anchorName) ?: return
        executions.remove(anchorName)
        unmarkForget(g)
    }

    fun snapshotGroups(): List<ChunkGroup> =
        groups.values.sortedBy { it.anchorName }

    /**
     * Rebuild derived groups from persisted, already-matured anchors.
     *
     * Note: this only (re)creates MARKED groups. It does not force transitions.
     */
    fun rebuildFromAnchors(server: MinecraftServer) {
        groups.clear()
        executions.clear()
        activeForgetChunks.clear()
        tickCounter = 0

        val matured = MementoAnchors.list().filter { a ->
            a.kind == MementoAnchors.Kind.FORGET &&
                a.state == MementoAnchors.WitherstoneState.MATURED
        }

        for (a in matured) {
            val g = deriveGroup(a)
            groups[g.anchorName] = g
        }

        if (groups.isNotEmpty()) {
            MementoDebug.info(
                server,
                "Rebuilt ${groups.size} chunk group(s) from matured witherstones"
            )
        }
    }

    /**
     * Refresh per-world group state:
     * - MARKED/BLOCKED -> FREE when the last chunk unloads
     * - FREE triggers execution start (FORGETTING)
     */
    fun refresh(world: ServerWorld, server: MinecraftServer) {
        for (g in groups.values.filter { it.dimension == world.registryKey }) {
            val loaded = countLoaded(world, g)
            val next =
                if (loaded > 0) GroupState.BLOCKED
                else GroupState.FREE

            if (
                g.state != next &&
                g.state != GroupState.FORGETTING &&
                g.state != GroupState.RENEWED
            ) {
                val prev = g.state
                g.state = next
                MementoDebug.info(
                    server,
                    "Chunk group state: '${g.anchorName}' $prev -> $next (loadedChunks=$loaded/${g.chunks.size})"
                )
            }

            if (g.state == GroupState.FREE) {
                startExecution(world, server, g)
            }
        }
    }

    /**
     * Budgeted execution step: attempts to load at most one chunk every [intervalTicks] across all active executions.
     *
     * The actual regeneration happens on load via the storage mixin + ChunkForgetPredicate.
     */
    fun tick(server: MinecraftServer, intervalTicks: Int) {
        if (executions.isEmpty()) return
        if (intervalTicks <= 0) return

        tickCounter++
        if (tickCounter % intervalTicks != 0) return

        // Global budget: choose one group deterministically (by anchorName) and load its next chunk.
        val ex = executions.values
            .filter { it.remaining.isNotEmpty() }
            .sortedBy { it.group.anchorName }
            .firstOrNull()
            ?: return

        val world = server.getWorld(ex.group.dimension) ?: return
        val pos = ex.remaining.firstOrNull() ?: return

        // Trigger a normal chunk load. This is the execution mechanism:
        // the storage mixin consults ChunkForgetPredicate and regenerates if marked.
        world.chunkManager.getChunk(pos.x, pos.z, ChunkStatus.FULL, true)
    }

    private fun startExecution(
        world: ServerWorld,
        server: MinecraftServer,
        group: ChunkGroup
    ) {
        if (executions.containsKey(group.anchorName)) return
        if (!allUnloaded(world, group)) return

        group.state = GroupState.FORGETTING
        markForget(group)

        executions[group.anchorName] =
            Execution(group, ArrayDeque(group.chunks))

        MementoDebug.warn(
            server,
            "The land is being forgotten for witherstone '${group.anchorName}' (dim=${group.dimension.value}, chunks=${group.chunks.size})"
        )
    }

    // ---------------------------------------------------------------------
    // Renewal observation (called via predicate)
    // ---------------------------------------------------------------------

    /**
     * Observe a renewed chunk and advance execution state.
     *
     * Returns the completed group when the last chunk is renewed; otherwise null.
     */
    fun onChunkRenewed(
        server: MinecraftServer,
        dimension: RegistryKey<World>,
        pos: ChunkPos
    ): ChunkGroup? {
        val owner = activeForgetChunks[key(dimension, pos)] ?: return null
        val ex = executions[owner] ?: return null

        if (!ex.remaining.remove(pos)) return null

        ex.renewed++
        if (ex.renewed % 5 == 0 || ex.remaining.isEmpty()) {
            MementoDebug.info(
                server,
                "Renewal observed for '$owner': ${ex.renewed}/${ex.group.chunks.size} chunk(s) renewed"
            )
        }

        if (ex.remaining.isEmpty()) {
            return finalize(server, ex.group)
        }

        return null
    }

    private fun finalize(server: MinecraftServer, group: ChunkGroup): ChunkGroup {
        group.state = GroupState.RENEWED
        executions.remove(group.anchorName)
        unmarkForget(group)
        groups.remove(group.anchorName)

        MementoDebug.info(
            server,
            "The land has renewed for witherstone '${group.anchorName}'"
        )

        return group
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun deriveGroup(a: MementoAnchors.Anchor): ChunkGroup =
        ChunkGroup(
            anchorName = a.name,
            dimension = a.dimension,
            anchorPos = a.pos,
            radiusChunks = a.radius,
            chunks = MementoAnchors.computeChunksInRadius(a.pos, a.radius).toList()
        )

    private fun countLoaded(world: ServerWorld, g: ChunkGroup): Int =
        g.chunks.count {
            world.chunkManager.getChunk(it.x, it.z, ChunkStatus.EMPTY, false) != null
        }

    private fun allUnloaded(world: ServerWorld, g: ChunkGroup): Boolean =
        countLoaded(world, g) == 0

    private fun markForget(g: ChunkGroup) {
        for (pos in g.chunks) {
            activeForgetChunks[key(g.dimension, pos)] = g.anchorName
        }
    }

    private fun unmarkForget(g: ChunkGroup) {
        for (pos in g.chunks) {
            activeForgetChunks.remove(key(g.dimension, pos))
        }
    }

    fun isMarked(dimension: RegistryKey<World>, pos: ChunkPos): Boolean =
        activeForgetChunks.containsKey(key(dimension, pos))

    private fun key(d: RegistryKey<World>, p: ChunkPos) =
        "${d.value}|${p.x}|${p.z}"
}
