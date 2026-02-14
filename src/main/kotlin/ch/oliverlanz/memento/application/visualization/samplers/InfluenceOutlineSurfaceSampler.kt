package ch.oliverlanz.memento.application.visualization.samplers

import ch.oliverlanz.memento.domain.stones.StoneSpatial
import ch.oliverlanz.memento.domain.stones.StoneView
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.world.Heightmap
import kotlin.math.abs

/**
 * Samples a block-space ring around the stone influence radius.
 *
 * The ring is world-space geometry and independent of chunk borders.
 */
class InfluenceOutlineSurfaceSampler(
    private val stone: StoneView,
    private val thicknessBlocks: Int = 4,
) : StoneSampler {

    private var cached: List<BlockPos>? = null

    override fun candidates(world: ServerWorld): List<BlockPos> {
        cached?.let { return it }

        val cx = stone.position.x
        val cz = stone.position.z
        val radius = StoneSpatial.influenceRadiusBlocks(stone)
        val inner = (radius - thicknessBlocks).coerceAtLeast(0)
        val outer = radius + thicknessBlocks
        val inner2 = inner.toLong() * inner.toLong()
        val outer2 = outer.toLong() * outer.toLong()

        val out = ArrayList<BlockPos>(outer * 24)
        for (dx in -outer..outer) {
            val x = cx + dx
            for (dz in -outer..outer) {
                val dist2 = dx.toLong() * dx.toLong() + dz.toLong() * dz.toLong()
                if (dist2 < inner2 || dist2 > outer2) continue

                val z = cz + dz
                val topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)
                out.add(BlockPos(x, topY, z))
            }
        }

        val immutable = out
            .sortedWith(compareBy<BlockPos>({ abs(it.x - cx) + abs(it.z - cz) }, { it.x }, { it.z }))
            .toList()

        cached = immutable
        return immutable
    }
}
