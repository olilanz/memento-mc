package ch.oliverlanz.memento.application.visualization.effectplans

import ch.oliverlanz.memento.infrastructure.time.GameHours
import kotlin.math.floor

/** Discrete bursts emitted at fixed game-time intervals. */
data class PulsatingEffectPlan(
    var pulseEveryGameHours: Double = 0.10,
    /** Per-pulse selection density over bound samples (0.0..1.0). */
    var selectionDensityPerPulse: Double = 1.0,
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
        if (pulseEveryGameHours <= 0.0 || selectionDensityPerPulse <= 0.0) return
        if (samples.isEmpty()) return

        if (emitInitialPulse) {
            emitDensitySelection(context, pulseCount = 1)
            emitInitialPulse = false
        }

        if (context.deltaGameHours.value <= 0.0) return

        val updated = GameHours(elapsedGameHours.value + context.deltaGameHours.value)
        val pulses = floor(updated.value / pulseEveryGameHours).toInt()
        elapsedGameHours = GameHours(updated.value - (pulses * pulseEveryGameHours))
        if (pulses <= 0) return

        emitDensitySelection(context, pulseCount = pulses)
    }

    private fun emitDensitySelection(
        context: EffectPlan.TickContext,
        pulseCount: Int,
    ) {
        if (pulseCount <= 0) return
        val density = selectionDensityPerPulse.coerceIn(0.0, 1.0)

        repeat(pulseCount) {
            val target = kotlin.math.round(samples.size * density).toInt().coerceIn(0, samples.size)
            if (target <= 0) return@repeat

            // Start with all candidates, then randomly remove until target size remains.
            val selected = samples.toMutableList()
            while (selected.size > target) {
                val removeIndex = context.random.nextInt(selected.size)
                selected.removeAt(removeIndex)
            }

            for (sample in selected) {
                context.executionSurface.emit(sample)
            }
        }
    }
}
