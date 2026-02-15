package ch.oliverlanz.memento.application.visualization.samplers

import ch.oliverlanz.memento.domain.stones.StoneMapService
import ch.oliverlanz.memento.domain.stones.StoneView
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.Heightmap

/**
 * Samples a single-block-thick surface path that follows the border of
 * influenced chunks for this stone.
 *
 * This is intentionally chunk-border based so it visualizes the exact
 * start/stop frontier used by renewal/protection semantics.
 */
class InfluenceOutlineSurfaceSampler(
    private val stone: StoneView,
) : StoneSampler {

    private var cached: List<BlockPos>? = null

    override fun candidates(world: ServerWorld): List<BlockPos> {
        cached?.let { return it }

        val influenced = StoneMapService.influencedChunks(stone)
        if (influenced.isEmpty()) {
            cached = emptyList()
            return emptyList()
        }

        val borderPoints = linkedSetOf<Xz>()

        for (chunk in influenced) {
            val baseX = chunk.x shl 4
            val baseZ = chunk.z shl 4
            val north = ChunkPos(chunk.x, chunk.z - 1)
            val south = ChunkPos(chunk.x, chunk.z + 1)
            val west = ChunkPos(chunk.x - 1, chunk.z)
            val east = ChunkPos(chunk.x + 1, chunk.z)

            if (north !in influenced) {
                for (x in baseX..(baseX + 15)) borderPoints.add(Xz(x, baseZ))
            }
            if (south !in influenced) {
                for (x in baseX..(baseX + 15)) borderPoints.add(Xz(x, baseZ + 15))
            }
            if (west !in influenced) {
                for (z in baseZ..(baseZ + 15)) borderPoints.add(Xz(baseX, z))
            }
            if (east !in influenced) {
                for (z in baseZ..(baseZ + 15)) borderPoints.add(Xz(baseX + 15, z))
            }
        }

        val ordered = orderBoundary(borderPoints)
        val immutable = ordered.map { p ->
            val topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, p.x, p.z)
            BlockPos(p.x, topY, p.z)
        }

        cached = immutable
        return immutable
    }

    private fun orderBoundary(points: Set<Xz>): List<Xz> {
        if (points.isEmpty()) return emptyList()

        val pointsSet = points.toHashSet()
        val adjacency = HashMap<Xz, List<Xz>>(points.size)
        for (p in points) {
            val neighbors = ArrayList<Xz>(4)
            val north = Xz(p.x, p.z - 1)
            val south = Xz(p.x, p.z + 1)
            val west = Xz(p.x - 1, p.z)
            val east = Xz(p.x + 1, p.z)
            if (north in pointsSet) neighbors.add(north)
            if (south in pointsSet) neighbors.add(south)
            if (west in pointsSet) neighbors.add(west)
            if (east in pointsSet) neighbors.add(east)
            adjacency[p] = neighbors
        }

        val remaining = points.toMutableSet()
        val ordered = ArrayList<Xz>(points.size)

        while (remaining.isNotEmpty()) {
            val start = remaining.minWithOrNull(compareBy<Xz>({ it.x }, { it.z })) ?: break
            var previous: Xz? = null
            var current = start

            while (true) {
                if (current !in remaining) break
                ordered.add(current)
                remaining.remove(current)

                val candidates = adjacency[current]
                    .orEmpty()
                    .filter { it in remaining }
                if (candidates.isEmpty()) break

                val next = chooseNext(previous, current, candidates)
                previous = current
                current = next
            }
        }

        return ordered
    }

    private fun chooseNext(previous: Xz?, current: Xz, candidates: List<Xz>): Xz {
        if (candidates.size == 1) return candidates.first()
        if (previous == null) return candidates.minWithOrNull(compareBy<Xz>({ it.x }, { it.z }))!!

        val vx = current.x - previous.x
        val vz = current.z - previous.z
        return candidates
            .maxWithOrNull(
                compareBy<Xz>({ (it.x - current.x) * vx + (it.z - current.z) * vz }, { -it.x }, { -it.z })
            )!!
    }

    private data class Xz(val x: Int, val z: Int)
}
