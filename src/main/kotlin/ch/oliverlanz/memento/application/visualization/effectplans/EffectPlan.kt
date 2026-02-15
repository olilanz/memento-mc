package ch.oliverlanz.memento.application.visualization.effectplans

import ch.oliverlanz.memento.infrastructure.time.GameHours
import net.minecraft.util.math.BlockPos
import java.util.Random as JavaRandom

/**
 * Composable execution strategy for effect emissions.
 *
 * Plans are lane-agnostic. They are initialized once with materialized samples,
 * then ticked with game-time deltas.
 */
interface EffectPlan {
    data class BoundSample(
        val pos: BlockPos,
        val emissionToken: Any,
    )

    data class SampleUpdateContext(
        val samples: List<BoundSample>,
        val random: JavaRandom,
    )

    interface ExecutionSurface {
        fun emit(sample: BoundSample)
    }

    data class TickContext(
        val deltaGameHours: GameHours,
        val random: JavaRandom,
        val executionSurface: ExecutionSurface,
    )

    fun updateSamples(context: SampleUpdateContext)

    fun tick(context: TickContext)
}
