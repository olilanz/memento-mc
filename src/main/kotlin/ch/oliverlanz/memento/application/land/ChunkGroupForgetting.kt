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

object ChunkGroupForgetting {

    enum class StoneMaturityTrigger { SERVER_START, NIGHTLY_TICK, COMMAND }
    enum class ChunkRenewalTrigger { CHUNK_UNLOAD }

    private val groups = ConcurrentHashMap<String, ChunkGroup>()
    private val executions = ConcurrentHashMap<String, Execution>()
    private val activeForgetChunks = ConcurrentHashMap<String, String>()

    private var tickCounter: Int = 0

    private data class Execution(
        val group: ChunkGroup,
        val remaining: ArrayDeque<ChunkPos>,
        var renewed: Int = 0
    )

    private fun transition(
        server: MinecraftServer,
        group: ChunkGroup,
        next: GroupState,
        context: String = ""
    ) {
        if (group.state == next) return
        val prev = group.state
        group.state = next
        val suffix = if (context.isBlank()) "" else " $context"
        MementoDebug.info(server, "ChunkGroup '${group.anchorName}' $prev -> $next$suffix")
    }

    // ---------------------------------------------------------------------
    // Public API
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

    fun rebuildFromAnchors(server: MinecraftServer) =
        rebuildFromAnchors(server, StoneMaturityTrigger.SERVER_START)

    fun rebuildFromAnchors(server: MinecraftServer, trigger: StoneMaturityTrigger) {
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

            MementoDebug.info(
                server,
                "ChunkGroup '${g.anchorName}' derived due to stone maturity trigger: $trigger " +
                    "(state=${g.state}, dim=${g.dimension.value}, chunks=${g.chunks.size})"
            )

            if (trigger == StoneMaturityTrigger.SERVER_START) {
                val world = server.getWorld(g.dimension)
                if (world != null) {
                    val loadedChunks = countLoaded(world, g)
                    if (loadedChunks > 0) {
                        MementoDebug.warn(
                            server,
                            "ChunkGroup '${g.anchorName}' derived on server start but remains blocked " +
                                "(loadedChunks=$loadedChunks/${g.chunks.size}). " +
                                "Some chunks are already loaded (spawn chunks, force-loading, or other mods)."
                        )
                    }
                }
            }
        }

        if (groups.isNotEmpty()) {
            MementoDebug.info(
                server,
                "Rebuilt ${groups.size} chunk group(s) from matured witherstones"
            )
        }
    }

    fun refresh(world: ServerWorld, server: MinecraftServer) {
        groups.values
            .filter { it.dimension == world.registryKey }
            .forEach { refreshOne(world, server, it) } // ← FIX HERE
    }

    fun onChunkUnloaded(world: ServerWorld, server: MinecraftServer, pos: ChunkPos) {
        val affected = groups.values
            .filter { it.dimension == world.registryKey && it.chunks.contains(pos) }

        if (affected.isNotEmpty()) {
            MementoDebug.info(
                server,
                "Chunk renewal trigger observed: CHUNK_UNLOAD " +
                    "(dim=${world.registryKey.value}, chunk=(${pos.x},${pos.z})) -> " +
                    "re-evaluating ${affected.size} group(s)"
            )
        }

        for (g in affected) {
            refreshOne(world, server, g, ChunkRenewalTrigger.CHUNK_UNLOAD, pos)
        }
    }

    fun tick(server: MinecraftServer, intervalTicks: Int) {
        if (executions.isEmpty()) return
        if (intervalTicks <= 0) return

        tickCounter++
        if (tickCounter % intervalTicks != 0) return

        val ex = executions.values
            .firstOrNull { it.remaining.isNotEmpty() }
            ?: return

        val world = server.getWorld(ex.group.dimension) ?: return
        val pos = ex.remaining.first()

        world.chunkManager.getChunk(pos.x, pos.z, ChunkStatus.FULL, true)
    }

    private fun refreshOne(
        world: ServerWorld,
        server: MinecraftServer,
        g: ChunkGroup,
        renewalTrigger: ChunkRenewalTrigger? = null,
        renewalChunk: ChunkPos? = null
    ) {
        val loaded = countLoaded(world, g)
        val next = if (loaded > 0) GroupState.BLOCKED else GroupState.FREE

        if (g.state != GroupState.FORGETTING && g.state != GroupState.RENEWED) {
            transition(
                server,
                g,
                next,
                context =
                    if (next == GroupState.BLOCKED)
                        "(loadedChunks=$loaded/${g.chunks.size}; waiting for chunk renewal trigger: CHUNK_UNLOAD)"
                    else
                        "(loadedChunks=$loaded/${g.chunks.size})"
            )
        }

        if (g.state == GroupState.FREE) {
            startExecution(world, server, g, renewalTrigger, renewalChunk)
        }
    }

    private fun startExecution(
        world: ServerWorld,
        server: MinecraftServer,
        group: ChunkGroup,
        renewalTrigger: ChunkRenewalTrigger? = null,
        renewalChunk: ChunkPos? = null
    ) {
        if (executions.containsKey(group.anchorName)) return
        if (!allUnloaded(world, group)) return

        val latchContext =
            if (renewalTrigger == ChunkRenewalTrigger.CHUNK_UNLOAD && renewalChunk != null)
                "(latched due to chunk renewal trigger: CHUNK_UNLOAD chunk=(${renewalChunk.x},${renewalChunk.z}))"
            else
                "(latched; chunk renewal trigger conditions already satisfied)"

        transition(server, group, GroupState.FORGETTING, latchContext)
        markForget(group)

        executions[group.anchorName] =
            Execution(group, ArrayDeque(group.chunks))

        MementoDebug.info(
            server,
            "The land is being forgotten for witherstone '${group.anchorName}' " +
                "(dim=${group.dimension.value}, chunks=${group.chunks.size})"
        )
    }

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
        transition(server, group, GroupState.RENEWED, "(all chunks renewed)")
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
