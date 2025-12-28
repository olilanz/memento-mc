package ch.oliverlanz.memento.application.land

import ch.oliverlanz.memento.application.land.inspect.RenewalBatchView
import ch.oliverlanz.memento.application.land.inspect.RenewalBatchViewSnapshot

import ch.oliverlanz.memento.application.MementoStones
import ch.oliverlanz.memento.application.stone.StoneMaturityTrigger
import ch.oliverlanz.memento.domain.renewal.RenewalBatch
import ch.oliverlanz.memento.domain.renewal.RenewalBatchState
import ch.oliverlanz.memento.infrastructure.MementoDebug
import ch.oliverlanz.memento.application.stone.WitherstoneLifecycle
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkStatus

/**
 * Land / RenewalBatch lifecycle coordinator.
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
object RenewalBatchForgetting {

    data class PendingRenewal(
        val trigger: String,
        val observedChunk: ChunkPos,
        val observedGameTime: Long,
    )

    data class Execution(
        val renewalBatch: RenewalBatch,
        val remaining: MutableSet<ChunkPos>,
        val inFlight: MutableSet<ChunkPos>,
        val queuedGameTime: Long,
    )

    private val batchesByStoneName = linkedMapOf<String, RenewalBatch>()

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

    fun getBatchByStoneName(stoneName: String): RenewalBatch? = batchesByStoneName[stoneName]

    fun snapshotBatches(): List<RenewalBatchView> =
        batchesByStoneName.map { (name, batch) ->
            RenewalBatchViewSnapshot(
                name = name,
                dimension = batch.dimension,
                chunks = batch.chunks.toSet(),
                state = batch.state,
            )
        }

    /**
     * Commands operate stone-first and only carry the stone name.
     * If the same name exists across dimensions (shouldn't happen), discard all.
     */
    fun discardGroup(stoneName: String) {
        if (batchesByStoneName.remove(stoneName) == null) return

        // Remove any queued/active work for this stone across dimensions.
        pendingRenewals.keys.removeIf { it.endsWith("::$stoneName") }
        executionsByGroupKey.keys.removeIf { it.endsWith("::$stoneName") }
    }

private fun discardGroupInternal(stoneName: String) {
    val b = batchesByStoneName.remove(stoneName) ?: return
    val key = batchKey(b)

    pendingRenewals.remove(key)
    executionsByGroupKey.remove(key)
    lastUnloadObserved.remove(key)
}


    fun rebuildFromAnchors(
        server: MinecraftServer,
        stones: Map<String, MementoStones.Stone>,
        trigger: StoneMaturityTrigger
    ) {
        batchesByStoneName.clear()
        pendingRenewals.clear()
        executionsByGroupKey.clear()
        lastUnloadObserved.clear()

        val derived = deriveGroupsFromMaturedWitherstones(server, stones, trigger)
        derived.forEach { (name, b) ->
            batchesByStoneName[name] = b

            // Server start / maturity trigger should also trigger an initial readiness evaluation.
            // This is not a direct renewal action; it merely registers that a renewal is desired.
            pendingRenewals[batchKey(b)] = PendingRenewal(
                trigger = trigger.name,
                observedChunk = ChunkPos(b.stonePos),
                observedGameTime = server.overworld.time
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
        for (b in batchesByStoneName.values) {
            batchesByStoneName.values.forEach { batch -> refreshBatchReadiness(server, batch) }
        }
    }

    /**
     * Called when Minecraft unloads a chunk.
     *
     * NOTE: This never starts renewal. It only records the renewal trigger and updates
     * group readiness (BLOCKED/FREE).
     */
    fun onChunkUnloaded(server: MinecraftServer, world: ServerWorld, pos: ChunkPos) {
        val affected = batchesByStoneName.values.filter { b ->
            b.dimension == world.registryKey && b.state != RenewalBatchState.RENEWED && pos in b.chunks
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
            val key = groupKey(g.dimension, g.anchorName)
            pendingRenewals[key] = PendingRenewal(
                trigger = "CHUNK_UNLOAD",
                observedChunk = pos,
                observedGameTime = now,
            )
            refreshBatchReadiness(server, g)
        }
    }

    /**
     * Used by ChunkForgetPredicate (mixin path).
     * We only allow renewal when the chunk is currently in-flight, i.e. we initiated the load.
     */
    fun isChunkRenewalQueued(dimension: RegistryKey<World>, pos: ChunkPos): Boolean {
        return executionsByGroupKey.values.any { ex ->
            ex.renewalBatch.dimension == dimension && pos in ex.inFlight
        }
    }

    /**
     * Called by ChunkForgetPredicate whenever a chunk is being renewed.
     */
    fun onChunkRenewalObserved(server: MinecraftServer, dimension: RegistryKey<World>, pos: ChunkPos) {
        val matching = executionsByGroupKey.values.filter { ex ->
            ex.renewalBatch.dimension == dimension && (pos in ex.remaining || pos in ex.inFlight)
        }
        if (matching.isEmpty()) return

        for (ex in matching) {
            val wasRemaining = ex.remaining.remove(pos)
            ex.inFlight.remove(pos)

            if (wasRemaining) {
                val done = (ex.renewalBatch.chunks.size - ex.remaining.size)
                val total = ex.renewalBatch.chunks.size
                MementoDebug.info(server, "RenewalBatch '${ex.renewalBatch.anchorName}' renewed chunk (${pos.x},${pos.z}) ($done/$total)")
            }

            if (ex.remaining.isEmpty()) {
                val batch = ex.renewalBatch
                val key = batchKey(batch)
                executionsByGroupKey.remove(key)

                if (batch.state != RenewalBatchState.RENEWED) {
                    batchesByStoneName[batch.anchorName] = batch.copy(state = RenewalBatchState.RENEWED)
                    MementoDebug.info(
                        server,
                        "RenewalBatch '${batch.anchorName}' FORGETTING -> RENEWED (dim=${batch.dimension.value}, chunks=${batch.chunks.size})"
                    )

                    // Terminal action: consume the WITHERSTONE (one-shot) and discard the derived group.
                    WitherstoneLifecycle.onRenewalBatchRenewed(server, batch.anchorName)
                    discardGroupInternal(batch.anchorName)
                }
            }
        }
    }

    private fun maybeStartQueuedRenewals(server: MinecraftServer) {
        if (batchesByStoneName.isEmpty()) return

        for (b in batchesByStoneName.values) {
            val key = batchKey(b)
            val pending = pendingRenewals[key] ?: continue

            if (b.state != RenewalBatchState.FREE) continue

            // Extra guard: state FREE must match actual loaded-chunk reality.
            val loadedChunks = countLoadedChunks(server, b)
            if (loadedChunks != 0) continue

            // Do not start twice.
            if (executionsByGroupKey.containsKey(key)) {
                pendingRenewals.remove(key)
                continue
            }

            val updated = b.copy(state = RenewalBatchState.FORGETTING)
            batchesByStoneName[b.anchorName] = updated

            executionsByGroupKey[key] = Execution(
                renewalBatch = updated,
                remaining = updated.chunks.toMutableSet(),
                inFlight = linkedSetOf(),
                queuedGameTime = server.overworld.time,
            )

            pendingRenewals.remove(key)

            MementoDebug.info(
                server,
                "RenewalBatch '${updated.anchorName}' FREE -> FORGETTING (queued ${updated.chunks.size} chunk(s); trigger=${pending.trigger} chunk=(${pending.observedChunk.x},${pending.observedChunk.z}))"
            )
        }
    }

    private fun executeRenewalWork(server: MinecraftServer) {
        val executions = executionsByGroupKey.values.toList()
        if (executions.isEmpty()) return

        for (ex in executions) {
            val world = server.getWorld(ex.renewalBatch.dimension) ?: continue

            // Pick the next chunk that isn't already in-flight.
            val next = ex.remaining.firstOrNull { it !in ex.inFlight } ?: continue

            ex.inFlight.add(next)

            // This triggers a load. VersionedChunkStorageMixin consults ChunkForgetPredicate,
            // which will call onChunkRenewalObserved(...)
            world.chunkManager.getChunk(next.x, next.z, ChunkStatus.FULL, true)
        }
    }

    private fun refreshBatchReadiness(server: MinecraftServer, batch: RenewalBatch) {
        if (batch.state == RenewalBatchState.FORGETTING || batch.state == RenewalBatchState.RENEWED) return

        val loaded = countLoadedChunks(server, batch)
        val total = batch.chunks.size
        val newState = if (loaded == 0) RenewalBatchState.FREE else RenewalBatchState.BLOCKED

        if (newState != batch.state) {
            batchesByStoneName[batch.anchorName] = batch.copy(state = newState)
            MementoDebug.info(server, "RenewalBatch '${batch.anchorName}' ${batch.state} -> $newState (loadedChunks=$loaded/$total)")
        }
    }

    private fun countLoadedChunks(server: MinecraftServer, batch: RenewalBatch): Int {
        val world = server.getWorld(batch.dimension) ?: return 0
        return batch.chunks.count { pos ->
            world.chunkManager.getChunk(pos.x, pos.z, ChunkStatus.EMPTY, false) != null
        }
    }

    private fun deriveGroupsFromMaturedWitherstones(
        server: MinecraftServer,
        stones: Map<String, MementoStones.Stone>,
        trigger: StoneMaturityTrigger,
    ): Map<String, RenewalBatch> {
        val derived = linkedMapOf<String, RenewalBatch>()

        val matured = stones.values.filter { s ->
            s.kind == MementoStones.Kind.FORGET && s.state == MementoStones.WitherstoneState.MATURED
        }

        for (s in matured) {
            val chunks = MementoStones.computeChunksInRadius(s.pos, s.radius).toList()
            val b = RenewalBatch(
                anchorName = s.name,
                dimension = s.dimension,
                stonePos = s.pos,
                radiusChunks = s.radius,
                chunks = chunks.toList(),
                state = RenewalBatchState.MARKED,
            )

            derived[s.name] = b

            MementoDebug.info(
                server,
                "RenewalBatch '${b.anchorName}' derived due to stone maturity trigger: $trigger (state=${b.state}, dim=${b.dimension.value}, chunks=${b.chunks.size})"
            )

            // Evaluate readiness once, so /memento inspect is accurate right after trigger.
            refreshBatchReadiness(server, b)
        }

        return derived
    }

    private fun batchKey(batch: RenewalBatch): String = "${batch.dimension.value}::${batch.anchorName}"

    private fun groupKey(dimension: RegistryKey<World>, stoneName: String): String =
        "${dimension.value}::$stoneName"
}
