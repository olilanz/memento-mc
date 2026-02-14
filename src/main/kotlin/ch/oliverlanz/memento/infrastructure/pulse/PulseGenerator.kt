package ch.oliverlanz.memento.infrastructure.pulse

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog

/**
 * Infrastructure pulse generator.
 *
 * Emits cadence transport signals only; never executes semantic domain decisions.
 */
class PulseGenerator {

    private var tick: Long = 0L
    private var lastStateLogTick: Long = 0L

    init {
        validateConfiguration()
    }

    fun reset() {
        validateConfiguration()
        tick = 0L
        lastStateLogTick = 0L
    }

    fun onServerTick() {
        tick += 1L

        emit(PulseCadence.HIGH)

        if (isDue(
                period = MementoConstants.PULSE_CADENCE_MEDIUM_TICKS,
                phase = MementoConstants.PULSE_PHASE_MEDIUM,
            )) {
            emit(PulseCadence.MEDIUM)
        }

        if (isDue(
                period = MementoConstants.PULSE_CADENCE_LOW_TICKS,
                phase = MementoConstants.PULSE_PHASE_LOW,
            )) {
            emit(PulseCadence.LOW)
        }

        if (isDue(
                period = MementoConstants.PULSE_CADENCE_ULTRA_LOW_TICKS,
                phase = MementoConstants.PULSE_PHASE_ULTRA_LOW,
            )) {
            emit(PulseCadence.ULTRA_LOW)
        }

        if (isDue(
                period = MementoConstants.PULSE_CADENCE_EXTREME_LOW_TICKS,
                phase = MementoConstants.PULSE_PHASE_EXTREME_LOW,
            )) {
            emit(PulseCadence.EXTREME_LOW)
        }

        maybeLogState()
    }

    private fun emit(cadence: PulseCadence) {
        PulseEvents.publish(PulseClock(tick = tick, cadence = cadence))
    }

    private fun isDue(period: Long, phase: Long): Boolean {
        if (period <= 0L) return false
        val normalizedPhase = phase.mod(period)
        return tick % period == normalizedPhase
    }

    private fun maybeLogState() {
        if ((tick - lastStateLogTick) < MementoConstants.PULSE_STATE_LOG_EVERY_TICKS) return

        lastStateLogTick = tick
        MementoLog.debug(
            MementoConcept.WORLD,
            "pulse state tick={} listeners(high={} medium={} low={} ultraLow={} extremeLow={})",
            tick,
            PulseEvents.listenerCount(PulseCadence.HIGH),
            PulseEvents.listenerCount(PulseCadence.MEDIUM),
            PulseEvents.listenerCount(PulseCadence.LOW),
            PulseEvents.listenerCount(PulseCadence.ULTRA_LOW),
            PulseEvents.listenerCount(PulseCadence.EXTREME_LOW),
        )
    }

    private fun validateConfiguration() {
        require(MementoConstants.PULSE_CADENCE_HIGH_TICKS > 0L) { "PULSE_CADENCE_HIGH_TICKS must be > 0" }
        require(MementoConstants.PULSE_CADENCE_MEDIUM_TICKS > 0L) { "PULSE_CADENCE_MEDIUM_TICKS must be > 0" }
        require(MementoConstants.PULSE_CADENCE_LOW_TICKS > 0L) { "PULSE_CADENCE_LOW_TICKS must be > 0" }
        require(MementoConstants.PULSE_CADENCE_ULTRA_LOW_TICKS > 0L) { "PULSE_CADENCE_ULTRA_LOW_TICKS must be > 0" }
        require(MementoConstants.PULSE_CADENCE_EXTREME_LOW_TICKS > 0L) { "PULSE_CADENCE_EXTREME_LOW_TICKS must be > 0" }

        // Phase staggering guardrails for nested cadences.
        require(
            MementoConstants.PULSE_PHASE_MEDIUM.mod(MementoConstants.PULSE_CADENCE_LOW_TICKS) !=
                MementoConstants.PULSE_PHASE_LOW.mod(MementoConstants.PULSE_CADENCE_LOW_TICKS)
        ) { "MEDIUM and LOW phases must be staggered" }

        require(
            MementoConstants.PULSE_PHASE_LOW.mod(MementoConstants.PULSE_CADENCE_ULTRA_LOW_TICKS) !=
                MementoConstants.PULSE_PHASE_ULTRA_LOW.mod(MementoConstants.PULSE_CADENCE_ULTRA_LOW_TICKS)
        ) { "LOW and ULTRA_LOW phases must be staggered" }

        require(
            MementoConstants.PULSE_PHASE_ULTRA_LOW.mod(MementoConstants.PULSE_CADENCE_EXTREME_LOW_TICKS) !=
                MementoConstants.PULSE_PHASE_EXTREME_LOW.mod(MementoConstants.PULSE_CADENCE_EXTREME_LOW_TICKS)
        ) { "ULTRA_LOW and EXTREME_LOW phases must be staggered" }
    }
}
