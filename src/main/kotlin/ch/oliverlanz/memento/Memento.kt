package ch.oliverlanz.memento

import ch.oliverlanz.memento.application.visualization.StoneVisualizationEngine
import net.fabricmc.api.ModInitializer
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

object Memento : ModInitializer {

    private val log = LoggerFactory.getLogger("Memento")
    private lateinit var visualizationEngine: StoneVisualizationEngine

    override fun onInitialize() {
        // Visualization engine is self-registering; construction is sufficient.
        // No attach/detach lifecycle calls.
        log.info("Initializing Memento")
    }

    fun onServerStarted(server: MinecraftServer) {
        visualizationEngine = StoneVisualizationEngine(server)
    }

    fun onServerStopping() {
        // No-op: visualization engine unregisters via process shutdown.
    }
}