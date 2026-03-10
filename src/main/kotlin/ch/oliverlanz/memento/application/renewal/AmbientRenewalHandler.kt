package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.application.CommandHandlers
import ch.oliverlanz.memento.infrastructure.ambient.MementoConfigStore
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import ch.oliverlanz.memento.infrastructure.time.GameClock
import ch.oliverlanz.memento.infrastructure.time.GameClockEvents
import ch.oliverlanz.memento.infrastructure.worldscan.WorldScanner
import net.minecraft.server.MinecraftServer

/**
 * Acceptance-gated ambient nightly orchestration.
 *
 * This component is orchestration-only:
 * - listens to clock transport
 * - evaluates acceptance and scan gates
 * - triggers existing scan/renew pathways
 *
 * It does not own projection/election/execution semantics.
 */
class AmbientRenewalHandler(
    private val server: MinecraftServer,
    private val scanner: WorldScanner,
) {

    private var attached: Boolean = false
    private var lastObservedMementoDayIndex: Long? = null

    fun attach() {
        if (attached) return
        GameClockEvents.subscribe(::onClock)
        attached = true
    }

    fun detach() {
        if (!attached) return
        GameClockEvents.unsubscribe(::onClock)
        attached = false
        lastObservedMementoDayIndex = null
    }

    private fun onClock(clock: GameClock) {
        val last = lastObservedMementoDayIndex
        if (last == null) {
            lastObservedMementoDayIndex = clock.mementoDayIndex
            return
        }

        if (clock.mementoDayIndex <= last) return
        lastObservedMementoDayIndex = clock.mementoDayIndex

        onNightlyCheckpoint()
    }

    private fun onNightlyCheckpoint() {
        if (!MementoConfigStore.isAmbientRenewalAccepted()) return

        if (scanner.statusView().active) {
            MementoLog.debug(MementoConcept.RENEWAL, "ambient nightly skipped reason=scan_running")
            return
        }

        if (!scanner.hasInitialScanCompleted()) {
            MementoLog.info(MementoConcept.RENEWAL, "ambient nightly action=scan_start")
            scanner.startActiveScan(server.commandSource)
            return
        }

        MementoLog.info(MementoConcept.RENEWAL, "ambient nightly action=renew_execute count=1")
        CommandHandlers.executeRenewalFromAutomation(server, 1)
    }
}

