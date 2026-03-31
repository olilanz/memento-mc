package ch.oliverlanz.memento.infrastructure.ambient

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkMetadataFact
import ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance
import ch.oliverlanz.memento.domain.worldmap.ChunkSignals
import ch.oliverlanz.memento.domain.worldmap.WorldMapService
import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos

/**
 * Ambient metadata enrichment and freshness correction boundary.
 *
 * This component never requests chunk loads. It only consumes load lifecycle signals and performs
 * bounded best-effort enrichment on tick thread.
 */
class AmbientIngestionService(
    private val worldMapService: WorldMapService,
) : ChunkAvailabilityListener {

    private var server: MinecraftServer? = null
    private val tracker = LoadedChunkTracker()
    private var cursorLastKey: LoadedChunkKey? = null

    fun attach(server: MinecraftServer) {
        this.server = server
        tracker.clear()
        cursorLastKey = null
    }

    fun detach() {
        server = null
        tracker.clear()
        cursorLastKey = null
    }

    override fun onChunkLoaded(world: ServerWorld, pos: ChunkPos) {
        tracker.onLoaded(LoadedChunkKey(world.registryKey, pos.x, pos.z))
    }

    override fun onChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
        val ref = LoadedChunkKey(world.registryKey, pos.x, pos.z)
        tracker.onUnloaded(ref)
        if (!MementoConstants.AMBIENT_INGESTION_WRITES_ENABLED) return

        // Best-effort only at unload time.
        val chunk = world.chunkManager.getWorldChunk(pos.x, pos.z, false) ?: return
        applyAmbientObservation(world = world, pos = chunk.pos, inhabitedTicks = chunk.inhabitedTime)
    }

    fun onVeryLowPulse() {
        if (!MementoConstants.AMBIENT_INGESTION_WRITES_ENABLED) return
        val srv = server ?: return
        val ordered = tracker.snapshotOrdered()
        if (ordered.isEmpty()) return

        val start = tracker.indexAfter(cursorLastKey, ordered)
        val seekMax = minOf(MementoConstants.AMBIENT_FRESHNESS_SEARCH_WINDOW_MAX, ordered.size)

        var staleIndex = -1
        var seekVisited = 0
        while (seekVisited < seekMax) {
            val idx = (start + seekVisited) % ordered.size
            val ref = ordered[idx]
            if (isStale(ref, srv)) {
                staleIndex = idx
                break
            }
            seekVisited++
        }

        if (staleIndex < 0) {
            if (seekMax > 0) {
                val cursorIndex = (start + seekMax - 1) % ordered.size
                cursorLastKey = ordered[cursorIndex]
            }
            return
        }

        val batch = minOf(MementoConstants.AMBIENT_FRESHNESS_BATCH_WINDOW, ordered.size)
        var updates = 0
        var traversed = 0
        while (traversed < batch) {
            val idx = (staleIndex + traversed) % ordered.size
            val ref = ordered[idx]
            if (updates < MementoConstants.AMBIENT_FRESHNESS_MAX_UPDATES_PER_PULSE && isStale(ref, srv)) {
                val world = srv.getWorld(ref.dimension)
                val chunk = world?.chunkManager?.getWorldChunk(ref.x, ref.z, false)
                if (world != null && chunk != null) {
                    val applied = applyAmbientObservation(world = world, pos = chunk.pos, inhabitedTicks = chunk.inhabitedTime)
                    if (applied) updates++
                }
            }
            traversed++
        }

        cursorLastKey = ordered[(staleIndex + batch - 1) % ordered.size]

        MementoLog.debug(
            MementoConcept.WORLD,
            "ambient freshness pulse loaded={} seekVisited={} batchTraversed={} updates={} cursorSet={}",
            tracker.size(),
            seekVisited,
            batch,
            updates,
            cursorLastKey != null,
        )
    }

    private fun isStale(ref: LoadedChunkKey, server: MinecraftServer): Boolean {
        val world = server.getWorld(ref.dimension) ?: return false
        val key = toChunkKey(world, ChunkPos(ref.x, ref.z))
        val existing = worldMapService.substrate().scannedEntry(key) ?: return true
        return (world.time - existing.scanTick) > MementoConstants.AMBIENT_FRESHNESS_STALE_AFTER_TICKS
    }

    private fun applyAmbientObservation(world: ServerWorld, pos: ChunkPos, inhabitedTicks: Long): Boolean {
        val fact = ChunkMetadataFact(
            key = toChunkKey(world, pos),
            source = ChunkScanProvenance.ENGINE_AMBIENT,
            unresolvedReason = null,
            signals = ChunkSignals(
                inhabitedTimeTicks = inhabitedTicks,
                lastUpdateTicks = null,
                surfaceY = null,
                biomeId = null,
                isSpawnChunk = false,
            ),
            scanTick = world.time,
        )
        return worldMapService.applyAmbientFactOnTickThread(fact)
    }

    private fun toChunkKey(world: ServerWorld, pos: ChunkPos): ChunkKey =
        ChunkKey(
            world = world.registryKey,
            regionX = Math.floorDiv(pos.x, 32),
            regionZ = Math.floorDiv(pos.z, 32),
            chunkX = pos.x,
            chunkZ = pos.z,
        )
}
