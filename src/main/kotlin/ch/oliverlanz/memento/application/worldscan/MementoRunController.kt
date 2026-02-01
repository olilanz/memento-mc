package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.WorldMementoMap
import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadProvider
import ch.oliverlanz.memento.infrastructure.chunk.ChunkRef
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * /memento run controller.
 *
 * Semantics:
 * - `/memento run` toggles the scanner into *proactive* mode.
 * - While proactive, the scanner proposes chunks to the driver via [desiredChunks].
 * - The scanner is otherwise passive (reactive): it updates the in-memory map on unsolicited chunk loads.
 * - Completion is defined as the moment proactive transitions true -> false due to exhaustion.
 *   That edge triggers CSV export and completion logging.
 *
 * Driver mechanics (tickets, throttling, priority arbitration) are owned by the infrastructure ChunkLoadDriver.
 */
class MementoRunController : ChunkLoadProvider, ChunkAvailabilityListener {

    private val log = LoggerFactory.getLogger("memento")

    private var server: MinecraftServer? = null

    /**
     * Scanner mode:
     * - proactive=true  -> propose chunks to the driver
     * - proactive=false -> passive/reactive only
     */
    private val proactive = AtomicBoolean(false)

    /** The world map (single source of truth). Kept in memory across proactive runs. */
    private var map: WorldMementoMap? = null

    private var consumer: ChunkMetadataConsumer? = null

    /** Observability only. */
    private var plannedChunks: Int = 0

    /** True after a proactive run has written a CSV snapshot. */
    private var csvWrittenForCurrentRun: Boolean = false

    /**
     * Cache of chunks most recently proposed to the driver.
     *
     * Purpose:
     * - Avoid recomputing the desired chunk slice on every provider pull.
     * - Allow event-driven invalidation only when a *requested* chunk becomes accessible.
     */
    private var cachedProposed: List<ChunkRef> = emptyList()
    private val requestedKeys: LinkedHashSet<ChunkKey> = LinkedHashSet()

    fun attach(server: MinecraftServer) {
        this.server = server
    }

    fun detach() {
        server = null
        proactive.set(false)
        map = null
        consumer = null
        plannedChunks = 0
        csvWrittenForCurrentRun = false
        cachedProposed = emptyList()
        requestedKeys.clear()
    }

    /** Entry point used by CommandHandlers. */
    fun start(source: ServerCommandSource): Int {
        val srv = source.server
        this.server = srv

        if (proactive.get()) {
            source.sendError(Text.literal("Memento: scanner is already proactive."))
            return 0
        }

        // (Re)discover existing chunks and ensure they exist in the in-memory map.
        val scanMap = map ?: WorldMementoMap().also { map = it }
        val worlds = WorldDiscovery().discover(srv)
        val discoveredRegions = RegionDiscovery().discover(srv, worlds)
        val discoveredChunks = ChunkDiscovery().discover(discoveredRegions)

        var ensured = 0
        discoveredChunks.worlds.forEach { world ->
            world.regions.forEach { region ->
                region.chunks.forEach { slot ->
                    val chunkX = region.x * 32 + slot.localX
                    val chunkZ = region.z * 32 + slot.localZ
                    val key = ChunkKey(
                        world = world.world,
                        regionX = region.x,
                        regionZ = region.z,
                        chunkX = chunkX,
                        chunkZ = chunkZ,
                    )
                    scanMap.ensureExists(key)
                    ensured++
                }
            }
        }

        if (ensured == 0 && scanMap.totalChunks() == 0) {
            source.sendFeedback(
                { Text.literal("Memento: no existing chunks discovered; nothing to scan.") },
                false
            )
            log.debug("[SCAN] proactive=false start aborted reason=no_chunks")
            return 1
        }

        // Ensure a consumer exists (passive mode also relies on it).
        if (consumer == null || map !== scanMap) {
            consumer = ChunkMetadataConsumer(scanMap)
        }

        plannedChunks = scanMap.totalChunks()
        csvWrittenForCurrentRun = false
        cachedProposed = emptyList()
        requestedKeys.clear()

        // If already complete, we can export immediately without going proactive.
        if (scanMap.isComplete()) {
            val topology = StoneInfluenceSuperposition.apply(scanMap)
            val path = MementoCsvWriter.write(srv, topology)
            csvWrittenForCurrentRun = true
            log.debug(
                "[SCAN] proactive=false already_complete plannedChunks={} scannedChunks={} csv={}",
                plannedChunks,
                scanMap.scannedChunks(),
                path.toAbsolutePath(),
            )
            source.sendFeedback({ Text.literal("Memento: scan already complete. CSV exported.") }, false)
            return 1
        }

        proactive.set(true)
        log.debug(
            "[SCAN] proactive=true started worlds={} plannedChunks={} scannedChunks={} missing={}",
            worlds.size,
            plannedChunks,
            scanMap.scannedChunks(),
            scanMap.missingCount(),
        )
        source.sendFeedback(
            { Text.literal("Memento: scan started. Planned chunks: $plannedChunks") },
            false
        )
        return 1
    }

    override fun desiredChunks(): Sequence<ChunkRef> {
        if (!proactive.get()) return emptySequence()
        val m = map ?: return emptySequence()

        // If we have a cached proposal, reuse it.
        if (cachedProposed.isNotEmpty()) {
            return cachedProposed.asSequence()
        }

        // Compute a fresh proposal.
        val missing = m.missingSignals(limit = 100)
        if (missing.isEmpty()) {
            // If we have exhausted intent, complete the proactive run.
            completeIfExhausted(m, reason = "exhausted")
            return emptySequence()
        }

        requestedKeys.clear()
        requestedKeys.addAll(missing)

        cachedProposed = missing
            .map { key -> ChunkRef(key.world, ChunkPos(key.chunkX, key.chunkZ)) }

        return cachedProposed.asSequence()
    }

    override fun onChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        val m = map
        if (m == null) {
            // No map yet: ignore (should not happen during normal use).
            return
        }

        // Always consume metadata into the map (passive/reactive behavior).
        consumer?.onChunkLoaded(world, chunk)

        // If not proactive, nothing else to do.
        if (!proactive.get()) return

        // Only reconsider scan selection if this chunk was explicitly requested.
        val key = ChunkKey(
            world = world.registryKey,
            regionX = Math.floorDiv(chunk.pos.x, 32),
            regionZ = Math.floorDiv(chunk.pos.z, 32),
            chunkX = chunk.pos.x,
            chunkZ = chunk.pos.z,
        )

        if (!requestedKeys.contains(key)) {
            return
        }

        // Invalidate cached proposal (we got feedback for requested work).
        cachedProposed = emptyList()
        requestedKeys.remove(key)

        // Completion check: if the map is now complete, stop being proactive and export.
        if (m.isComplete()) {
            completeIfExhausted(m, reason = "complete")
        }
    }

    private fun completeIfExhausted(m: WorldMementoMap, reason: String) {
        if (!proactive.compareAndSet(true, false)) {
            return
        }

        // Clear proposal caches immediately.
        cachedProposed = emptyList()
        requestedKeys.clear()

        val srv = server
        if (srv == null) {
            log.warn("[SCAN] proactive=false completed reason={} but server=null; csv skipped", reason)
            return
        }

        if (!csvWrittenForCurrentRun) {
            val topology = StoneInfluenceSuperposition.apply(m)
            val path = MementoCsvWriter.write(srv, topology)
            csvWrittenForCurrentRun = true
            log.debug(
                "[SCAN] proactive=false completed reason={} plannedChunks={} scannedChunks={} missing={} csv={}",
                reason,
                plannedChunks,
                m.scannedChunks(),
                m.missingCount(),
                path.toAbsolutePath(),
            )
        } else {
            log.debug(
                "[SCAN] proactive=false completed reason={} plannedChunks={} scannedChunks={} missing={} csv=already_written",
                reason,
                plannedChunks,
                m.scannedChunks(),
                m.missingCount(),
            )
        }
    }
}
