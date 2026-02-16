package ch.oliverlanz.memento.application.visualization.samplers

import ch.oliverlanz.memento.domain.stones.StoneMapService
import ch.oliverlanz.memento.domain.stones.StoneView
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.Heightmap

/**
 * Samples single-block-thick surface borders for all dominant-category regions
 * inside this stone's influenced chunk mask.
 *
 * Border rule per chunk side:
 * - Include when neighbor is outside the influenced mask, or
 * - Include when neighbor inside the mask has a different dominant category.
 *
 * This renders outer borders and internal dominance seams (double-line at
 * category transitions, one block from each side).
 */
class InfluenceOutlineSurfaceSampler(
    private val stone: StoneView,
) : StoneSampler {

    private var cached: List<BlockPos>? = null

    override fun candidates(world: ServerWorld): List<BlockPos> {
        cached?.let { return it }

        val mask = StoneMapService.influencedChunks(stone)
        if (mask.isEmpty()) {
            cached = emptyList()
            return emptyList()
        }
        val dominantByChunk = StoneMapService.dominantByChunk(stone.dimension)

        val borderPoints = linkedSetOf<Xz>()

        for (chunk in mask) {
            val category = dominantByChunk[chunk]
            val baseX = chunk.x shl 4
            val baseZ = chunk.z shl 4
            val north = ChunkPos(chunk.x, chunk.z - 1)
            val south = ChunkPos(chunk.x, chunk.z + 1)
            val west = ChunkPos(chunk.x - 1, chunk.z)
            val east = ChunkPos(chunk.x + 1, chunk.z)

            if (north !in mask || dominantByChunk[north] != category) {
                for (x in baseX..(baseX + 15)) borderPoints.add(Xz(x, baseZ))
            }
            if (south !in mask || dominantByChunk[south] != category) {
                for (x in baseX..(baseX + 15)) borderPoints.add(Xz(x, baseZ + 15))
            }
            if (west !in mask || dominantByChunk[west] != category) {
                for (z in baseZ..(baseZ + 15)) borderPoints.add(Xz(baseX, z))
            }
            if (east !in mask || dominantByChunk[east] != category) {
                for (z in baseZ..(baseZ + 15)) borderPoints.add(Xz(baseX + 15, z))
            }
        }

        val immutable = borderPoints
            .sortedWith(compareBy<Xz>({ it.x }, { it.z }))
            .map { p ->
            val topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, p.x, p.z)
            BlockPos(p.x, topY, p.z)
        }

        cached = immutable
        return immutable
    }

    private data class Xz(val x: Int, val z: Int)
}
