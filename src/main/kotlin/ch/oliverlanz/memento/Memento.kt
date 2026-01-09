package ch.oliverlanz.memento

import ch.oliverlanz.memento.application.renewal.ChunkLoadScheduler
import ch.oliverlanz.memento.application.renewal.RenewalInitialObserver
import ch.oliverlanz.memento.application.renewal.WitherstoneRenewalBridge
import ch.oliverlanz.memento.application.stone.StoneMaturityTimeBridge
import ch.oliverlanz.memento.application.time.GameTimeTracker
import ch.oliverlanz.memento.application.visualization.StoneVisualizationEngine
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.RenewalTrackerHooks
import ch.oliverlanz.memento.domain.renewal.RenewalTrackerLogging
import ch.oliverlanz.memento.domain.stones.StoneTopologyHooks
import ch.oliverlanz.memento.infrastructure.renewal.RenewalRegenerationBridge
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import org.slf4j.LoggerFactory

object Memento : ModInitializer {

    private val log = LoggerFactory.getLogger("memento")

    override fun onInitialize() {
        log.info("Initializing Memento (new authoritative pipeline)")

        Commands.register()

        val scheduler = ChunkLoadScheduler(chunksPerTick = 1)
        val initialObserver = RenewalInitialObserver()
        val timeTracker = GameTimeTracker(clockEmitEveryTicks = 10)

        RenewalTracker.subscribe(scheduler::onRenewalEvent)
        RenewalTracker.subscribe(initialObserver::onRenewalEvent)
        RenewalTracker.subscribe(RenewalRegenerationBridge::onRenewalEvent)

        // -----------------------------------------------------------------
        // Server lifecycle wiring
        // -----------------------------------------------------------------

        ServerLifecycleEvents.SERVER_STARTED.register(ServerLifecycleEvents.ServerStarted { server ->
            // IMPORTANT: Attach all observers / bridges BEFORE StoneTopology.attach(),
            // because attach() performs startup reconciliation which may emit transitions.
            RenewalTrackerLogging.attachOnce()
            WitherstoneRenewalBridge.attach()

            initialObserver.attach(server)
            scheduler.attach(server)
            timeTracker.attach(server)

            // Time-driven stone maturity (semantic day events)
            StoneMaturityTimeBridge.attach()

            // Visualization is application-level and event-driven.
            StoneVisualizationEngine.attach(server)

            StoneTopologyHooks.onServerStarted(server)

            // Some stones may already be persisted as MATURED. Startup reconciliation may therefore be a no-op.
            // Reconcile AFTER StoneTopology is attached to ensure renewal batches exist for already-matured stones.
            WitherstoneRenewalBridge.reconcileAfterStoneTopologyAttached(reason = "startup_post_attach")
        })

        ServerLifecycleEvents.SERVER_STOPPING.register(ServerLifecycleEvents.ServerStopping {
            scheduler.detach()
            initialObserver.detach()
            StoneMaturityTimeBridge.detach()
            timeTracker.detach()
            WitherstoneRenewalBridge.detach()

            StoneVisualizationEngine.detach()

            RenewalRegenerationBridge.clear()
            RenewalTrackerLogging.detach()
            StoneTopologyHooks.onServerStopping()
        })

        // -----------------------------------------------------------------
        // Execution loop
        // -----------------------------------------------------------------

        ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick {
            // Renewal work (paced)
            scheduler.tick()

            // Time tracking (drives semantic events + application clock)
            timeTracker.tick()
        })

        // -----------------------------------------------------------------
        // Chunk lifecycle -> RenewalTracker (observational)
        // -----------------------------------------------------------------

        ServerChunkEvents.CHUNK_LOAD.register(ServerChunkEvents.Load { world, chunk ->
            RenewalTrackerHooks.onChunkLoaded(world.registryKey, chunk.pos)
        })

        ServerChunkEvents.CHUNK_UNLOAD.register(ServerChunkEvents.Unload { world, chunk ->
            RenewalTrackerHooks.onChunkUnloaded(world.registryKey, chunk.pos)
        })
    }
}