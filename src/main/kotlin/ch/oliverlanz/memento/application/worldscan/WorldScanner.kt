package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.WorldMementoMap
import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadProvider
import ch.oliverlanz.memento.infrastructure.chunk.ChunkRef
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.lang.Math
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk

/**
 * World scanner.
 *
 * Responsibility boundaries:
 * - Owns demand (desired chunk set) derived from the WorldMementoMap.
 * - Owns reconciliation (a chunk stops being demanded once metadata is attached).
 * - Receives chunk availability via the ChunkLoadDriver's propagation callback.
 *
 * This class implements both:
 * - [ChunkLoadProvider] (driver pulls demand)
 * - [ChunkAvailabilityListener] (driver pushes availability)
 */
class WorldScanner : ChunkLoadProvider, ChunkAvailabilityListener {

    private var server: MinecraftServer? = null

    /**
     * Scan mode:
     * - active=true -> propose chunks to the driver (baseline scan)
     * - active=false -> passive/reactive only (keep map fresh opportunistically)
     */
    private val activeScan = AtomicBoolean(false)

    /** The world map (single source of truth). Kept in memory across proactive runs. */
    private var map: WorldMementoMap? = null

    private var consumer: ChunkMetadataConsumer? = null

    /** Observability only. */
    private var plannedChunks: Int = 0

    /**
     * Current scanner demand set (ChunkKey), regulated by high/low watermarks.
     *
     * - Scanner expresses intent by exposing this set via [desiredChunks].
     * - The driver remains responsible for ticket pacing and pressure balancing.
     * - The map remains the sole authority for scan completion (scanTick).
     */
    private val desiredKeys = LinkedHashSet<ChunkKey>()

    /** Refill cadence guard (absolute world tick). */
    private var lastRefillTick: Long = Long.MIN_VALUE

    private val listeners = CopyOnWriteArrayList<WorldScanListener>()

    /** True after the current active scan has emitted ScanCompleted. */
    private var completionEmittedForCurrentScan: Boolean = false

    fun attach(server: MinecraftServer) {
        this.server = server
    }

    fun detach() {
        server = null
        activeScan.set(false)
        map = null
        consumer = null
        plannedChunks = 0
        desiredKeys.clear()
        lastRefillTick = Long.MIN_VALUE
        completionEmittedForCurrentScan = false
        listeners.clear()
    }

    fun addListener(listener: WorldScanListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: WorldScanListener) {
        listeners.remove(listener)
    }

    /** Entry point used by /memento scan. */
    fun startActiveScan(source: ServerCommandSource): Int {
        val srv = source.server
        this.server = srv

        if (activeScan.get()) {
            source.sendError(Text.literal("Memento: scan is already running."))
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
                    val key =
                            ChunkKey(
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
            MementoLog.debug(MementoConcept.SCANNER, "active=false scan aborted reason=no_chunks")
            return 1
        }

        // Ensure a consumer exists (passive mode also relies on it).
        if (consumer == null || map !== scanMap) {
            consumer = ChunkMetadataConsumer(scanMap)
        }

        plannedChunks = scanMap.totalChunks()
        completionEmittedForCurrentScan = false
        desiredKeys.clear()
        lastRefillTick = Long.MIN_VALUE
        desiredKeys.clear()
        lastRefillTick = Long.MIN_VALUE

        // If already complete, emit completion immediately without entering active scan mode.
        if (scanMap.isComplete()) {
            emitCompleted(reason = "already_complete", map = scanMap)
            MementoLog.debug(
                    MementoConcept.SCANNER,
                    "scan already_complete plannedChunks={} scannedChunks={}",
                    plannedChunks,
                    scanMap.scannedChunks()
            )
            source.sendFeedback({ Text.literal("Memento: scan already complete.") }, false)
            return 1
        }

        activeScan.set(true)
        MementoLog.info(
                MementoConcept.SCANNER,
                "scan started worlds={} plannedChunks={} scannedChunks={} missing={}",
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
        if (!activeScan.get()) return emptySequence()
        val m = map ?: return emptySequence()

        val tickNow = server?.overworld?.time ?: 0L
        reconcileAndRefillDemand(tickNow, m)

        if (desiredKeys.isEmpty()) {
            emitCompleted(reason = "exhausted", map = m)
            return emptySequence()
        }

        return desiredKeys.asSequence().map { key ->
            ChunkRef(key.world, ChunkPos(key.chunkX, key.chunkZ))
        }
    }

    private fun reconcileAndRefillDemand(tickNow: Long, map: WorldMementoMap) {
        // 1) Reconcile: drop any keys that are no longer missing.
        if (desiredKeys.isNotEmpty()) {
            val it = desiredKeys.iterator()
            while (it.hasNext()) {
                val key = it.next()
                if (!map.isMissing(key)) {
                    it.remove()
                }
            }
        }

        // 2) Watermark refill, bounded by cadence.
        val active = desiredKeys.size
        if (active >= MementoConstants.MEMENTO_SCAN_DESIRE_LOW_WATERMARK) return
        if (tickNow != 0L &&
                        (tickNow - lastRefillTick) <
                                MementoConstants.MEMENTO_SCAN_DESIRE_REFILL_EVERY_TICKS
        )
                return

        lastRefillTick = tickNow

        val target = MementoConstants.MEMENTO_SCAN_DESIRE_HIGH_WATERMARK
        while (desiredKeys.size < target) {
            val needed = target - desiredKeys.size
            val candidates = map.missingSignals(limit = needed)
            if (candidates.isEmpty()) break
            var addedAny = false
            for (k in candidates) {
                if (desiredKeys.size >= target) break
                if (desiredKeys.add(k)) addedAny = true
            }
            // Safety: if the map returned only keys already desired, stop to avoid tight loops.
            if (!addedAny) break
        }
    }

    override fun onChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        val m = map ?: return

        val chunkX = chunk.pos.x
        val chunkZ = chunk.pos.z
        val key =
                ChunkKey(
                        world = world.registryKey,
                        regionX = Math.floorDiv(chunkX, 32),
                        regionZ = Math.floorDiv(chunkZ, 32),
                        chunkX = chunkX,
                        chunkZ = chunkZ,
                )

        // Always consume metadata into the map (passive/reactive behavior).
        consumer?.onChunkLoaded(world, chunk)
        m.markScanned(key, world.time)
        desiredKeys.remove(key)
        // If not active scan, we do not drive demand; we only enrich the map.
        if (!activeScan.get()) return

        if (m.isComplete()) {
            emitCompleted(reason = "complete", map = m)
        }
    }

    override fun onChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
        // Scanner currently does not need unload signals.
    }

    override fun onChunkLoadExpired(world: ServerWorld, pos: ChunkPos) {
        val m = map ?: return

        val chunkX = pos.x
        val chunkZ = pos.z
        val key =
                ChunkKey(
                        world = world.registryKey,
                        regionX = Math.floorDiv(chunkX, 32),
                        regionZ = Math.floorDiv(chunkZ, 32),
                        chunkX = chunkX,
                        chunkZ = chunkZ,
                )

        // Best-effort coverage: record scan tick even when no chunk was accessible for propagation.
        m.markScanned(key, world.time)
        desiredKeys.remove(key)

        if (!activeScan.get()) return
        if (m.isComplete()) {
            emitCompleted(reason = "complete", map = m)
        }
    }

    private fun emitCompleted(reason: String, map: WorldMementoMap) {
        if (!activeScan.compareAndSet(true, false)) return
        if (completionEmittedForCurrentScan) return
        completionEmittedForCurrentScan = true

        desiredKeys.clear()

        val srv = server
        if (srv == null) {
            MementoLog.warn(
                    MementoConcept.SCANNER,
                    "scan completed reason={} but server=null; listeners skipped",
                    reason
            )
            return
        }

        val event =
                WorldScanCompleted(
                        reason = reason,
                        plannedChunks = plannedChunks,
                        scannedChunks = map.scannedChunks(),
                        missingChunks = map.missingCount(),
                        snapshot = map.snapshot(),
                )

        listeners.forEach { l ->
            try {
                l.onWorldScanCompleted(srv, event)
            } catch (t: Throwable) {
                MementoLog.error(MementoConcept.SCANNER, "scan completion listener failed", t)
            }
        }

        MementoLog.info(
                MementoConcept.SCANNER,
                "scan completed reason={} plannedChunks={} scannedChunks={} missing={}",
                reason,
                event.plannedChunks,
                event.scannedChunks,
                event.missingChunks,
        )
    }
}
