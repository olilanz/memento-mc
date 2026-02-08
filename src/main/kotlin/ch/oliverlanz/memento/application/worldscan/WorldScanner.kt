package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.WorldMementoMap
import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadProvider
import ch.oliverlanz.memento.infrastructure.chunk.ChunkRef
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk
import java.util.concurrent.atomic.AtomicBoolean

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
    }

    /** Entry point used by /memento run. */
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
            source.sendFeedback({ Text.literal("Memento: no existing chunks discovered; nothing to scan.") }, false)
            MementoLog.debug(MementoConcept.SCANNER, "proactive=false start aborted reason=no_chunks")
            return 1
        }

        // Ensure a consumer exists (passive mode also relies on it).
        if (consumer == null || map !== scanMap) {
            consumer = ChunkMetadataConsumer(scanMap)
        }

        plannedChunks = scanMap.totalChunks()
        csvWrittenForCurrentRun = false

        // If already complete, export immediately without going proactive.
        if (scanMap.isComplete()) {
            val topology = StoneInfluenceSuperposition.apply(scanMap)
            val path = MementoCsvWriter.write(srv, topology)
            csvWrittenForCurrentRun = true
            MementoLog.debug(
                MementoConcept.SCANNER,
                "proactive=false already_complete plannedChunks={} scannedChunks={} csv={}",
                plannedChunks,
                scanMap.scannedChunks(),
                path.toAbsolutePath(),
            )
            source.sendFeedback({ Text.literal("Memento: scan already complete. CSV exported.") }, false)
            return 1
        }

        proactive.set(true)
        MementoLog.info(
            MementoConcept.SCANNER,
            "scan started worlds={} plannedChunks={} scannedChunks={} missing={}",
            worlds.size,
            plannedChunks,
            scanMap.scannedChunks(),
            scanMap.missingCount(),
        )
        source.sendFeedback({ Text.literal("Memento: scan started. Planned chunks: $plannedChunks") }, false)
        return 1
    }

    override fun desiredChunks(): Sequence<ChunkRef> {
        if (!proactive.get()) return emptySequence()
        val m = map ?: return emptySequence()

        val missing = m.missingSignals(limit = 100)
        if (missing.isEmpty()) {
            completeIfExhausted(m, reason = "exhausted")
            return emptySequence()
        }

        return missing.asSequence()
            .map { key -> ChunkRef(key.world, ChunkPos(key.chunkX, key.chunkZ)) }
    }

    override fun onChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        val m = map ?: return

        // Always consume metadata into the map (passive/reactive behavior).
        consumer?.onChunkLoaded(world, chunk)

        // If not proactive, we do not drive demand; we only enrich the map.
        if (!proactive.get()) return

        if (m.isComplete()) {
            completeIfExhausted(m, reason = "complete")
        }
    }

    override fun onChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
        // Scanner currently does not need unload signals.
    }

    private fun completeIfExhausted(m: WorldMementoMap, reason: String) {
        if (!proactive.compareAndSet(true, false)) {
            return
        }

        val srv = server
        if (srv == null) {
            MementoLog.warn(MementoConcept.SCANNER, "scan completed reason={} but server=null; csv skipped", reason)
            return
        }

        if (!csvWrittenForCurrentRun) {
            val topology = StoneInfluenceSuperposition.apply(m)
            val path = MementoCsvWriter.write(srv, topology)
            csvWrittenForCurrentRun = true
            MementoLog.info(
                MementoConcept.SCANNER,
                "scan completed reason={} plannedChunks={} scannedChunks={} missing={} csv={}",
                reason,
                plannedChunks,
                m.scannedChunks(),
                m.missingCount(),
                path.toAbsolutePath(),
            )
        } else {
            MementoLog.info(
                MementoConcept.SCANNER,
                "scan completed reason={} plannedChunks={} scannedChunks={} missing={} csv=already_written",
                reason,
                plannedChunks,
                m.scannedChunks(),
                m.missingCount(),
            )
        }
    }
}
