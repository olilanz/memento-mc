package ch.oliverlanz.memento

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object Memento : ModInitializer {

    private val log = LoggerFactory.getLogger("memento")

    override fun onInitialize() {
        log.info("Initializing Memento (new authoritative pipeline)")
        // Legacy hooks deliberately disabled
    }
}