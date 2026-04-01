package ch.oliverlanz.memento.infrastructure.ambient

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkMetadataFact
import ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance
import ch.oliverlanz.memento.domain.worldmap.WorldMapService
import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadDriver
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
 *
 * Unload-path semantics:
 * - unload-time refresh is best-effort only and may miss due to normal lifecycle races
 * - VERY_LOW pulse sweep is the reliable reconciliation path for stale ambient metadata
 */
class AmbientIngestionService(
    private val worldMapService: WorldMapService,
    private val chunkLoadDriver: ChunkLoadDriver,
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

        // Best-effort only at unload time; misses are expected under lifecycle races.
        // Reliable reconciliation happens on VERY_LOW pulse sweep.
        val metadata = chunkLoadDriver.getMetadataIfLoaded(ref.dimension, ref.x, ref.z) ?: return
        applyAmbientObservation(
            metadata = metadata,
            reason = "UNLOAD",
            shouldLogUpdate = true,
        )
    }

    fun onVeryLowPulse() {
        val ordered = tracker.snapshotOrdered()
        val pulseTick = server?.overworld?.time ?: -1L

        val loadedCount = ordered.size
        var searchedCount = 0
        var staleFound = false
        var requestedCount = 0
        var hitCount = 0
        var missCount = 0
        var appliedCount = 0
        var updateLogsEmitted = 0
        val previousCursor = cursorLastKey

        if (ordered.isEmpty()) {
            MementoLog.debug(
                MementoConcept.AMBIENT,
                "refresh tick={} loaded={} searched={} staleFound={} requested={} hits={} misses={} applied={} cursorMoved={}",
                pulseTick,
                loadedCount,
                searchedCount,
                staleFound,
                requestedCount,
                hitCount,
                missCount,
                appliedCount,
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
            val metadata = metadataOrDrop(ref = ref, reason = "SWEEP_STALE")
            requestedCount++
            if (metadata == null) {
                missCount++
                seekVisited++
                continue
            }
            hitCount++
            searchedCount++
            if (isStale(ref = ref, currentTick = metadata.scanTick)) {
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
                "refresh tick={} loaded={} searched={} staleFound={} requested={} hits={} misses={} applied={} cursorMoved={}",
                pulseTick,
                loadedCount,
                searchedCount,
                staleFound,
                requestedCount,
                hitCount,
                missCount,
                appliedCount,
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
            if (updates < MementoConstants.AMBIENT_FRESHNESS_MAX_UPDATES_PER_PULSE) {
                val metadata = metadataOrDrop(ref = ref, reason = "SWEEP_STALE")
                requestedCount++
                if (metadata == null) {
                    missCount++
                    traversed++
                    continue
                }
                hitCount++
                if (!isStale(ref = ref, currentTick = metadata.scanTick)) {
                    traversed++
                    continue
                }

                val shouldLogUpdate = updateLogsEmitted < AMBIENT_UPDATE_LOG_SAMPLE_LIMIT_PER_PULSE
                val applied = applyAmbientObservation(
                    metadata = metadata,
                    reason = "SWEEP_STALE",
                    shouldLogUpdate = shouldLogUpdate,
                )
                if (applied) {
                    updates++
                    appliedCount++
                    if (shouldLogUpdate) updateLogsEmitted++
                }
            }
            traversed++
        }

        cursorLastKey = ordered[(staleIndex + batch - 1) % ordered.size]

        MementoLog.debug(
            MementoConcept.AMBIENT,
            "refresh tick={} loaded={} searched={} staleFound={} requested={} hits={} misses={} applied={} cursorMoved={}",
            pulseTick,
            loadedCount,
            searchedCount,
            staleFound,
            requestedCount,
            hitCount,
            missCount,
            appliedCount,
            previousCursor != cursorLastKey,
        )
    }

    private fun isStale(ref: LoadedChunkKey, currentTick: Long): Boolean {
        val key = toChunkKey(ref)
        val existing = worldMapService.substrate().scannedEntry(key) ?: return true
        return (currentTick - existing.scanTick) > MementoConstants.AMBIENT_FRESHNESS_STALE_AFTER_TICKS
    }

    private fun metadataOrDrop(ref: LoadedChunkKey, reason: String): ChunkLoadDriver.LoadedChunkMetadata? {
        val metadata = chunkLoadDriver.getMetadataIfLoaded(ref.dimension, ref.x, ref.z)
        if (metadata == null) {
            // Expected race: tracker entry can outlive loaded-state visibility.
            tracker.onUnloaded(ref)
            MementoLog.debug(
                MementoConcept.AMBIENT,
                "tracker.drop key={} reason=METADATA_MISS source={}",
                "${ref.dimension.value}:${ref.x},${ref.z}",
                reason,
            )
        }
        return metadata
    }

    private fun applyAmbientObservation(
        metadata: ChunkLoadDriver.LoadedChunkMetadata,
        reason: String,
        shouldLogUpdate: Boolean,
    ): Boolean {
        val key = metadata.key
        val previousTick = worldMapService.substrate().scannedEntry(key)?.scanTick

        val fact = ChunkMetadataFact(
            key = key,
            source = ChunkScanProvenance.ENGINE_AMBIENT,
            unresolvedReason = null,
            signals = metadata.signals,
            scanTick = metadata.scanTick,
        )
        val applied = worldMapService.applyAmbientFactOnTickThread(fact)

        if (applied && shouldLogUpdate) {
            val signals = fact.signals
            MementoLog.debug(
                MementoConcept.AMBIENT,
                "update key={} reason={} prevTick={} newTick={} inhabited={} surfaceY={} biome={}",
                "${key.world.value}:${key.chunkX},${key.chunkZ}",
                reason,
                previousTick,
                fact.scanTick,
                signals?.inhabitedTimeTicks,
                signals?.surfaceY,
                signals?.biomeId,
            )
        }

        return applied
    }

    private fun toChunkKey(ref: LoadedChunkKey): ChunkKey =
        ChunkKey(
            world = ref.dimension,
            regionX = Math.floorDiv(ref.x, 32),
            regionZ = Math.floorDiv(ref.z, 32),
            chunkX = ref.x,
            chunkZ = ref.z,
        )

}
