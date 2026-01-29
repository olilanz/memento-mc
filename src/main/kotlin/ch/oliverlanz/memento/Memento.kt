package ch.oliverlanz.memento

import ch.oliverlanz.memento.application.CommandHandlers
import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadDriver
import ch.oliverlanz.memento.application.renewal.RenewalChunkLoadProvider
import ch.oliverlanz.memento.application.renewal.RenewalInitialObserver
import ch.oliverlanz.memento.application.renewal.WitherstoneRenewalBridge
import ch.oliverlanz.memento.application.renewal.WitherstoneConsumptionBridge
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
        WitherstoneConsumptionBridge.onRenewalEvent(e)

        if (e is RenewalBatchLifecycleTransition) {
            effectsHost?.onRenewalEvent(e)
        }
    }

    override fun onInitialize() {
        log.info("Initializing Memento")

        Commands.register()

        // Chunk observations → domain + application
        ServerChunkEvents.CHUNK_LOAD.register { world, chunk ->
            // Single infrastructure entrypoint.
            chunkLoadDriver?.onEngineChunkLoaded(world, chunk)
        }

        ServerChunkEvents.CHUNK_UNLOAD.register { world, chunk ->
            chunkLoadDriver?.onEngineChunkUnloaded(world, chunk.pos)
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
                ticketMaxAgeTicks = MementoConstants.CHUNK_LOAD_TICKET_MAX_AGE_TICKS,
            ).also {
                it.attach(server)

                // Provider precedence: renewal first.
                it.registerProvider(renewalChunkLoadProvider!!)

                // Single fan-out point for chunk availability.
                it.registerConsumer(object : ChunkAvailabilityListener {
                    override fun onChunkLoaded(world: net.minecraft.server.world.ServerWorld, chunk: net.minecraft.world.chunk.WorldChunk) {
                        ch.oliverlanz.memento.domain.renewal.RenewalTrackerHooks.onChunkLoaded(world.registryKey, chunk.pos)
                    }

                    override fun onChunkUnloaded(world: net.minecraft.server.world.ServerWorld, pos: net.minecraft.util.math.ChunkPos) {
                        ch.oliverlanz.memento.domain.renewal.RenewalTrackerHooks.onChunkUnloaded(world.registryKey, pos)
                    }
                })

                // Renewal provider consumes loads to dedupe intent.
                it.registerConsumer(renewalChunkLoadProvider!!)
            }

            effectsHost = EffectsHost(server)
            CommandHandlers.attachVisualizationEngine(effectsHost!!)

            runController = MementoRunController().also {
                it.attach(server)
                CommandHandlers.attachRunController(it)
            }

            // World scanner provider (lower priority)
            chunkLoadDriver?.registerProvider(runController!!)
            chunkLoadDriver?.registerConsumer(runController!!)

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
