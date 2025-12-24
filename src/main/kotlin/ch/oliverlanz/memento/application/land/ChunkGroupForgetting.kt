package ch.oliverlanz.memento.application.land

import ch.oliverlanz.memento.application.MementoStones
import ch.oliverlanz.memento.application.stone.StoneMaturityTrigger
import ch.oliverlanz.memento.domain.land.ChunkGroup
import ch.oliverlanz.memento.domain.land.GroupState
import ch.oliverlanz.memento.infrastructure.MementoDebug
import ch.oliverlanz.memento.application.stone.WitherstoneLifecycle
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkStatus

/**
 * Land / ChunkGroup lifecycle coordinator.
 *
 * Responsibilities:
 * - derive chunk groups from matured witherstones (derived, not persisted)
 * - track readiness (BLOCKED/FREE) based on loaded chunks
 * - queue and execute renewal work in a paced loop
 *
 * Critical invariant:
 * CHUNK_UNLOAD must never cause a direct transition into renewal.
 * Unload events only record facts and trigger re-evaluation.
 */
object ChunkGroupForgetting {

    data class PendingRenewal(
        val trigger: String,
        val observedChunk: ChunkPos,
        val observedGameTime: Long,
    )

    data class Execution(
        val chunkGroup: ChunkGroup,
        val remaining: MutableSet<ChunkPos>,
        val inFlight: MutableSet<ChunkPos>,
        val queuedGameTime: Long,
    )

    private val groupsByStoneName = linkedMapOf<String, ChunkGroup>()

    // Key: "<dimId>::<stoneName>"
    private val pendingRenewals = linkedMapOf<String, PendingRenewal>()

    // Key: "<dimId>::<stoneName>"
    private val executionsByGroupKey = linkedMapOf<String, Execution>()

    // Debounce unload logs: Key "<dimId>::<cx>,<cz>" -> last observed game time
    private val lastUnloadObserved = linkedMapOf<String, Long>()

    // Pace proactive renewal (every N server ticks)
    private const val RENEWAL_INTERVAL_TICKS: Int = 5

    // A loose upper bound to avoid unbounded growth if unload fires repeatedly.
    private const val UNLOAD_DEBOUNCE_WINDOW_TICKS: Long = 5

    fun getGroupByStoneName(stoneName: String): ChunkGroup? = groupsByStoneName[stoneName]

    fun snapshotGroups(): List<ChunkGroup> = groupsByStoneName.values.toList()

    /**
     * Commands operate stone-first and only carry the stone name.
     * If the same name exists across dimensions (shouldn't happen), discard all.
     */
    fun discardGroup(stoneName: String) {
        if (groupsByStoneName.remove(stoneName) == null) return

        // Remove any queued/active work for this stone across dimensions.
        pendingRenewals.keys.removeIf { it.endsWith("::$stoneName") }
        executionsByGroupKey.keys.removeIf { it.endsWith("::$stoneName") }
    }

private fun discardGroupInternal(stoneName: String) {
    val g = groupsByStoneName.remove(stoneName) ?: return
    val key = groupKey(g)

    pendingRenewals.remove(key)
    executionsByGroupKey.remove(key)
    lastUnloadObserved.remove(key)
}


    fun rebuildFromAnchors(
        server: MinecraftServer,
        stones: Map<String, MementoStones.Stone>,
        trigger: StoneMaturityTrigger
    ) {
        groupsByStoneName.clear()
        pendingRenewals.clear()
        executionsByGroupKey.clear()
        lastUnloadObserved.clear()

        val derived = deriveGroupsFromMaturedWitherstones(server, stones, trigger)
        derived.forEach { (name, g) ->
            groupsByStoneName[name] = g

            // Server start / maturity trigger should also trigger an initial readiness evaluation.
            // This is not a direct renewal action; it merely registers that a renewal is desired.
            pendingRenewals[groupKey(g)] = PendingRenewal(
                trigger = trigger.name,
                observedChunk = ChunkPos(g.anchorPos),
                observedGameTime = server.overworld.time,
            )
        }

        if (derived.isNotEmpty()) {
            MementoDebug.info(server, "Rebuilt ${derived.size} chunk group(s) from matured witherstones")
        }
    }

    /**
     * Called by the stone lifecycle on SERVER_TICK.
     */
    fun tick(server: MinecraftServer) {
        // 1) Start renewal for groups that are confirmed FREE and have a pending renewal.
        maybeStartQueuedRenewals(server)

        // 2) Execute renewal work in a paced loop.
        if (server.ticks % RENEWAL_INTERVAL_TICKS != 0) return
        executeRenewalWork(server)
    }

    fun refreshAllReadiness(server: MinecraftServer) {
        for (g in groupsByStoneName.values) {
            refreshGroupReadiness(server, g)
        }
    }

    /**
     * Called when Minecraft unloads a chunk.
     *
     * NOTE: This never starts renewal. It only records the renewal trigger and updates
     * group readiness (BLOCKED/FREE).
     */
    fun onChunkUnloaded(server: MinecraftServer, world: ServerWorld, pos: ChunkPos) {
        val affected = groupsByStoneName.values.filter { g ->
            g.dimension == world.registryKey && g.state != GroupState.RENEWED && pos in g.chunks
        }
        if (affected.isEmpty()) return

        val dimId = world.registryKey.value
        val debounceKey = "$dimId::${pos.x},${pos.z}"
        val now = world.time
        val last = lastUnloadObserved[debounceKey]

        if (last == null || (now - last) > UNLOAD_DEBOUNCE_WINDOW_TICKS) {
            MementoDebug.info(
                server,
                "Chunk renewal trigger observed: CHUNK_UNLOAD (dim=$dimId, chunk=(${pos.x},${pos.z})) -> re-evaluating ${affected.size} group(s)"
            )
            lastUnloadObserved[debounceKey] = now
        }

        for (g in affected) {
            val key = groupKey(g)
            pendingRenewals[key] = PendingRenewal(
                trigger = "CHUNK_UNLOAD",
                observedChunk = pos,
                observedGameTime = now,
            )
            refreshGroupReadiness(server, g)
        }
    }

    /**
     * Used by ChunkForgetPredicate (mixin path).
     * We only allow renewal when the chunk is currently in-flight, i.e. we initiated the load.
     */
    fun isChunkRenewalQueued(dimension: RegistryKey<World>, pos: ChunkPos): Boolean {
        return executionsByGroupKey.values.any { ex ->
            ex.chunkGroup.dimension == dimension && pos in ex.inFlight
        }
    }

    /**
     * Called by ChunkForgetPredicate whenever a chunk is being renewed.
     */
    fun onChunkRenewalObserved(server: MinecraftServer, dimension: RegistryKey<World>, pos: ChunkPos) {
        val matching = executionsByGroupKey.values.filter { ex ->
            ex.chunkGroup.dimension == dimension && (pos in ex.remaining || pos in ex.inFlight)
        }
        if (matching.isEmpty()) return

        for (ex in matching) {
            val wasRemaining = ex.remaining.remove(pos)
            ex.inFlight.remove(pos)

            if (wasRemaining) {
                val done = (ex.chunkGroup.chunks.size - ex.remaining.size)
                val total = ex.chunkGroup.chunks.size
                MementoDebug.info(server, "ChunkGroup '${ex.chunkGroup.anchorName}' renewed chunk (${pos.x},${pos.z}) ($done/$total)")
            }

            if (ex.remaining.isEmpty()) {
                val group = ex.chunkGroup
                val key = groupKey(group)
                executionsByGroupKey.remove(key)

                if (group.state != GroupState.RENEWED) {
                    groupsByStoneName[group.anchorName] = group.copy(state = GroupState.RENEWED)
                    MementoDebug.info(
                        server,
                        "ChunkGroup '${group.anchorName}' FORGETTING -> RENEWED (dim=${group.dimension.value}, chunks=${group.chunks.size})"
                    )

                    // Terminal action: consume the WITHERSTONE (one-shot) and discard the derived group.
                    WitherstoneLifecycle.onChunkGroupRenewed(server, group.anchorName)
                    discardGroupInternal(group.anchorName)
                }
            }
        }
    }

    private fun maybeStartQueuedRenewals(server: MinecraftServer) {
        if (groupsByStoneName.isEmpty()) return

        for (g in groupsByStoneName.values) {
            val key = groupKey(g)
            val pending = pendingRenewals[key] ?: continue

            if (g.state != GroupState.FREE) continue

            // Extra guard: state FREE must match actual loaded-chunk reality.
            val loadedChunks = countLoadedChunks(server, g)
            if (loadedChunks != 0) continue

            // Do not start twice.
            if (executionsByGroupKey.containsKey(key)) {
                pendingRenewals.remove(key)
                continue
            }

            val updated = g.copy(state = GroupState.FORGETTING)
            groupsByStoneName[g.anchorName] = updated

            executionsByGroupKey[key] = Execution(
                chunkGroup = updated,
                remaining = updated.chunks.toMutableSet(),
                inFlight = linkedSetOf(),
                queuedGameTime = server.overworld.time,
            )

            pendingRenewals.remove(key)

            MementoDebug.info(
                server,
                "ChunkGroup '${updated.anchorName}' FREE -> FORGETTING (queued ${updated.chunks.size} chunk(s); trigger=${pending.trigger} chunk=(${pending.observedChunk.x},${pending.observedChunk.z}))"
            )
        }
    }

    private fun executeRenewalWork(server: MinecraftServer) {
        val executions = executionsByGroupKey.values.toList()
        if (executions.isEmpty()) return

        for (ex in executions) {
            val world = server.getWorld(ex.chunkGroup.dimension) ?: continue

            // Pick the next chunk that isn't already in-flight.
            val next = ex.remaining.firstOrNull { it !in ex.inFlight } ?: continue

            ex.inFlight.add(next)

            // This triggers a load. VersionedChunkStorageMixin consults ChunkForgetPredicate,
            // which will call onChunkRenewalObserved(...)
            world.chunkManager.getChunk(next.x, next.z, ChunkStatus.FULL, true)
        }
    }

    private fun refreshGroupReadiness(server: MinecraftServer, group: ChunkGroup) {
        if (group.state == GroupState.FORGETTING || group.state == GroupState.RENEWED) return

        val loaded = countLoadedChunks(server, group)
        val total = group.chunks.size
        val newState = if (loaded == 0) GroupState.FREE else GroupState.BLOCKED

        if (newState != group.state) {
            groupsByStoneName[group.anchorName] = group.copy(state = newState)
            MementoDebug.info(server, "ChunkGroup '${group.anchorName}' ${group.state} -> $newState (loadedChunks=$loaded/$total)")
        }
    }

    private fun countLoadedChunks(server: MinecraftServer, group: ChunkGroup): Int {
        val world = server.getWorld(group.dimension) ?: return 0
        return group.chunks.count { pos ->
            world.chunkManager.getChunk(pos.x, pos.z, ChunkStatus.EMPTY, false) != null
        }
    }

    private fun deriveGroupsFromMaturedWitherstones(
        server: MinecraftServer,
        stones: Map<String, MementoStones.Stone>,
        trigger: StoneMaturityTrigger,
    ): Map<String, ChunkGroup> {
        val derived = linkedMapOf<String, ChunkGroup>()

        val matured = stones.values.filter { s ->
            s.kind == MementoStones.Kind.FORGET && s.state == MementoStones.WitherstoneState.MATURED
        }

        for (s in matured) {
            val chunks = MementoStones.computeChunksInRadius(s.pos, s.radius).toList()
            val g = ChunkGroup(
                anchorName = s.name,
                dimension = s.dimension,
                anchorPos = s.pos,
                radiusChunks = s.radius,
                chunks = chunks.toList(),
                state = GroupState.MARKED,
            )

            derived[s.name] = g

            MementoDebug.info(
                server,
                "ChunkGroup '${g.anchorName}' derived due to stone maturity trigger: $trigger (state=${g.state}, dim=${g.dimension.value}, chunks=${g.chunks.size})"
            )

            // Evaluate readiness once, so /memento inspect is accurate right after trigger.
            refreshGroupReadiness(server, g)
        }

        return derived
    }

    private fun groupKey(group: ChunkGroup): String = groupKey(group.dimension, group.anchorName)

    private fun groupKey(dimension: RegistryKey<World>, stoneName: String): String =
        "${dimension.value}::$stoneName"
}
