package ch.oliverlanz.memento.domain.renewal

import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkStatus
import org.slf4j.LoggerFactory

/**
 * SHADOW / OBSERVABILITY ONLY.
 *
 * Tracks derived RenewalBatches and mirrors (observes) legacy state transitions.
 * It does not control execution, persistence, or authoritative decisions.
 */
object RenewalTracker {

    private val log = LoggerFactory.getLogger("memento")

    /**
     * Minimal descriptor coming from the authoritative stone layer.
     * Caller precomputes chunk coverage to avoid duplicating radius semantics here.
     */
    data class MaturedWitherstone(
        val name: String,
        val dimension: RegistryKey<World>,
        val pos: BlockPos,
        val radiusChunks: Int,
        val chunks: List<ChunkPos>,
    )

    // Keyed by "${dimension.value}::${anchorName}"
    private val batchesByKey = linkedMapOf<String, RenewalBatch>()

    // Chunk -> set of batchKeys (dimension-aware)
    private val batchKeysByChunkKey = mutableMapOf<String, MutableSet<String>>()

    init {
        log.info("[RENEWAL-TRACKER] initialized (shadow, empty)")
    }

    // ---------------------------------------------------------------------
    // Rebuild / Derivation (shadow)
    // ---------------------------------------------------------------------

    fun rebuildFromMaturedWitherstones(
        server: MinecraftServer,
        stones: List<MaturedWitherstone>,
        trigger: String,
    ) {
        clear()

        if (stones.isEmpty()) {
            log.info("[RENEWAL-TRACKER] rebuild trigger=$trigger -> 0 matured witherstones -> 0 batches")
            return
        }

        for (s in stones) {
            val batch = RenewalBatch(
                anchorName = s.name,
                dimension = s.dimension,
                stonePos = s.pos,
                radiusChunks = s.radiusChunks,
                chunks = s.chunks,
                state = RenewalBatchState.MARKED,
            )

            val key = batchKey(batch.dimension, batch.anchorName)
            batchesByKey[key] = batch
            indexBatch(batch)

            log.info(
                "[RENEWAL-TRACKER] derived batch '${batch.anchorName}' trigger=$trigger state=${batch.state} dim=${batch.dimension.value} chunks=${batch.chunks.size}"
            )

            // Evaluate readiness once so state is meaningful immediately.
            refreshBatchReadiness(server, key)
        }

        log.info("[RENEWAL-TRACKER] rebuild trigger=$trigger -> ${batchesByKey.size} batch(es)")
    }

    private fun clear() {
        batchesByKey.clear()
        batchKeysByChunkKey.clear()
    }

    // ---------------------------------------------------------------------
    // Observing legacy transitions (shadow mirror)
    // ---------------------------------------------------------------------

    fun observeLegacyStateTransition(
        server: MinecraftServer,
        dimension: RegistryKey<World>,
        batchName: String,
        from: RenewalBatchState,
        to: RenewalBatchState,
        detail: String? = null,
    ) {
        val key = batchKey(dimension, batchName)
        val current = batchesByKey[key]

        if (current == null) {
            // Shadow may be behind if rebuild wasn't called yet; don't crash.
            val suffix = detail?.let { " ($it)" } ?: ""
            log.info("[RENEWAL-TRACKER] legacy transition observed for unknown batch '$batchName' $from -> $to$suffix")
            return
        }

        if (current.state == to) return

        val updated = current.copy(state = to)
        batchesByKey[key] = updated

        val suffix = detail?.let { " ($it)" } ?: ""
        log.info("[RENEWAL-TRACKER] batch '${batchName}' $from -> $to$suffix")
    }

    fun observeLegacyBatchCompleted(
        server: MinecraftServer,
        dimension: RegistryKey<World>,
        batchName: String,
        detail: String? = null,
    ) {
        val key = batchKey(dimension, batchName)
        val current = batchesByKey[key] ?: return

        // Keep a final “completed” log, then remove.
        val suffix = detail?.let { " ($it)" } ?: ""
        log.info("[RENEWAL-TRACKER] remove batch '${current.anchorName}' reason=COMPLETED$suffix")

        removeBatch(key, current)
    }

    // ---------------------------------------------------------------------
    // Chunk event observability (filtered)
    // ---------------------------------------------------------------------

    fun onChunkUnloadedObserved(
        server: MinecraftServer,
        dimension: RegistryKey<World>,
        chunk: ChunkPos,
        gameTime: Long,
    ) {
        val affected = affectedBatchKeys(dimension, chunk)
        if (affected.isEmpty()) return

        // Only log unload when it matters for readiness (MARKED/BLOCKED/FREE) or execution (FORGETTING)
        val names = affected.mapNotNull { batchesByKey[it]?.anchorName }.distinct()
        log.info(
            "[RENEWAL-TRACKER] trigger=CHUNK_UNLOAD dim=${dimension.value} chunk=(${chunk.x},${chunk.z}) time=$gameTime affects=${names.joinToString(",")}"
        )

        // Refresh readiness for those batches (mirrors MARKED/BLOCKED/FREE changes).
        for (key in affected) {
            refreshBatchReadiness(server, key)
        }
    }

    fun onChunkLoadedObserved(
        server: MinecraftServer,
        dimension: RegistryKey<World>,
        chunk: ChunkPos,
        gameTime: Long,
    ) {
        val affected = affectedBatchKeys(dimension, chunk)
        if (affected.isEmpty()) return

        // Only log loads that are relevant to proactive renewal (FORGETTING), to keep noise down.
        val forgetting = affected
            .mapNotNull { batchesByKey[it] }
            .filter { it.state == RenewalBatchState.FORGETTING }
            .map { it.anchorName }
            .distinct()

        if (forgetting.isEmpty()) return

        log.info(
            "[RENEWAL-TRACKER] trigger=CHUNK_LOAD dim=${dimension.value} chunk=(${chunk.x},${chunk.z}) time=$gameTime forgetting=${forgetting.joinToString(",")}"
        )
    }

    // ---------------------------------------------------------------------
    // Inspection (for future /memento inspect/list shadow)
    // ---------------------------------------------------------------------

    fun listBatches(): List<RenewalBatch> = batchesByKey.values.toList()

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    private fun batchKey(dimension: RegistryKey<World>, batchName: String): String =
        "${dimension.value}::$batchName"

    private fun chunkKey(dimension: RegistryKey<World>, chunk: ChunkPos): String =
        "${dimension.value}::${chunk.x},${chunk.z}"

    private fun indexBatch(batch: RenewalBatch) {
        val bKey = batchKey(batch.dimension, batch.anchorName)
        for (c in batch.chunks) {
            val ck = chunkKey(batch.dimension, c)
            batchKeysByChunkKey.getOrPut(ck) { linkedSetOf() }.add(bKey)
        }
    }

    private fun removeBatch(batchKey: String, batch: RenewalBatch) {
        batchesByKey.remove(batchKey)
        for (c in batch.chunks) {
            val ck = chunkKey(batch.dimension, c)
            val set = batchKeysByChunkKey[ck] ?: continue
            set.remove(batchKey)
            if (set.isEmpty()) batchKeysByChunkKey.remove(ck)
        }
    }

    private fun affectedBatchKeys(dimension: RegistryKey<World>, chunk: ChunkPos): Set<String> {
        val ck = chunkKey(dimension, chunk)
        return batchKeysByChunkKey[ck]?.toSet() ?: emptySet()
    }

    private fun refreshBatchReadiness(server: MinecraftServer, key: String) {
        val batch = batchesByKey[key] ?: return

        // If already running or done, readiness isn't relevant.
        if (batch.state == RenewalBatchState.FORGETTING || batch.state == RenewalBatchState.RENEWED) return

        val total = batch.chunks.size
        val loaded = countLoadedChunks(server, batch)

        val newState = if (loaded == 0) RenewalBatchState.FREE else RenewalBatchState.BLOCKED

        if (newState != batch.state) {
            batchesByKey[key] = batch.copy(state = newState)
            log.info(
                "[RENEWAL-TRACKER] batch '${batch.anchorName}' ${batch.state} -> $newState (loadedChunks=$loaded/$total)"
            )
        }
    }

    private fun countLoadedChunks(server: MinecraftServer, batch: RenewalBatch): Int {
        val world = server.getWorld(batch.dimension) ?: return 0
        return batch.chunks.count { pos ->
            world.chunkManager.getChunk(pos.x, pos.z, ChunkStatus.EMPTY, false) != null
        }
    }
}
