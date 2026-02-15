package ch.oliverlanz.memento.application.visualization.effectplans

import ch.oliverlanz.memento.infrastructure.time.GameHours
import kotlin.math.floor

/** Discrete bursts emitted at fixed game-time intervals. */
data class PulsatingEffectPlan(
    var pulseEveryGameHours: Double = 0.10,
    var emissionsPerPulse: Int = 1,
) : EffectPlan {

    private var samples: List<EffectPlan.BoundSample> = emptyList()
    private var elapsedGameHours: GameHours = GameHours(0.0)
    private var emitInitialPulse: Boolean = false

    override fun updateSamples(context: EffectPlan.SampleUpdateContext) {
        samples = context.samples
        elapsedGameHours = GameHours(0.0)
        emitInitialPulse = samples.isNotEmpty()
    }

    override fun tick(context: EffectPlan.TickContext) {
        if (pulseEveryGameHours <= 0.0 || emissionsPerPulse <= 0) return
        if (samples.isEmpty()) return

        if (emitInitialPulse) {
            repeat(emissionsPerPulse) {
                val base = samples[context.random.nextInt(samples.size)]
                context.executionSurface.emit(base)
            }
            emitInitialPulse = false
        }

        if (context.deltaGameHours.value <= 0.0) return

        val updated = GameHours(elapsedGameHours.value + context.deltaGameHours.value)
        val pulses = floor(updated.value / pulseEveryGameHours).toInt()
        elapsedGameHours = GameHours(updated.value - (pulses * pulseEveryGameHours))
        if (pulses <= 0) return

        repeat(pulses * emissionsPerPulse) {
            val base = samples[context.random.nextInt(samples.size)]
            context.executionSurface.emit(base)
        }
    }
}
