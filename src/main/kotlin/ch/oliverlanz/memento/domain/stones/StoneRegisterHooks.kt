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
        log.info("[STONE] attach trigger=SERVER_START")
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

    fun onNightlyCheckpoint(days: Int) {
        log.info("[STONE] maturity check trigger=NIGHTLY_TICK days={}", days)
        StoneRegister.advanceDays(days, WitherstoneTransitionTrigger.NIGHTLY_TICK)
    }

    fun onAdminTimeAdjustment() {
        log.info("[STONE] maturity check trigger=OP_COMMAND")
        StoneRegister.evaluate(WitherstoneTransitionTrigger.OP_COMMAND)
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