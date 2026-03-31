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
    companion object {
        private const val AMBIENT_UPDATE_LOG_SAMPLE_LIMIT_PER_PULSE: Int = 5
    }

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

        // Best-effort only at unload time.
        val chunk = world.chunkManager.getWorldChunk(pos.x, pos.z, false) ?: return
        applyAmbientObservation(
            world = world,
            pos = chunk.pos,
            inhabitedTicks = chunk.inhabitedTime,
            reason = "UNLOAD",
            shouldLogUpdate = true,
        )
    }

    fun onVeryLowPulse() {
        val srv = server ?: return
        val ordered = tracker.snapshotOrdered()

        val loadedCount = ordered.size
        var searchedCount = 0
        var staleFound = false
        var updatedCount = 0
        var updateLogsEmitted = 0
        val previousCursor = cursorLastKey

        if (ordered.isEmpty()) {
            MementoLog.debug(
                MementoConcept.AMBIENT,
                "refresh loaded={} searched={} staleFound={} updated={} cursorMoved={}",
                loadedCount,
                searchedCount,
                staleFound,
                updatedCount,
                false,
            )
            return
        }

        val start = tracker.indexAfter(cursorLastKey, ordered)
        val seekMax = minOf(MementoConstants.AMBIENT_FRESHNESS_SEARCH_WINDOW_MAX, ordered.size)

        var staleIndex = -1
        var seekVisited = 0
        while (seekVisited < seekMax) {
            val idx = (start + seekVisited) % ordered.size
            val ref = ordered[idx]
            searchedCount++
            if (isStale(ref, srv)) {
                staleIndex = idx
                staleFound = true
                break
            }
            seekVisited++
        }

        if (staleIndex < 0) {
            if (seekMax > 0) {
                val cursorIndex = (start + seekMax - 1) % ordered.size
                cursorLastKey = ordered[cursorIndex]
            }
            MementoLog.debug(
                MementoConcept.AMBIENT,
                "refresh loaded={} searched={} staleFound={} updated={} cursorMoved={}",
                loadedCount,
                searchedCount,
                staleFound,
                updatedCount,
                previousCursor != cursorLastKey,
            )
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
                    val shouldLogUpdate = updateLogsEmitted < AMBIENT_UPDATE_LOG_SAMPLE_LIMIT_PER_PULSE
                    val applied = applyAmbientObservation(
                        world = world,
                        pos = chunk.pos,
                        inhabitedTicks = chunk.inhabitedTime,
                        reason = "STALE",
                        shouldLogUpdate = shouldLogUpdate,
                    )
                    if (applied) {
                        updates++
                        updatedCount++
                        if (shouldLogUpdate) updateLogsEmitted++
                    }
                }
            }
            traversed++
        }

        cursorLastKey = ordered[(staleIndex + batch - 1) % ordered.size]

        MementoLog.debug(
            MementoConcept.AMBIENT,
            "refresh loaded={} searched={} staleFound={} updated={} cursorMoved={}",
            loadedCount,
            searchedCount,
            staleFound,
            updatedCount,
            previousCursor != cursorLastKey,
        )
    }

    private fun isStale(ref: LoadedChunkKey, server: MinecraftServer): Boolean {
        val world = server.getWorld(ref.dimension) ?: return false
        val key = toChunkKey(world, ChunkPos(ref.x, ref.z))
        val existing = worldMapService.substrate().scannedEntry(key) ?: return true
        return (world.time - existing.scanTick) > MementoConstants.AMBIENT_FRESHNESS_STALE_AFTER_TICKS
    }

    private fun applyAmbientObservation(
        world: ServerWorld,
        pos: ChunkPos,
        inhabitedTicks: Long,
        reason: String,
        shouldLogUpdate: Boolean,
    ): Boolean {
        val key = toChunkKey(world, pos)
        val previousTick = worldMapService.substrate().scannedEntry(key)?.scanTick

        val fact = ChunkMetadataFact(
            key = key,
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
        val applied = worldMapService.applyAmbientFactOnTickThread(fact)

        if (applied && shouldLogUpdate) {
            MementoLog.debug(
                MementoConcept.AMBIENT,
                "update chunk={} reason={} prevTick={} newTick={} inhabitedTicks={}",
                "${key.world.value}:${key.chunkX},${key.chunkZ}",
                reason,
                previousTick,
                fact.scanTick,
                inhabitedTicks,
            )
        }

        return applied
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
