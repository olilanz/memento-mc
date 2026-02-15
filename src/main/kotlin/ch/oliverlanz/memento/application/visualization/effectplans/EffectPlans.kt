package ch.oliverlanz.memento.application.visualization.effectplans

import ch.oliverlanz.memento.infrastructure.time.GameHours
import net.minecraft.util.math.BlockPos
import java.util.Random as JavaRandom
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Composable execution strategy for effect emissions.
 *
 * Plans are lane-agnostic and operate only on candidate positions plus game-time delta.
 * Mutable runtime state is owned by each plan instance.
 */
interface EffectPlan {
    data class ExecutionContext(
        val deltaGameHours: GameHours,
        val candidates: List<BlockPos>,
        val random: JavaRandom,
        val emit: (BlockPos) -> Unit,
    )

    fun tick(context: ExecutionContext)
}

/** Steady stochastic trickle paced by game-time throughput. */
data class RateEffectPlan(
    var emissionsPerGameHour: Int = 0,
) : EffectPlan {
    override fun tick(context: EffectPlan.ExecutionContext) {
        val occurrences = occurrencesForRate(emissionsPerGameHour, context.deltaGameHours)
        if (occurrences <= 0) return
        if (context.candidates.isEmpty()) return

        repeat(occurrences) {
            val base = context.candidates[context.random.nextInt(context.candidates.size)]
            context.emit(base)
        }
    }
}

/** Discrete bursts emitted at fixed game-time intervals. */
data class PulsatingEffectPlan(
    var pulseEveryGameHours: Double = 0.10,
    var emissionsPerPulse: Int = 1,
) : EffectPlan {
    private var elapsedGameHours: GameHours = GameHours(0.0)

    override fun tick(context: EffectPlan.ExecutionContext) {
        if (pulseEveryGameHours <= 0.0 || emissionsPerPulse <= 0) return
        if (context.deltaGameHours.value <= 0.0) return
        if (context.candidates.isEmpty()) return

        val updated = GameHours(elapsedGameHours.value + context.deltaGameHours.value)
        val pulses = floor(updated.value / pulseEveryGameHours).toInt()
        elapsedGameHours = GameHours(updated.value - (pulses * pulseEveryGameHours))
        if (pulses <= 0) return

        repeat(pulses * emissionsPerPulse) {
            val base = context.candidates[context.random.nextInt(context.candidates.size)]
            context.emit(base)
        }
    }
}

/**
 * One true cursor runs along a wrapped path at speed in chunks/game-hour.
 *
 * Trail emissions are produced every [maxCursorSpacingBlocks] while advancing,
 * creating a pseudo-multi-cursor illusion.
 */
data class RunningEffectPlan(
    var speedChunksPerGameHour: Double = 0.0,
    var maxCursorSpacingBlocks: Int = 16,
) : EffectPlan {
    private var absoluteDistanceBlocks: Double = 0.0
    private var nextEmissionDistanceBlocks: Double = 0.0

    override fun tick(context: EffectPlan.ExecutionContext) {
        if (context.deltaGameHours.value <= 0.0) return
        if (context.candidates.isEmpty()) return

        val path = RunningPath.from(context.candidates)
        if (path.totalLengthBlocks <= 0.0) return

        val spacing = maxCursorSpacingBlocks.coerceAtLeast(1).toDouble()
        val speedBlocksPerHour = speedChunksPerGameHour.coerceAtLeast(0.0) * 16.0
        val travelDistance = speedBlocksPerHour * context.deltaGameHours.value
        if (travelDistance <= 0.0) return

        val currentAbsolute = absoluteDistanceBlocks + travelDistance
        while (nextEmissionDistanceBlocks <= currentAbsolute) {
            val pos = path.positionAtWrappedDistance(nextEmissionDistanceBlocks)
            context.emit(pos)
            nextEmissionDistanceBlocks += spacing
        }
        absoluteDistanceBlocks = currentAbsolute
    }

    private data class RunningPath(
        val points: List<BlockPos>,
        val cumulativeSegmentLength: List<Double>,
        val totalLengthBlocks: Double,
    ) {
        companion object {
            fun from(points: List<BlockPos>): RunningPath {
                if (points.isEmpty()) return RunningPath(emptyList(), emptyList(), 0.0)
                if (points.size == 1) return RunningPath(points, listOf(0.0), 0.0)

                val cumulative = ArrayList<Double>(points.size)
                var acc = 0.0
                cumulative.add(acc)

                for (i in 0 until points.size - 1) {
                    acc += segmentLength(points[i], points[i + 1])
                    cumulative.add(acc)
                }
                acc += segmentLength(points.last(), points.first())

                return RunningPath(
                    points = points,
                    cumulativeSegmentLength = cumulative,
                    totalLengthBlocks = acc,
                )
            }

            private fun segmentLength(a: BlockPos, b: BlockPos): Double {
                val dx = (b.x - a.x).toDouble()
                val dz = (b.z - a.z).toDouble()
                return hypot(dx, dz)
            }
        }

        fun positionAtWrappedDistance(distance: Double): BlockPos {
            if (points.isEmpty()) return BlockPos(0, 0, 0)
            if (points.size == 1 || totalLengthBlocks <= 0.0) return points.first()

            val wrapped = ((distance % totalLengthBlocks) + totalLengthBlocks) % totalLengthBlocks
            var index = cumulativeSegmentLength.binarySearch(wrapped)
            if (index < 0) index = (-index - 2).coerceAtLeast(0)
            if (index >= points.size) index = points.lastIndex

            val start = points[index]
            val end = points[(index + 1) % points.size]
            val segStart = cumulativeSegmentLength[index]
            val segLen = segmentLength(start, end)
            if (segLen <= 0.0) return start

            val t = ((wrapped - segStart) / segLen).coerceIn(0.0, 1.0)
            val x = start.x + (end.x - start.x) * t
            val z = start.z + (end.z - start.z) * t

            return BlockPos(x.roundToInt(), start.y, z.roundToInt())
        }

        private fun segmentLength(a: BlockPos, b: BlockPos): Double {
            val dx = (b.x - a.x).toDouble()
            val dz = (b.z - a.z).toDouble()
            return hypot(dx, dz)
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
