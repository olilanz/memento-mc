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
import ch.oliverlanz.memento.domain.worldmap.ChunkMetadataFact
import ch.oliverlanz.memento.domain.worldmap.WorldMapService
import ch.oliverlanz.memento.domain.renewal.RenewalBatchLifecycleTransition
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.RenewalTrackerHooks
import ch.oliverlanz.memento.domain.renewal.RenewalTrackerLogging
import ch.oliverlanz.memento.domain.stones.StoneAuthorityHooks
import ch.oliverlanz.memento.infrastructure.renewal.RenewalRegenerationBridge
import ch.oliverlanz.memento.infrastructure.worldscan.WorldScanCsvExporter
import ch.oliverlanz.memento.infrastructure.worldscan.WorldScanner
import ch.oliverlanz.memento.infrastructure.worldscan.TwoPassRegionFileMetadataProvider
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog

object Memento : ModInitializer {

    private var effectsHost: EffectsHost? = null
    private val gameTimeTracker = GameTimeTracker()

    private var worldScanner: WorldScanner? = null
    private var worldMapService: WorldMapService? = null

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

            worldMapService = WorldMapService().also { it.attach() }

            RenewalTrackerLogging.attachOnce()
            renewalInitialObserver = RenewalInitialObserver().also { it.attach(server) }

            // Renewal provider (highest priority)
            renewalChunkLoadProvider = RenewalChunkLoadProvider()

            chunkLoadDriver = ChunkLoadDriver().also {
                it.attach(server)

                // Explicit driver authority: renewal demand only.
                it.registerRenewalProvider(renewalChunkLoadProvider!!)

                // Single fan-out point for chunk availability → domain renewal tracker
                it.registerConsumer(object : ChunkAvailabilityListener {
                    override fun onChunkMetadata(world: ServerWorld, fact: ChunkMetadataFact) {
                        RenewalTrackerHooks.onChunkLoaded(
                            world.registryKey,
                            ChunkPos(fact.key.chunkX, fact.key.chunkZ)
                        )
                    }

                    override fun onChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
                        RenewalTrackerHooks.onChunkUnloaded(world.registryKey, pos)
                    }
                })
            }

            effectsHost = EffectsHost(server)
            CommandHandlers.attachVisualizationEngine(effectsHost!!)

            val scanner = WorldScanner().also {
                val service = checkNotNull(worldMapService) { "WorldMapService must be initialized before WorldScanner bootstrap" }
                MementoLog.info(MementoConcept.SCANNER, "bootstrap scanner: binding WorldMapService before server attach")
                it.attachWorldMapService(service)
                it.attach(server)
                it.attachFileMetadataProvider(TwoPassRegionFileMetadataProvider(it.metadataIngestionPort()))
                it.addListener(WorldScanCsvExporter)
            }

            worldScanner = scanner
            CommandHandlers.attachWorldScanner(scanner)

            // World scanner remains a passive chunk-availability consumer.
            chunkLoadDriver?.registerConsumer(scanner)

            // Fan out domain events
            RenewalTracker.subscribe(renewalEventListener)

            WitherstoneRenewalBridge.attach()
            StoneAuthorityHooks.onServerStarted(server)
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
            StoneAuthorityHooks.onServerStopping()

            CommandHandlers.detachVisualizationEngine()
            CommandHandlers.detachWorldScanner()

            worldScanner?.detach()
            worldScanner = null

            worldMapService?.detach()
            worldMapService = null

            effectsHost = null
        }

        // Transport tick only
        ServerTickEvents.END_SERVER_TICK.register {
            gameTimeTracker.tick()
            worldMapService?.tick()
            chunkLoadDriver?.tick()
            worldScanner?.tick()
        }
    }
}
