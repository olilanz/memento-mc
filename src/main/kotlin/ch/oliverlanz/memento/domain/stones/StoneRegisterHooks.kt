package ch.oliverlanz.memento.domain.stones

import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.WitherstoneStateTransition
import ch.oliverlanz.memento.domain.events.WitherstoneTransitionTrigger
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

/**
 * Adapter hooks for wiring StoneRegister into the mod runtime.
 *
 * Keeps the integration surface small and avoids entangling legacy and shadow implementations.
 */
object StoneRegisterHooks {

    private val log = LoggerFactory.getLogger("memento")

    private var loggingAttached = false

    fun onServerStarted(server: MinecraftServer) {
        StoneRegister.attach(server)
        attachLoggingOnce()
    }

    fun onServerStopping() {
        if (loggingAttached) {
            StoneDomainEvents.unsubscribeFromWitherstoneTransitions(::logTransition)
            loggingAttached = false
        }
        StoneRegister.detach()
    }

    fun onNightlyCheckpoint() {
        StoneRegister.advanceDay()
    }

    fun onAdminTimeAdjustment() {
        StoneRegister.evaluate(WitherstoneTransitionTrigger.ADMIN_TIME_ADJUSTMENT)
    }

    private fun attachLoggingOnce() {
        if (loggingAttached) return
        StoneDomainEvents.subscribeToWitherstoneTransitions(::logTransition)
        loggingAttached = true
    }

    private fun logTransition(e: WitherstoneStateTransition) {
        val pos = "(${e.position.x},${e.position.y},${e.position.z})"
        log.info(
            "[STONE] witherstone='{}' dim='{}' pos={} {} -> {} trigger={}",
            e.stoneName,
            e.dimension.value.toString(),
            pos,
            e.from.name,
            e.to.name,
            e.trigger.name,
        )
    }
}