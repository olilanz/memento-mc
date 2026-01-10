package ch.oliverlanz.memento.domain.stones

import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.StoneLifecycleState
import ch.oliverlanz.memento.domain.events.StoneLifecycleTransition
import ch.oliverlanz.memento.domain.events.StoneLifecycleTrigger
import ch.oliverlanz.memento.infrastructure.MementoDebug
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

/**
 * Adapter hooks for wiring StoneTopology into the mod runtime.
 *
 * Keeps the integration surface small and avoids entangling legacy and shadow implementations.
 */
object StoneTopologyHooks {

    private val log = LoggerFactory.getLogger("memento")

    private var loggingAttached = false
    private var serverRef: MinecraftServer? = null

    fun onServerStarted(server: MinecraftServer) {
        // Subscribe to domain events BEFORE attach(), so we observe startup reconciliation transitions.
        attachLoggingOnce()

        serverRef = server

        log.info("[STONE] attach trigger=SERVER_START")
        StoneTopology.attach(server)
        StoneTopology.evaluate(StoneLifecycleTrigger.SERVER_START)
        logLoadedSnapshot()
    }

    fun onServerStopping() {
        if (loggingAttached) {
            StoneDomainEvents.unsubscribeFromLifecycleTransitions(::logTransition)
            loggingAttached = false
        }

        serverRef = null
        StoneTopology.detach()
    }

    fun onNightlyCheckpoint(days: Int) {
        log.info("[STONE] maturity check trigger=NIGHTLY_TICK days={}", days)
        StoneTopology.advanceDays(days, StoneLifecycleTrigger.NIGHTLY_TICK)
    }

    fun onAdminTimeAdjustment() {
        log.info("[STONE] maturity check trigger=OP_COMMAND")
        StoneTopology.evaluate(StoneLifecycleTrigger.OP_COMMAND)
    }

    private fun attachLoggingOnce() {
        if (loggingAttached) return
        StoneDomainEvents.subscribeToLifecycleTransitions(::logTransition)
        loggingAttached = true
    }

    private fun logLoadedSnapshot() {
        val stones = StoneTopology.list()
        log.info("[STONE] loaded count={}", stones.size)

        for (s in stones) {
            when (s) {
                is Witherstone -> {
                    val pos = "(${s.position.x},${s.position.y},${s.position.z})"
                    log.info(
                        "[STONE] loaded witherstone='{}' dim='{}' pos={} state={} days={} r={}",
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
                    log.info(
                        "[STONE] loaded lorestone='{}' dim='{}' pos={} r={}",
                        s.name,
                        s.dimension.value.toString(),
                        pos,
                        s.radius,
                    )
                }

                else -> {
                    // no-op: future stone types
                }
            }
        }
    }

    private fun logTransition(e: StoneLifecycleTransition) {
        val stone = e.stone
        val pos = "(${stone.position.x},${stone.position.y},${stone.position.z})"
        log.info(
            "[STONE] stone='{}' dim='{}' pos={} {} -> {} trigger={}",
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
