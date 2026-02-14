package ch.oliverlanz.memento.application.visualization.samplers

import ch.oliverlanz.memento.domain.stones.StoneMapService
import ch.oliverlanz.memento.domain.stones.StoneView
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.world.Heightmap

/**
 * Samples top-surface blocks for every chunk influenced by the stone.
 */
class InfluenceAreaSurfaceSampler(
    private val stone: StoneView,
) : StoneSampler {

    private var cached: List<BlockPos>? = null

    override fun candidates(world: ServerWorld): List<BlockPos> {
        cached?.let { return it }

        val influenced = StoneMapService.influencedChunks(stone)
        if (influenced.isEmpty()) {
            val empty = emptyList<BlockPos>()
            cached = empty
            return empty
        }

        val orderedChunks = influenced
            .sortedWith(compareBy({ it.x }, { it.z }))

        val out = ArrayList<BlockPos>(orderedChunks.size * 16 * 16)
        for (chunk in orderedChunks) {
            val baseX = chunk.x shl 4
            val baseZ = chunk.z shl 4
            for (dx in 0 until 16) {
                val x = baseX + dx
                for (dz in 0 until 16) {
                    val z = baseZ + dz
                    val topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)
                    out.add(BlockPos(x, topY, z))
                }
            }
        }

        val immutable = out.toList()
        cached = immutable
        return immutable
    }
}
