package ch.oliverlanz.memento.application.visualization.effectplans

import ch.oliverlanz.memento.infrastructure.time.GameHours
import net.minecraft.util.math.BlockPos
import kotlin.math.floor
import kotlin.random.Random

/** Steady stochastic trickle paced by game-time throughput. */
data class RateEffectPlan(
    var emissionsPerGameHour: Int = 0,
) : EffectPlan {

    private var samples: List<BlockPos> = emptyList()

    override fun initialize(context: EffectPlan.InitializeContext) {
        samples = context.samples
    }

    override fun tick(context: EffectPlan.TickContext) {
        val occurrences = occurrencesForRate(emissionsPerGameHour, context.deltaGameHours)
        if (occurrences <= 0) return
        if (samples.isEmpty()) return

        repeat(occurrences) {
            val base = samples[context.random.nextInt(samples.size)]
            context.emit(base)
        }
    }
}

private fun occurrencesForRate(
    emissionsPerGameHour: Int,
    deltaGameHours: GameHours,
): Int {
    if (emissionsPerGameHour <= 0) return 0
    if (deltaGameHours.value <= 0.0) return 0

    val expected = emissionsPerGameHour.toDouble() * deltaGameHours.value
    if (expected <= 0.0) return 0

    val k = floor(expected).toInt()
    val frac = expected - k
    return k + if (Random.nextDouble() < frac) 1 else 0
}

