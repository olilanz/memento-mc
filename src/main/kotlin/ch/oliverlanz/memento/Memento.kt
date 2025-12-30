package ch.oliverlanz.memento

import ch.oliverlanz.memento.application.renewal.ProactiveRenewer
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
        RenewalTracker.subscribe(renewer::onRenewalEvent)

        ServerLifecycleEvents.SERVER_STARTED.register(ServerLifecycleEvents.ServerStarted { server ->
    // Attach logging & bridges BEFORE startup reconciliation emits transitions.
    RenewalTrackerLogging.attachOnce()
    WitherstoneRenewalBridge.attach()

    StoneRegisterHooks.onServerStarted(server)
    // Now that StoneRegister has loaded persisted stones, reconcile matured stones into renewal batches.
    WitherstoneRenewalBridge.reconcileAfterStoneRegisterAttached(reason = "startup_post_attach")
    renewer.attach(server)
})

ServerLifecycleEvents.SERVER_STOPPING.register(ServerLifecycleEvents.ServerStopping {
    renewer.detach()
    WitherstoneRenewalBridge.detach()
    RenewalTrackerLogging.detach()
    StoneRegisterHooks.onServerStopping()
})

ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick {
            renewer.tick()
        })

        // Chunk lifecycle -> RenewalTracker (observational)
        ServerChunkEvents.CHUNK_LOAD.register(ServerChunkEvents.Load { world, chunk ->
            RenewalTrackerHooks.onChunkLoaded(world.registryKey, chunk.pos)
        })

        ServerChunkEvents.CHUNK_UNLOAD.register(ServerChunkEvents.Unload { world, chunk ->
            RenewalTrackerHooks.onChunkUnloaded(world.registryKey, chunk.pos)
        })
    }
}