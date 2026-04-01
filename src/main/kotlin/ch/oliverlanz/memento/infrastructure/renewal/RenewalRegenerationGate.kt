
package ch.oliverlanz.memento.infrastructure.renewal

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.WorldMapService
import ch.oliverlanz.memento.domain.renewal.BatchCompleted
import ch.oliverlanz.memento.domain.renewal.BatchRemoved
import ch.oliverlanz.memento.domain.renewal.BatchWaitingForRenewal
import ch.oliverlanz.memento.domain.renewal.ChunkObserved
import ch.oliverlanz.memento.domain.renewal.RenewalBatchState
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.renewal.RenewalTrigger
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Infrastructure bridge between:
 *  - RenewalTracker's observed lifecycle (domain events), and
 *  - the mixin that intercepts chunk NBT reads.
 *
 * Threading model:
 *  - onRenewalEvent(...) and tickThreadProcess() are called on the server tick thread
 *  - shouldRegenerate(...) and recordRegenTriggered(...) may be called off-thread
 *
 * IMPORTANT:
 *  - No domain state is advanced off-thread
 *  - Regeneration is acknowledged only via tickThreadProcess()
 */
object RenewalRegenerationGate {

    private const val REGEN_ACK_SAMPLE_SIZE = 5

    /** dimension-id -> (chunkLong -> true) */
    private val pendingByDimension: ConcurrentHashMap<String, ConcurrentHashMap<Long, Boolean>> =
        ConcurrentHashMap()

    /** batchName -> (dimension-id, chunkLongs) */
    private val batchIndex: ConcurrentHashMap<String, Pair<String, Set<Long>>> =
        ConcurrentHashMap()

    /** Off-thread recorded facts: regeneration was actually triggered */
    private val regenTriggered: MutableSet<Pair<String, Long>> =
        ConcurrentHashMap.newKeySet()

    @Volatile private var worldMapService: WorldMapService? = null

    fun attachWorldMapService(service: WorldMapService) {
        worldMapService = service
    }

    fun clear() {
        pendingByDimension.clear()
        batchIndex.clear()
        regenTriggered.clear()
        worldMapService = null
    }

    fun onRenewalEvent(e: RenewalEvent) {
        when (e) {
            is BatchWaitingForRenewal -> onBatchWaitingForRenewal(e)
            is ChunkObserved -> onChunkObserved(e)
            is BatchCompleted -> onBatchTerminal(e.batchName)
            is BatchRemoved -> onBatchTerminal(e.batchName)
            else -> Unit
        }
    }

    /**
     * Called from the mixin (possibly off-thread).
     * Pure check only.
     */
    fun shouldRegenerate(dimension: RegistryKey<World>, pos: ChunkPos): Boolean {
        val dim = dimension.value.toString()
        val map = pendingByDimension[dim] ?: return false
        return map.containsKey(pos.toLong())
    }

    /**
     * Called from the mixin (possibly off-thread) exactly when regeneration is triggered.
     */
    fun recordRegenTriggered(dimension: RegistryKey<World>, pos: ChunkPos) {
        regenTriggered.add(dimension.value.toString() to pos.toLong())
    }

    /**
     * Server tick thread only.
     *
     * Drains regeneration evidence and acknowledges it against the pending index.
     * This is the ONLY place where renewal is advanced based on regeneration.
     */
    fun tickThreadProcess() {
        if (regenTriggered.isEmpty()) return

        val snapshot = regenTriggered.toSet()
        regenTriggered.removeAll(snapshot)

        val acknowledgedByDimension = linkedMapOf<String, MutableList<Long>>()
        var staleMarksAppliedTotal = 0

        for ((dim, chunkLong) in snapshot) {
            pendingByDimension[dim]?.remove(chunkLong)
            if (pendingByDimension[dim]?.isEmpty() == true) {
                pendingByDimension.remove(dim)
            }

            acknowledgedByDimension.getOrPut(dim) { mutableListOf() }.add(chunkLong)
        }

        acknowledgedByDimension.forEach { (dim, acknowledgedChunks) ->
            val staleMarksApplied = applyStaleMarks(dim = dim, acknowledgedChunks = acknowledgedChunks)
            staleMarksAppliedTotal += staleMarksApplied
            val sample = acknowledgedChunks
                .asSequence()
                .distinct()
                .sorted()
                .take(REGEN_ACK_SAMPLE_SIZE)
                .joinToString(prefix = "[", postfix = "]", separator = ",")

            MementoLog.debug(
                MementoConcept.RENEWAL,
                "regeneration acknowledged dim='{}' chunks={} sampleChunkIds={}",
                dim,
                acknowledgedChunks.size,
                sample,
            )

            if (staleMarksApplied > 0) {
                MementoLog.debug(
                    MementoConcept.RENEWAL,
                    "regeneration stale-mark applied dim='{}' chunks={} staleMarks={}",
                    dim,
                    acknowledgedChunks.size,
                    staleMarksApplied,
                )
            }
        }

        MementoLog.debug(
            MementoConcept.RENEWAL,
            "staleMark applied count={}",
            staleMarksAppliedTotal,
        )
    }

    private fun applyStaleMarks(dim: String, acknowledgedChunks: List<Long>): Int {
        val service = worldMapService ?: return 0
        val worldKey = runCatching {
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of(dim))
        }.getOrNull() ?: return 0

        // Force old-enough observation tick; keeps factual signals untouched.
        val staleScanTick = -1L - MementoConstants.AMBIENT_FRESHNESS_STALE_AFTER_TICKS
        var applied = 0
        for (chunkLong in acknowledgedChunks) {
            val pos = ChunkPos(chunkLong)
            val key =
                ChunkKey(
                    world = worldKey,
                    regionX = Math.floorDiv(pos.x, 32),
                    regionZ = Math.floorDiv(pos.z, 32),
                    chunkX = pos.x,
                    chunkZ = pos.z,
                )
            if (service.forceStaleObservationTickOnTickThread(key = key, staleScanTick = staleScanTick)) {
                applied++
            }
        }
        return applied
    }

    private fun onBatchWaitingForRenewal(e: BatchWaitingForRenewal) {
        val dim = e.dimension.value.toString()
        val chunkLongs = e.chunks.map { it.toLong() }.toSet()

        onBatchTerminal(e.batchName)

        batchIndex[e.batchName] = dim to chunkLongs
        val dimMap = pendingByDimension.computeIfAbsent(dim) { ConcurrentHashMap() }
        for (l in chunkLongs) dimMap[l] = true

        MementoLog.debug(
            MementoConcept.RENEWAL,
            "regeneration armed batch='{}' dim='{}' chunks={}",
            e.batchName, dim, chunkLongs.size
        )
    }

    /**
     * NOTE:
     * CHUNK_LOAD is no longer treated as regeneration evidence.
     */
    private fun onChunkObserved(e: ChunkObserved) {
        if (e.trigger != RenewalTrigger.CHUNK_LOAD) return
        if (e.state != RenewalBatchState.WAITING_FOR_RENEWAL) return
        // intentionally no-op
    }

    private fun onBatchTerminal(batchName: String) {
        val idx = batchIndex.remove(batchName) ?: return
        val dim = idx.first
        val chunkLongs = idx.second

        val dimMap = pendingByDimension[dim] ?: return
        for (l in chunkLongs) dimMap.remove(l)
        if (dimMap.isEmpty()) pendingByDimension.remove(dim)
    }
}
