package ch.oliverlanz.memento

import ch.oliverlanz.memento.application.renewal.WitherstoneRenewalBridge
import ch.oliverlanz.memento.application.stone.StoneMaturityTimeBridge
import ch.oliverlanz.memento.application.time.GameTimeTracker
import ch.oliverlanz.memento.application.visualization.StoneVisualizationEngine
import ch.oliverlanz.memento.domain.stones.StoneTopologyHooks
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

object Memento : ModInitializer {

    private val log = LoggerFactory.getLogger("Memento")

    private var visualizationEngine: StoneVisualizationEngine? = null
    private val gameTimeTracker = GameTimeTracker()

    override fun onInitialize() {
        log.info("Initializing Memento")

        // Register /memento command tree.
        Commands.register()

        // Attach server-scoped components.
        ServerLifecycleEvents.SERVER_STARTED.register { server: MinecraftServer ->
            StoneTopologyHooks.onServerStarted(server)

            // Application wiring (no startup-only code paths).
            WitherstoneRenewalBridge.attach()

            // Drive stone maturity from semantic day events.
            StoneMaturityTimeBridge.attach()

            gameTimeTracker.attach(server)
            visualizationEngine = StoneVisualizationEngine(server)
        }

        // Detach cleanly.
        ServerLifecycleEvents.SERVER_STOPPING.register {
            gameTimeTracker.detach()

            StoneMaturityTimeBridge.detach()
            WitherstoneRenewalBridge.detach()
            StoneTopologyHooks.onServerStopping()

            visualizationEngine = null
        }

        // Transport tick only (NO domain logic here).
        ServerTickEvents.END_SERVER_TICK.register {
            gameTimeTracker.tick()
        }
    }
}
