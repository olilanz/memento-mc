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
import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.infrastructure.renewal.RenewalRegenerationBridge
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog

object Memento : ModInitializer {

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
        MementoLog.info(MementoConcept.WORLD, "initializing")

        Commands.register()

        // The ChunkLoadDriver owns the engine event subscriptions.
        ChunkLoadDriver.installEngineHooks()

        ServerLifecycleEvents.SERVER_STARTED.register { server: MinecraftServer ->

            RenewalTrackerLogging.attachOnce()
            renewalInitialObserver = RenewalInitialObserver().also { it.attach(server) }

            // Renewal provider (highest priority)
            renewalChunkLoadProvider = RenewalChunkLoadProvider()

            chunkLoadDriver = ChunkLoadDriver().also {
                it.attach(server)

                // Explicit driver precedence: renewal first, scanner fallback.
                it.registerRenewalProvider(renewalChunkLoadProvider!!)

                // Single fan-out point for chunk availability → domain renewal tracker
                it.registerConsumer(object : ChunkAvailabilityListener {
                    override fun onChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
                        RenewalTrackerHooks.onChunkLoaded(world.registryKey, chunk.pos)
                    }

                    override fun onChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
                        RenewalTrackerHooks.onChunkUnloaded(world.registryKey, pos)
                    }
                })
            }

            effectsHost = EffectsHost(server)
            CommandHandlers.attachVisualizationEngine(effectsHost!!)

            runController = MementoRunController().also {
                it.attach(server)
                CommandHandlers.attachRunController(it)
            }

            // World scanner provider (lower priority)
            chunkLoadDriver?.registerScanProvider(runController!!)
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
            chunkLoadDriver?.tick()
        }
    }
}
