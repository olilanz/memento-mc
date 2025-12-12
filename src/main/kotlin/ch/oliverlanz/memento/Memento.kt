package ch.oliverlanz.memento

import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Memento : ModInitializer {
    internal val logger: Logger = LoggerFactory.getLogger("memento")

    object log {
        fun info(msg: () -> String) = logger.info(msg())
        fun warn(msg: () -> String) = logger.warn(msg())
        fun error(msg: () -> String) = logger.error(msg())
    }

    override fun onInitialize() {
        log.info { "Memento: Natural Renewal initializing..." }

        MementoBlocks.register()
        Commands.register()
    }
}
