package ch.oliverlanz.memento

import ch.oliverlanz.memento.application.CommandHandlers
import ch.oliverlanz.memento.application.renewal.ChunkLoadScheduler
import ch.oliverlanz.memento.application.renewal.RenewalInitialObserver
import ch.oliverlanz.memento.application.renewal.WitherstoneRenewalBridge
import ch.oliverlanz.memento.application.stone.StoneMaturityTimeBridge
import ch.oliverlanz.memento.application.time.GameTimeTracker
import ch.oliverlanz.memento.application.visualization.StoneVisualizationEngine
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.RenewalTrackerHooks
import ch.oliverlanz.memento.domain.renewal.RenewalTrackerLogging
import ch.oliverlanz.memento.domain.stones.StoneTopologyHooks
import ch.oliverlanz.memento.infrastructure.MementoConstants
import ch.oliverlanz.memento.infrastructure.renewal.RenewalRegenerationBridge
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

object Memento : ModInitializer {

    private val log = LoggerFactory.getLogger("Memento")

    private var visualizationEngine: StoneVisualizationEngine? = null
    private val gameTimeTracker = GameTimeTracker()

    // Renewal wiring (application/infrastructure)
    private var renewalInitialObserver: RenewalInitialObserver? = null
    private var chunkLoadScheduler: ChunkLoadScheduler? = null

    private var renewalTickCounter: Int = 0

    // Single listener to fan out RenewalTracker domain events to application/infrastructure components.
    private val renewalEventListener: (RenewalEvent) -> Unit = { e ->
        renewalInitialObserver?.onRenewalEvent(e)
        chunkLoadScheduler?.onRenewalEvent(e)
        RenewalRegenerationBridge.onRenewalEvent(e)
        visualizationEngine?.onRenewalEvent(e)
    }

    override fun onInitialize() {
        log.info("Initializing Memento")

        // Register /memento command tree.
        Commands.register()

        // Chunk observations -> RenewalTracker
        // (Domain remains observational; it only reacts to these hooks.)
        ServerChunkEvents.CHUNK_LOAD.register { world, chunk ->
            RenewalTrackerHooks.onChunkLoaded(world.registryKey, chunk.pos)
        }
        ServerChunkEvents.CHUNK_UNLOAD.register { world, chunk ->
            RenewalTrackerHooks.onChunkUnloaded(world.registryKey, chunk.pos)
        }

        // Attach server-scoped components.
        ServerLifecycleEvents.SERVER_STARTED.register { server: MinecraftServer ->
            // ------------------------------------------------------------
            // Wiring order matters.
            // All observers must be attached BEFORE any domain activity can emit events.
            // Startup must not be a separate semantic code path.
            // ------------------------------------------------------------

            // Renewal: logging + observational seeding + paced execution.
            RenewalTrackerLogging.attachOnce()
            renewalInitialObserver = RenewalInitialObserver().also { it.attach(server) }
            chunkLoadScheduler = ChunkLoadScheduler(chunksPerTick = 1).also { it.attach(server) }

            // Visualization host must be attached before any domain activity can emit events.
            visualizationEngine = StoneVisualizationEngine(server)
            CommandHandlers.attachVisualizationEngine(visualizationEngine!!)

            // Fan out tracker events.
            RenewalTracker.subscribe(renewalEventListener)

            // Application wiring that reacts to normal domain events.
            WitherstoneRenewalBridge.attach()

            // Domain startup (loading stones must behave like normal creation).
            StoneTopologyHooks.onServerStarted(server)

            // Drive stone maturity from semantic day events.
            StoneMaturityTimeBridge.attach()

            gameTimeTracker.attach(server)

            renewalTickCounter = 0
        }

        // Detach cleanly.
        ServerLifecycleEvents.SERVER_STOPPING.register {
            gameTimeTracker.detach()

            // Renewal detach first (avoid consuming stones while topology is stopping).
            RenewalTracker.unsubscribe(renewalEventListener)

            chunkLoadScheduler?.detach()
            chunkLoadScheduler = null

            renewalInitialObserver?.detach()
            renewalInitialObserver = null

            RenewalRegenerationBridge.clear()
            RenewalTrackerLogging.detach()

            StoneMaturityTimeBridge.detach()
            WitherstoneRenewalBridge.detach()
            StoneTopologyHooks.onServerStopping()

            CommandHandlers.detachVisualizationEngine()
            visualizationEngine = null
        }

        // Transport tick only (NO direct domain logic here).
        // (Application/infrastructure may still need a paced tick.)
        ServerTickEvents.END_SERVER_TICK.register {
            gameTimeTracker.tick()

            val scheduler = chunkLoadScheduler
            if (scheduler != null) {
                renewalTickCounter++
                if (renewalTickCounter % MementoConstants.REGENERATION_CHUNK_INTERVAL_TICKS == 0) {
                    scheduler.tick()
                }
            }
        }
    }
}
