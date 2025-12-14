package ch.oliverlanz.memento

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.LoggerFactory

object Memento : ModInitializer {

    private val logger = LoggerFactory.getLogger("memento")

    override fun onInitialize() {
        logger.info("Memento initializing")

        Commands.register()

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            logger.info("Loading memento anchors...")
            MementoPersistence.load(server)
            logger.info("Loaded ${MementoAnchors.list().size} anchors")
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            logger.info("Saving memento anchors...")
            MementoPersistence.save(server)
        }
    }
}
