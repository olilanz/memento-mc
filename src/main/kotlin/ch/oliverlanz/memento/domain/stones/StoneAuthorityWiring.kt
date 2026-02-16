package ch.oliverlanz.memento.domain.stones

import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.StoneLifecycleState
import ch.oliverlanz.memento.domain.events.StoneLifecycleTransition
import ch.oliverlanz.memento.domain.events.StoneLifecycleTrigger
import ch.oliverlanz.memento.infrastructure.MementoDebug
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import net.minecraft.server.MinecraftServer

/**
 * Adapter wiring for StoneAuthority integration into the mod runtime.
 *
 * Keeps the integration surface small and avoids entangling legacy and shadow implementations.
 */
object StoneAuthorityWiring {
    private var loggingAttached = false
    private var serverRef: MinecraftServer? = null

    fun onServerStarted(server: MinecraftServer) {
        // Subscribe to domain events before explicit startup stone processing.
        attachLoggingOnce()
        serverRef = server
    }

    fun onServerStopping() {
        if (loggingAttached) {
            StoneDomainEvents.unsubscribeFromLifecycleTransitions(::logTransition)
            loggingAttached = false
        }

        serverRef = null
        StoneAuthority.detach()
    }

    fun onNightlyCheckpoint(days: Int) {
        MementoLog.info(MementoConcept.STONE, "maturity check trigger=NIGHTLY_TICK days={}", days)
        StoneAuthority.advanceDays(days, StoneLifecycleTrigger.NIGHTLY_TICK)
    }

    fun onAdminTimeAdjustment() {
        MementoLog.info(MementoConcept.STONE, "maturity check trigger=OP_COMMAND")
        StoneAuthority.evaluate(StoneLifecycleTrigger.OP_COMMAND)
    }

    private fun attachLoggingOnce() {
        if (loggingAttached) return
        StoneDomainEvents.subscribeToLifecycleTransitions(::logTransition)
        loggingAttached = true
    }

    private fun logLoadedSnapshot() {
        val stones = StoneAuthority.list()
        MementoLog.info(MementoConcept.STONE, "loaded count={}", stones.size)

        for (s in stones) {
            when (s) {
                is Witherstone -> {
                    val pos = "(${s.position.x},${s.position.y},${s.position.z})"
                    MementoLog.info(
                        MementoConcept.STONE,
                        "loaded witherstone='{}' dim='{}' pos={} state={} days={} r={}",
                        s.name,
                        s.dimension.value.toString(),
                        pos,
                        s.state.name,
                        s.daysToMaturity,
                        s.radius,
                    )
                }

                is Lorestone -> {
                    val pos = "(${s.position.x},${s.position.y},${s.position.z})"
                    MementoLog.info(
                        MementoConcept.STONE,
                        "loaded lorestone='{}' dim='{}' pos={} r={}",
                        s.name,
                        s.dimension.value.toString(),
                        pos,
                        s.radius,
                    )
                }
            }
        }
    }

    private fun logTransition(e: StoneLifecycleTransition) {
        val stone = e.stone
        val pos = "(${stone.position.x},${stone.position.y},${stone.position.z})"
        MementoLog.info(
            MementoConcept.STONE,
            "stone='{}' dim='{}' pos={} {} -> {} trigger={}",
            stone.name,
            stone.dimension.value.toString(),
            pos,
            e.from?.name ?: "ABSENT",
            e.to.name,
            e.trigger.name,
        )

        // Operator feedback (in-game) for meaningful lifecycle events.
        // (Only witherstones produce meaningful lifecycle events for players at the moment.)
        if (stone !is WitherstoneView) return

        when (e.to) {
            StoneLifecycleState.MATURED -> {
                MementoDebug.info(
                    serverRef,
                    "Witherstone '${stone.name}' has matured. Leave the area to allow renewal."
                )
            }

            StoneLifecycleState.CONSUMED -> {
                MementoDebug.info(
                    serverRef,
                    "Witherstone '${stone.name}' has completed renewal."
                )
            }

            else -> {
                // intentionally silent to avoid chat spam
            }
        }
    }
}
