package ch.oliverlanz.memento.application.worldscan
import ch.oliverlanz.memento.domain.memento.WorldMementoSubstrate
import ch.oliverlanz.memento.infrastructure.MementoConstants
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Application-layer controller for the /memento run tracer-bullet pipeline.
 *
 * Semantics (Slice 1):
 * - One run at a time.
 * - Triggered explicitly by an operator.
 * - Executes discovery immediately, then chunk extraction in tick-paced slices.
 */
class MementoRunController {

    private val log = LoggerFactory.getLogger("memento")

    private val worldDiscovery = WorldDiscovery()
    private val regionDiscovery = RegionDiscovery()
    private val chunkDiscovery = ChunkDiscovery()
    private val extractor = ChunkInfoExtractor()

    @Volatile
    private var isRunning: Boolean = false

    private var server: MinecraftServer? = null

    private var initiatorPlayer: UUID? = null

    private var plan: WorldDiscoveryPlan? = null
    private var substrate: WorldMementoSubstrate? = null

    private var startedNanos: Long = 0L

    fun attach(server: MinecraftServer) {
        this.server = server
    }

    fun detach() {
        server = null
        isRunning = false
        initiatorPlayer = null
        plan = null
        substrate = null
    }

    fun start(source: ServerCommandSource): Int {
        val s = server
        if (s == null) {
            source.sendError(Text.literal("Memento: server not ready."))
            return 0
        }

        synchronized(this) {
            if (isRunning) {
                source.sendError(Text.literal("Memento: a run is already in progress."))
                return 0
            }
            isRunning = true
        }

        initiatorPlayer = (source.entity as? ServerPlayerEntity)?.uuid
        startedNanos = System.nanoTime()

        log.info("[RUN] started by={}", source.name)
        source.sendFeedback({ Text.literal("Memento: run started.") }, false)

        // Stage 1: discovery (immediate)
        val discoveryStart = System.nanoTime()
        val worldKeys = worldDiscovery.discover(s)
        val worldsAndRegions = regionDiscovery.discover(s, worldKeys)
        val discovered = chunkDiscovery.discover(worldsAndRegions)
        val discoveryMs = (System.nanoTime() - discoveryStart) / 1_000_000
        log.info("[RUN] discovery completed worlds={} durationMs={}", discovered.worlds.size, discoveryMs)

        val sub = WorldMementoSubstrate()
        plan = discovered
        substrate = sub
        extractor.start(discovered, sub)

        return 1
    }

    /** Called each server tick by [ch.oliverlanz.memento.Memento]. */
    fun tick() {
        if (!isRunning) return

        val s = server ?: return
        val p = plan ?: return
        val sub = substrate ?: return

        val more = try {
            extractor.readNext(s, MementoConstants.MEMENTO_RUN_CHUNK_SLOTS_PER_TICK)
        } catch (e: Exception) {
            log.error("[RUN] extraction failed", e)
            notifyInitiator(s, "Memento: run failed (see server log).")
            clearRunState()
            return
        }

        if (more) {
            return
        }

        // Stage 3: influence superposition (immediate)
        val influenceStart = System.nanoTime()
        val topology = try {
            StoneInfluenceSuperposition.apply(sub)
        } catch (e: Exception) {
            log.error("[RUN] influence superposition failed", e)
            notifyInitiator(s, "Memento: run failed during influence superposition (see server log).")
            clearRunState()
            return
        }
        val influenceMs = (System.nanoTime() - influenceStart) / 1_000_000
        log.info("[RUN] influence superposition completed entries={} durationMs={}", topology.entries.size, influenceMs)

        // Stage 4: CSV generation (immediate)
        val writeStart = System.nanoTime()
        val path = try {
            MementoCsvWriter.write(s, topology)
        } catch (e: Exception) {
            log.error("[RUN] csv write failed", e)
            notifyInitiator(s, "Memento: run failed writing CSV (see server log).")
            clearRunState()
            return
        }
        val writeMs = (System.nanoTime() - writeStart) / 1_000_000

        val totalMs = (System.nanoTime() - startedNanos) / 1_000_000
        log.info("[RUN] completed totalMs={} csvPath={}", totalMs, path)

        notifyInitiator(s, "Memento: run completed. Wrote ${topology.entries.size} rows to ${path.fileName}.")
        clearRunState()
    }

    private fun notifyInitiator(server: MinecraftServer, message: String) {
        val uuid = initiatorPlayer
        if (uuid == null) {
            log.info("[RUN] notify (no player): {}", message)
            return
        }
        val player = server.playerManager.getPlayer(uuid)
        if (player == null) {
            log.info("[RUN] notify (player offline): {}", message)
            return
        }
        player.sendMessage(Text.literal(message), false)
    }

    private fun clearRunState() {
        synchronized(this) {
            isRunning = false
        }
        initiatorPlayer = null
        plan = null
        substrate = null
    }
}
