package ch.oliverlanz.memento.application.visualization.effectplans

import net.minecraft.util.math.BlockPos
import kotlin.math.hypot
import kotlin.math.roundToInt

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

    private var path: RunningPath = RunningPath.empty()
    private var absoluteDistanceBlocks: Double = 0.0
    private var nextEmissionDistanceBlocks: Double = 0.0

    override fun updateSamples(context: EffectPlan.SampleUpdateContext) {
        path = RunningPath.from(context.samples)
        absoluteDistanceBlocks = 0.0
        nextEmissionDistanceBlocks = 0.0
    }

    override fun tick(context: EffectPlan.TickContext) {
        if (context.deltaGameHours.value <= 0.0) return
        if (path.totalLengthBlocks <= 0.0) return

        val spacing = maxCursorSpacingBlocks.coerceAtLeast(1).toDouble()
        val speedBlocksPerHour = speedChunksPerGameHour.coerceAtLeast(0.0) * 16.0
        val travelDistance = speedBlocksPerHour * context.deltaGameHours.value
        if (travelDistance <= 0.0) return

        val currentAbsolute = absoluteDistanceBlocks + travelDistance
        while (nextEmissionDistanceBlocks <= currentAbsolute) {
            val sample = path.sampleAtWrappedDistance(nextEmissionDistanceBlocks)
            context.executionSurface.emit(sample)
            nextEmissionDistanceBlocks += spacing
        }
        absoluteDistanceBlocks = currentAbsolute
    }

    private data class RunningPath(
        val points: List<EffectPlan.BoundSample>,
        val cumulativeSegmentLength: List<Double>,
        val totalLengthBlocks: Double,
    ) {
        companion object {
            fun empty(): RunningPath = RunningPath(emptyList(), emptyList(), 0.0)

            fun from(points: List<EffectPlan.BoundSample>): RunningPath {
                if (points.isEmpty()) return empty()
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

            private fun segmentLength(a: EffectPlan.BoundSample, b: EffectPlan.BoundSample): Double {
                val dx = (b.pos.x - a.pos.x).toDouble()
                val dz = (b.pos.z - a.pos.z).toDouble()
                return hypot(dx, dz)
            }
        }

        fun sampleAtWrappedDistance(distance: Double): EffectPlan.BoundSample {
            if (points.isEmpty()) return EffectPlan.BoundSample(BlockPos(0, 0, 0), emissionToken = Unit)
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
            val x = start.pos.x + (end.pos.x - start.pos.x) * t
            val z = start.pos.z + (end.pos.z - start.pos.z) * t

            return EffectPlan.BoundSample(
                pos = BlockPos(x.roundToInt(), start.pos.y, z.roundToInt()),
                emissionToken = start.emissionToken,
            )
        }

        private fun segmentLength(a: EffectPlan.BoundSample, b: EffectPlan.BoundSample): Double {
            val dx = (b.pos.x - a.pos.x).toDouble()
            val dz = (b.pos.z - a.pos.z).toDouble()
            return hypot(dx, dz)
        }
    }
}
