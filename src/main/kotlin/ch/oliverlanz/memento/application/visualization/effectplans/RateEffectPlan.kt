package ch.oliverlanz.memento.application.visualization.effectplans

import ch.oliverlanz.memento.infrastructure.time.GameHours
import kotlin.math.roundToInt

/** Steady stochastic trickle paced by game-time throughput. */
data class RateEffectPlan(
    /** Per-game-hour selection density over bound samples (0.0..1.0). */
    var selectionDensityPerGameHour: Double = 0.0,
) : EffectPlan {

    private var samples: List<EffectPlan.BoundSample> = emptyList()

    override fun updateSamples(context: EffectPlan.SampleUpdateContext) {
        samples = context.samples
    }

    override fun tick(context: EffectPlan.TickContext) {
        val density = densityForDelta(selectionDensityPerGameHour, context.deltaGameHours)
        if (density <= 0.0) return
        if (samples.isEmpty()) return

        val target = (samples.size * density).roundToInt().coerceIn(0, samples.size)
        if (target <= 0) return

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

private fun densityForDelta(
    selectionDensityPerGameHour: Double,
    deltaGameHours: GameHours,
): Double {
    if (selectionDensityPerGameHour <= 0.0) return 0.0
    if (deltaGameHours.value <= 0.0) return 0.0
    return (selectionDensityPerGameHour * deltaGameHours.value).coerceIn(0.0, 1.0)
}
