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
    data class InitializeContext(
        val samples: List<BlockPos>,
        val random: JavaRandom,
    )

    data class TickContext(
        val deltaGameHours: GameHours,
        val random: JavaRandom,
        val emit: (BlockPos) -> Unit,
    )

    fun initialize(context: InitializeContext)

    fun tick(context: TickContext)
}

