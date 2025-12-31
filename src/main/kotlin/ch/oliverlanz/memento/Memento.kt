package ch.oliverlanz.memento

import ch.oliverlanz.memento.application.renewal.ProactiveRenewer
import ch.oliverlanz.memento.application.renewal.RenewalInitialObserver
import ch.oliverlanz.memento.application.renewal.WitherstoneRenewalBridge
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.RenewalTrackerHooks
import ch.oliverlanz.memento.domain.renewal.RenewalTrackerLogging
import ch.oliverlanz.memento.domain.stones.StoneRegisterHooks
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

        val renewer = ProactiveRenewer(chunksPerTick = 1)
        val initialObserver = RenewalInitialObserver()
        RenewalTracker.subscribe(renewer::onRenewalEvent)
        RenewalTracker.subscribe(initialObserver::onRenewalEvent)

        // -----------------------------------------------------------------
        // Server lifecycle wiring
        // -----------------------------------------------------------------

        ServerLifecycleEvents.SERVER_STARTED.register(ServerLifecycleEvents.ServerStarted { server ->
            // IMPORTANT: Attach all observers / bridges BEFORE StoneRegister.attach(),
            // because attach() performs startup reconciliation which may emit transitions.
            RenewalTrackerLogging.attachOnce()
            WitherstoneRenewalBridge.attach()

            initialObserver.attach(server)
            renewer.attach(server)

            StoneRegisterHooks.onServerStarted(server)

            // Some stones may already be persisted as MATURED. Startup reconciliation may therefore be a no-op.
            // Reconcile AFTER StoneRegister is attached to ensure renewal batches exist for already-matured stones.
            WitherstoneRenewalBridge.reconcileAfterStoneRegisterAttached(reason = "startup_post_attach")

        })

        ServerLifecycleEvents.SERVER_STOPPING.register(ServerLifecycleEvents.ServerStopping {
            renewer.detach()
            initialObserver.detach()
            WitherstoneRenewalBridge.detach()
            RenewalTrackerLogging.detach()
            StoneRegisterHooks.onServerStopping()
        })

        // -----------------------------------------------------------------
        // Execution loop
        // -----------------------------------------------------------------

        ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick {
            renewer.tick()
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