package ch.oliverlanz.memento.application.visualization.samplers

import ch.oliverlanz.memento.domain.stones.StoneView
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.Heightmap

/**
 * Samples the top surface blocks of the chunk that contains the stone.
 *
 * Uses MOTION_BLOCKING_NO_LEAVES to prefer ground over canopy and avoid floating visuals.
 */
class SingleChunkSurfaceSampler(
    private val stone: StoneView,
    /**
     * Optional deterministic subset size.
     *
     * Locked semantics: subset selection is a sampling concern.
     */
    private val subsetSize: Int? = null,
) : StoneSampler {

    override fun sample(world: ServerWorld): Set<BlockPos> {
        val chunkPos = ChunkPos(stone.position)
        val baseX = chunkPos.x shl 4
        val baseZ = chunkPos.z shl 4

        val out = HashSet<BlockPos>(16 * 16)
        for (dx in 0 until 16) {
            val x = baseX + dx
            for (dz in 0 until 16) {
                val z = baseZ + dz
                val topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)
                out.add(BlockPos(x, topY, z))
            }
        }

        val n = subsetSize
        if (n == null || n <= 0 || out.size <= n) return out

        // Deterministic subset derived from the stone position.
        val rnd = kotlin.random.Random(stone.position.asLong())
        return out.shuffled(rnd).take(n).toSet()
    }
}
