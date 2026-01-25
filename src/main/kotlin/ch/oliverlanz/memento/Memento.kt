package ch.oliverlanz.memento

import ch.oliverlanz.memento.application.CommandHandlers
import ch.oliverlanz.memento.application.chunk.ChunkLoadDriver
import ch.oliverlanz.memento.application.renewal.RenewalChunkLoadProvider
import ch.oliverlanz.memento.application.renewal.RenewalInitialObserver
import ch.oliverlanz.memento.application.renewal.WitherstoneRenewalBridge
import ch.oliverlanz.memento.application.stone.StoneMaturityTimeBridge
import ch.oliverlanz.memento.application.time.GameTimeTracker
import ch.oliverlanz.memento.application.visualization.EffectsHost
import ch.oliverlanz.memento.application.worldscan.MementoRunController
import ch.oliverlanz.memento.domain.renewal.RenewalBatchLifecycleTransition
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

    private var effectsHost: EffectsHost? = null
    private val gameTimeTracker = GameTimeTracker()

    private var runController: MementoRunController? = null

    // Renewal wiring (application / infrastructure)
    private var renewalInitialObserver: RenewalInitialObserver? = null
    private var renewalChunkLoadProvider: RenewalChunkLoadProvider? = null
    private var chunkLoadDriver: ChunkLoadDriver? = null

    // Single listener to fan out RenewalTracker domain events
    private val renewalEventListener: (RenewalEvent) -> Unit = { e ->
        renewalInitialObserver?.onRenewalEvent(e)
        renewalChunkLoadProvider?.onRenewalEvent(e)
        RenewalRegenerationBridge.onRenewalEvent(e)

        if (e is RenewalBatchLifecycleTransition) {
            effectsHost?.onRenewalEvent(e)
        }
    }

    override fun onInitialize() {
        log.info("Initializing Memento")

        Commands.register()

        // Chunk observations → domain + application
        ServerChunkEvents.CHUNK_LOAD.register { world, chunk ->
            RenewalTrackerHooks.onChunkLoaded(world.registryKey, chunk.pos)
            chunkLoadDriver?.onChunkLoaded(world.registryKey, chunk.pos)
            renewalChunkLoadProvider?.onChunkLoaded(world.registryKey, chunk.pos)
            runController?.onChunkLoaded(world, chunk)
        }

        ServerChunkEvents.CHUNK_UNLOAD.register { world, chunk ->
            RenewalTrackerHooks.onChunkUnloaded(world.registryKey, chunk.pos)
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server: MinecraftServer ->

            // ------------------------------------------------------------
            // Wiring order matters.
            // ------------------------------------------------------------

            RenewalTrackerLogging.attachOnce()
            renewalInitialObserver = RenewalInitialObserver().also { it.attach(server) }

            // Renewal provider (highest priority)
            renewalChunkLoadProvider = RenewalChunkLoadProvider()

            chunkLoadDriver = ChunkLoadDriver(
                activeLoadIntervalTicks = MementoConstants.CHUNK_LOAD_ACTIVE_INTERVAL_TICKS,
                passiveGraceTicks = MementoConstants.CHUNK_LOAD_PASSIVE_GRACE_TICKS,
            ).also {
                it.attach(server)
                it.registerProvider(renewalChunkLoadProvider!!)
            }

            effectsHost = EffectsHost(server)
            CommandHandlers.attachVisualizationEngine(effectsHost!!)

            runController = MementoRunController().also {
                it.attach(server)
                CommandHandlers.attachRunController(it)
            }

            // World scanner provider (lower priority)
            chunkLoadDriver?.registerProvider(runController!!)

            // Fan out domain events
            RenewalTracker.subscribe(renewalEventListener)

            WitherstoneRenewalBridge.attach()
            StoneTopologyHooks.onServerStarted(server)
            StoneMaturityTimeBridge.attach()
            gameTimeTracker.attach(server)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register {

            gameTimeTracker.detach()
            RenewalTracker.unsubscribe(renewalEventListener)

            chunkLoadDriver?.detach()
            chunkLoadDriver = null

            renewalChunkLoadProvider = null

            renewalInitialObserver?.detach()
            renewalInitialObserver = null

            RenewalRegenerationBridge.clear()
            RenewalTrackerLogging.detach()

            StoneMaturityTimeBridge.detach()
            WitherstoneRenewalBridge.detach()
            StoneTopologyHooks.onServerStopping()

            CommandHandlers.detachVisualizationEngine()
            CommandHandlers.detachRunController()

            runController?.detach()
            runController = null

            effectsHost = null
        }

        // Transport tick only
        ServerTickEvents.END_SERVER_TICK.register {
            gameTimeTracker.tick()
            runController?.tick()
            chunkLoadDriver?.tick()
        }
    }
}
