package ch.oliverlanz.memento

import ch.oliverlanz.memento.application.CommandHandlers
import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadDriver
import ch.oliverlanz.memento.application.renewal.RenewalChunkLoadProvider
import ch.oliverlanz.memento.application.renewal.RenewalInitialObserver
import ch.oliverlanz.memento.application.renewal.AmbientRenewalHandler
import ch.oliverlanz.memento.application.renewal.WitherstoneRenewalEventSubscriber
import ch.oliverlanz.memento.application.renewal.WitherstoneConsumptionEventSubscriber
import ch.oliverlanz.memento.application.stone.StoneMaturityTimeBridge
import ch.oliverlanz.memento.application.visualization.EffectsHost
import ch.oliverlanz.memento.domain.worldmap.WorldMapService
import ch.oliverlanz.memento.domain.renewal.projection.RenewalProjection
import ch.oliverlanz.memento.domain.renewal.projection.RenewalProjectionEvents
import ch.oliverlanz.memento.domain.renewal.projection.RenewalProjectionStableListener
import ch.oliverlanz.memento.domain.renewal.projection.WorldMapFactAppliedListener
import ch.oliverlanz.memento.domain.renewal.RenewalBatchLifecycleTransition
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.RenewalChunkObservationAdapter
import ch.oliverlanz.memento.domain.renewal.RenewalTrackerLogging
import ch.oliverlanz.memento.domain.events.StoneLifecycleTrigger
import ch.oliverlanz.memento.domain.stones.StoneAuthority
import ch.oliverlanz.memento.domain.stones.StoneAuthorityWiring
import ch.oliverlanz.memento.infrastructure.renewal.RenewalRegenerationGate
import ch.oliverlanz.memento.infrastructure.pruning.WorldPruningService
import ch.oliverlanz.memento.infrastructure.async.GlobalAsyncExclusionGate
import ch.oliverlanz.memento.infrastructure.pulse.PulseCadence
import ch.oliverlanz.memento.infrastructure.pulse.PulseClock
import ch.oliverlanz.memento.infrastructure.pulse.PulseEvents
import ch.oliverlanz.memento.infrastructure.pulse.PulseGenerator
import ch.oliverlanz.memento.infrastructure.time.GameTimeTracker
import ch.oliverlanz.memento.infrastructure.worldscan.WorldScanCsvExporter
import ch.oliverlanz.memento.infrastructure.worldscan.WorldScanner
import ch.oliverlanz.memento.infrastructure.worldscan.RegionFileMetadataProvider
import ch.oliverlanz.memento.infrastructure.worldscan.WorldScanListener
import ch.oliverlanz.memento.infrastructure.observability.OperatorMessages
import ch.oliverlanz.memento.infrastructure.ambient.MementoConfigStore
import ch.oliverlanz.memento.infrastructure.ambient.AmbientIngestionService
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
    private var renewalProjection: RenewalProjection? = null
    private var projectionOperatorListener: RenewalProjectionStableListener? = null
    private val pulseGenerator = PulseGenerator()

    private val onWorldMapFactApplied = WorldMapFactAppliedListener {
        renewalProjection?.observeFactApplied(it.fact)
    }

    private val onWorldScanCompleted = WorldScanListener { _, _ ->
        renewalProjection?.observeWorldScanCompleted()
    }

    // Renewal wiring (application / infrastructure)
    private var renewalInitialObserver: RenewalInitialObserver? = null
    private var renewalChunkLoadProvider: RenewalChunkLoadProvider? = null
    private var ambientRenewalHandler: AmbientRenewalHandler? = null
    private var ambientIngestionService: AmbientIngestionService? = null
    private var chunkLoadDriver: ChunkLoadDriver? = null

    private val onRealtimePulse: (PulseClock) -> Unit = {
        chunkLoadDriver?.tick()
    }

    private val onHighPulse: (PulseClock) -> Unit = {
        gameTimeTracker.tick()
        worldScanner?.tick()
        renewalProjection?.tick()
    }

    private val onMediumPulse: (PulseClock) -> Unit = {
        worldScanner?.onMediumPulse()
        renewalProjection?.onMediumPulse()
        WorldPruningService.tickThreadProcess()
        CommandHandlers.onMediumPulse()
    }

    private val onLowPulse: (PulseClock) -> Unit = {
        RenewalRegenerationGate.tickThreadProcess()
        chunkLoadDriver?.logStateOnLowPulse()
    }

    private val onUltraLowPulse: (PulseClock) -> Unit = {
        ambientIngestionService?.onVeryLowPulse()
    }

    private val onExtremeLowPulse: (PulseClock) -> Unit = {
        // Reserved for future very-low-frequency maintenance cadence.
    }

    // Single listener to fan out RenewalTracker domain events
    private val renewalEventListener: (RenewalEvent) -> Unit = { e ->
        renewalInitialObserver?.onRenewalEvent(e)
        renewalChunkLoadProvider?.onRenewalEvent(e)
        RenewalRegenerationGate.onRenewalEvent(e)
        WitherstoneConsumptionEventSubscriber.onRenewalEvent(e)

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

            GlobalAsyncExclusionGate.attach()

            pulseGenerator.reset()
            PulseEvents.subscribe(PulseCadence.REALTIME, onRealtimePulse)
            PulseEvents.subscribe(PulseCadence.HIGH, onHighPulse)
            PulseEvents.subscribe(PulseCadence.MEDIUM, onMediumPulse)
            PulseEvents.subscribe(PulseCadence.LOW, onLowPulse)
            PulseEvents.subscribe(PulseCadence.VERY_LOW, onUltraLowPulse)
            PulseEvents.subscribe(PulseCadence.ULTRA_LOW, onExtremeLowPulse)

            worldMapService = WorldMapService().also { it.attach(server) }
            MementoConfigStore.attach(server)

            renewalProjection = RenewalProjection().also { projection ->
                projection.attach(checkNotNull(worldMapService))

                val operatorListener = RenewalProjectionStableListener {
                    val reason = projection.statusView().lastCompletedReason
                    if (reason != "SCAN_COMPLETED") {
                        return@RenewalProjectionStableListener
                    }
                    val status = projection.statusView()
                    val durationText = status.lastCompletedDurationMs
                        ?.let { " in ${formatDuration(it)}" }
                        ?: ""
                    OperatorMessages.info(
                        server,
                        "Renewal analysis finished$durationText and refreshed world-view metrics."
                    )
                }
                projection.addStableListener(operatorListener)
                projectionOperatorListener = operatorListener
            }

            RenewalProjectionEvents.subscribeFactApplied(onWorldMapFactApplied)

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
                    override fun onChunkLoaded(world: ServerWorld, pos: ChunkPos) {
                        RenewalChunkObservationAdapter.onChunkLoaded(
                            world.registryKey,
                            pos,
                        )
                    }

                    override fun onChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
                        RenewalChunkObservationAdapter.onChunkUnloaded(world.registryKey, pos)
                    }
                })
            }

            ambientIngestionService = AmbientIngestionService(checkNotNull(worldMapService)).also {
                it.attach(server)
                chunkLoadDriver?.registerConsumer(it)
            }

            effectsHost = EffectsHost(server)
            CommandHandlers.attachVisualizationEngine(effectsHost!!)

            val scanner = WorldScanner().also {
                val service = checkNotNull(worldMapService) { "WorldMapService must be initialized before WorldScanner bootstrap" }
                MementoLog.info(MementoConcept.SCANNER, "bootstrap scanner: binding WorldMapService before server attach")
                it.attachWorldMapService(service)
                it.attach(server)
                it.attachFileMetadataProvider(RegionFileMetadataProvider(it.metadataIngestionPort()))
                it.addListener(onWorldScanCompleted)
            }

            worldScanner = scanner
            WorldPruningService.attach(server, scanner)
            CommandHandlers.attachWorldScanner(scanner)
            CommandHandlers.attachRenewalProjection(checkNotNull(renewalProjection))

            ambientRenewalHandler = AmbientRenewalHandler(server, scanner).also { it.attach() }

            if (!MementoConfigStore.isAmbientRenewalAccepted()) {
                MementoLog.info(MementoConcept.RENEWAL, "Ambient renewal is not yet activated.")
                MementoLog.info(MementoConcept.RENEWAL, "The mod can automatically renew unused terrain over time.")
                MementoLog.info(MementoConcept.RENEWAL, "Before enabling, ensure you keep regular world backups.")
                MementoLog.info(MementoConcept.RENEWAL, "Run \"/memento accept\" to activate automated renewal.")
            }

            // World scanner remains a passive chunk-availability consumer.
            chunkLoadDriver?.registerConsumer(scanner)

            // Fan out domain events
            RenewalTracker.subscribe(renewalEventListener)

            WitherstoneRenewalEventSubscriber.attach()
            CommandHandlers.attachStoneNameSuggestions()
            StoneAuthorityWiring.onServerStarted(server)

            // Explicit startup stone lifecycle bootstrap:
            // 1) attach authority runtime, 2) process persisted stones through authoritative
            // mutation/transition pathway, 3) evaluate startup lifecycle progression.
            MementoLog.info(MementoConcept.STONE, "startup stone bootstrap phase=attach")
            StoneAuthority.attach(server)
            StoneAuthority.attachWorldFactPublisher { fact ->
                worldMapService?.applyFactOnTickThread(fact)
            }

            MementoLog.info(MementoConcept.STONE, "startup stone bootstrap phase=process_persisted")
            StoneAuthority.processPersistedStones(trigger = StoneLifecycleTrigger.SERVER_START)

            MementoLog.info(MementoConcept.STONE, "startup stone bootstrap phase=evaluate")
            StoneAuthority.evaluate(StoneLifecycleTrigger.SERVER_START)

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
            RenewalProjectionEvents.unsubscribeFactApplied(onWorldMapFactApplied)

            chunkLoadDriver?.detach()
            chunkLoadDriver = null

            renewalChunkLoadProvider = null

            renewalInitialObserver?.detach()
            renewalInitialObserver = null

            ambientRenewalHandler?.detach()
            ambientRenewalHandler = null

            ambientIngestionService?.detach()
            ambientIngestionService = null

            RenewalRegenerationGate.clear()
            RenewalTrackerLogging.detach()

            StoneMaturityTimeBridge.detach()
            WitherstoneRenewalEventSubscriber.detach()
            CommandHandlers.detachStoneNameSuggestions()
            StoneAuthority.attachWorldFactPublisher(null)
            StoneAuthorityWiring.onServerStopping()

            CommandHandlers.detachVisualizationEngine()
            CommandHandlers.detachWorldScanner()
            CommandHandlers.detachRenewalProjection()

            worldScanner?.detach()
            worldScanner = null
            WorldPruningService.detach()

            projectionOperatorListener?.let { renewalProjection?.removeStableListener(it) }
            projectionOperatorListener = null
            renewalProjection?.detach()
            renewalProjection = null

            worldMapService?.detach()
            worldMapService = null

            MementoConfigStore.detach()

            effectsHost = null

            GlobalAsyncExclusionGate.detach()
        }

        // Transport tick only
        ServerTickEvents.END_SERVER_TICK.register {
            pulseGenerator.onServerTick()
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) "${minutes}m ${seconds}s" else "${seconds}s"
    }
}
