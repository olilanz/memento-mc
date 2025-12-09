package ch.oliverlanz.memento

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object Memento : ModInitializer {
    private val logger = LoggerFactory.getLogger("memento")

    override fun onInitialize() {
        logger.info("Memento: Natural Renewal initializing...")
    }
}
