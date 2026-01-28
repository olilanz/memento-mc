package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.domain.memento.ChunkKey
import ch.oliverlanz.memento.domain.memento.WorldMementoSubstrate
import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadProvider
import ch.oliverlanz.memento.infrastructure.chunk.ChunkRef
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk
import org.slf4j.LoggerFactory

/**
 * /memento run controller.
 *
 * Responsibilities:
 * - Orchestrates the discovery pipeline on demand (World -> Region -> Chunk slots)
 * - Declares scan intent via [ChunkLoadProvider] (declarative desired chunks)
 * - Consumes loaded chunks via [ChunkAvailabilityListener] and extracts signals into a substrate
 * - Finalizes by superimposing stone influence and writing the CSV
 *
 * Engine mechanics (tickets, throttling, passive/active) are owned by the infrastructure ChunkLoadDriver.
 */
class MementoRunController : ChunkLoadProvider, ChunkAvailabilityListener {

    private val log = LoggerFactory.getLogger("memento")

    private var server: MinecraftServer? = null

    private var active = false

    private var plan: WorldScanPlan? = null
    private var substrate: WorldMementoSubstrate? = null
    private var consumer: ChunkMetadataConsumer? = null

    /** Number of chunks planned for the current run (for observability only). */
    private var plannedChunks: Int = 0

    private var ticksSinceStart: Int = 0

    fun attach(server: MinecraftServer) {
        this.server = server
    }

    fun detach() {
        server = null
        active = false
        plan = null
        substrate = null
        consumer = null
        plannedChunks = 0
    }

    /**
     * Entry point used by CommandHandlers.
     */
    fun start(source: ServerCommandSource): Int {
        val srv = source.server
        this.server = srv

        if (active) {
            source.sendError(net.minecraft.text.Text.literal("Memento: a scan is already running."))
            return 0
        }

        val scanPlan = WorldScanPlan()
        val scanSubstrate = WorldMementoSubstrate()

        // Build the discovery plan (pure IO / planning; no chunk loads).
        val worlds = WorldDiscovery().discover(srv)
        val discoveredRegions = RegionDiscovery().discover(srv, worlds)
        val discoveredChunks = ChunkDiscovery().discover(discoveredRegions)

        var count = 0
        discoveredChunks.worlds.forEach { world ->
            world.regions.forEach { region ->
                region.chunks.forEach { slot ->
                    val chunkX = region.x * 32 + slot.localX
                    val chunkZ = region.z * 32 + slot.localZ
                    scanPlan.add(ChunkRef(world.world, ChunkPos(chunkX, chunkZ)))
                    count++
                }
            }
        }

        if (count == 0) {
            source.sendFeedback({ net.minecraft.text.Text.literal("Memento: no existing chunks discovered; nothing to scan.") }, false)
            log.info("[RUN] start aborted reason=no_chunks")
            return 1
        }

        this.plan = scanPlan
        this.substrate = scanSubstrate
        this.consumer = ChunkMetadataConsumer(scanSubstrate, scanPlan)
        this.plannedChunks = count
        this.active = true
        this.ticksSinceStart = 0

        log.info("[RUN] started worlds={} plannedChunks={}", worlds.size, plannedChunks)
        log.info("[RUN] note driver='yields while external loads are observed'; behavior='consume external loads; drive only when not yielding'")
        source.sendFeedback({ net.minecraft.text.Text.literal("Memento: scan started. Planned chunks: $plannedChunks") }, false)
        return 1
    }

    fun tick() {
        if (!active) return

        val srv = server ?: return
        val p = plan ?: return
        val s = substrate ?: return

        if (p.isComplete()) {
            // Finalize once: superposition + CSV export.
            val topology = StoneInfluenceSuperposition.apply(s)
            val path = MementoCsvWriter.write(srv, topology)
            log.info("[RUN] completed plannedChunks={} scannedChunks={} csv={}", plannedChunks, s.size(), path.toAbsolutePath())
            active = false
            plan = null
            substrate = null
            consumer = null
            plannedChunks = 0
            ticksSinceStart = 0
            return
        }

        ticksSinceStart++

        // Minimal observability (avoid per-tick spam).
        // We log progress every ~5 seconds (100 ticks).
        if ((ticksSinceStart % 100) == 0) {
            log.info("[RUN] progress scannedChunks={} plannedChunks={}", s.size(), plannedChunks)
        }
    }

    override fun desiredChunks(): Sequence<ChunkRef> {
        if (!active) return emptySequence()
        val p = plan ?: return emptySequence()
        // Driver will select based on priority; scanner is always lowest priority.
        return p.desiredChunks().asSequence()
    }

    override fun onChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        if (!active) return
        consumer?.onChunkLoaded(world, chunk)
    }
}