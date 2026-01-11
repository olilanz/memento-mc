package ch.oliverlanz.memento.application.visualization.emitters

import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.world.Heightmap
import kotlin.random.Random

/**
 * Utility for sampling surface positions within a single chunk
 * and emitting particles above them.
 *
 * This is intentionally simple and mechanical:
 * - no topology awareness
 * - no samplers abstraction yet
 * - no effect logic
 */
object SurfaceParticleEmitter {

    /**
     * Emits a particle at a random surface position in the given chunk.
     *
     * @param world server world
     * @param chunkX chunk x-coordinate
     * @param chunkZ chunk z-coordinate
     * @param emit function that performs the actual particle emission
     */
    fun emitRandomSurfacePosition(
        world: ServerWorld,
        chunkX: Int,
        chunkZ: Int,
        emit: (BlockPos) -> Unit
    ) {
        val localX = Random.nextInt(16)
        val localZ = Random.nextInt(16)

        val worldX = (chunkX shl 4) + localX
        val worldZ = (chunkZ shl 4) + localZ

        val surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ)
        val surfacePos = BlockPos(worldX, surfaceY, worldZ)

        emit(surfacePos)
    }
}