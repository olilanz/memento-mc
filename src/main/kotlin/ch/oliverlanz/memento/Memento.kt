package ch.oliverlanz.memento

import ch.oliverlanz.memento.application.CommandHandlers
import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadDriver
import ch.oliverlanz.memento.application.renewal.RenewalChunkLoadProvider
import ch.oliverlanz.memento.application.renewal.RenewalInitialObserver
import ch.oliverlanz.memento.application.renewal.WitherstoneRenewalBridge
import ch.oliverlanz.memento.application.renewal.WitherstoneConsumptionBridge
import ch.oliverlanz.memento.application.stone.StoneMaturityTimeBridge
import ch.oliverlanz.memento.application.visualization.EffectsHost
import ch.oliverlanz.memento.domain.worldmap.ChunkMetadataFact
import ch.oliverlanz.memento.domain.worldmap.WorldMapService
import ch.oliverlanz.memento.domain.renewal.RenewalBatchLifecycleTransition
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.RenewalChunkObservationBridge
import ch.oliverlanz.memento.domain.renewal.RenewalTrackerLogging
import ch.oliverlanz.memento.domain.stones.StoneAuthorityWiring
import ch.oliverlanz.memento.infrastructure.renewal.RenewalRegenerationBridge
import ch.oliverlanz.memento.infrastructure.pulse.PulseCadence
import ch.oliverlanz.memento.infrastructure.pulse.PulseClock
import ch.oliverlanz.memento.infrastructure.pulse.PulseEvents
import ch.oliverlanz.memento.infrastructure.pulse.PulseGenerator
import ch.oliverlanz.memento.infrastructure.time.GameTimeTracker
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
    private val pulseGenerator = PulseGenerator()

    // Renewal wiring (application / infrastructure)
    private var renewalInitialObserver: RenewalInitialObserver? = null
    private var renewalChunkLoadProvider: RenewalChunkLoadProvider? = null
    private var chunkLoadDriver: ChunkLoadDriver? = null

    private val onRealtimePulse: (PulseClock) -> Unit = {
        chunkLoadDriver?.tick()
    }

    private val onHighPulse: (PulseClock) -> Unit = {
        gameTimeTracker.tick()
    }

    private val onMediumPulse: (PulseClock) -> Unit = {
        worldMapService?.tick()
        worldScanner?.tick()
    }

    private val onLowPulse: (PulseClock) -> Unit = {
        RenewalRegenerationBridge.tickThreadProcess()
        chunkLoadDriver?.logStateOnLowPulse()
    }

    private val onUltraLowPulse: (PulseClock) -> Unit = {
        // Reserved for future heavy maintenance cadence.
    }

    private val onExtremeLowPulse: (PulseClock) -> Unit = {
        // Reserved for future very-low-frequency maintenance cadence.
    }

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

            pulseGenerator.reset()
            PulseEvents.subscribe(PulseCadence.REALTIME, onRealtimePulse)
            PulseEvents.subscribe(PulseCadence.HIGH, onHighPulse)
            PulseEvents.subscribe(PulseCadence.MEDIUM, onMediumPulse)
            PulseEvents.subscribe(PulseCadence.LOW, onLowPulse)
            PulseEvents.subscribe(PulseCadence.VERY_LOW, onUltraLowPulse)
            PulseEvents.subscribe(PulseCadence.ULTRA_LOW, onExtremeLowPulse)

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
                        RenewalChunkObservationBridge.onChunkLoaded(
                            world.registryKey,
                            ChunkPos(fact.key.chunkX, fact.key.chunkZ)
                        )
                    }

                    override fun onChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
                        RenewalChunkObservationBridge.onChunkUnloaded(world.registryKey, pos)
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
            StoneAuthorityWiring.onServerStarted(server)
            StoneMaturityTimeBridge.attach()
            gameTimeTracker.attach(server)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register {

            PulseEvents.unsubscribe(PulseCadence.ULTRA_LOW, onExtremeLowPulse)
            PulseEvents.unsubscribe(PulseCadence.VERY_LOW, onUltraLowPulse)
            PulseEvents.unsubscribe(PulseCadence.LOW, onLowPulse)
            PulseEvents.unsubscribe(PulseCadence.MEDIUM, onMediumPulse)
            PulseEvents.unsubscribe(PulseCadence.HIGH, onHighPulse)
            PulseEvents.unsubscribe(PulseCadence.REALTIME, onRealtimePulse)

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
            StoneAuthorityWiring.onServerStopping()

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
            pulseGenerator.onServerTick()
        }
    }
}
