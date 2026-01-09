package ch.oliverlanz.memento.application.visualization

import ch.oliverlanz.memento.domain.stones.LorestoneView
import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.particle.ParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.Heightmap
import kotlin.random.Random

/**
 * Shared particle emission helpers.
 *
 * This is intentionally separate from the engine and effect lifecycle concerns.
 * Effects can call these helpers to preserve visual behavior while the engine
 * is refactored toward long-lived projections.
 */
internal object StoneParticleEmitters {

    /**
     * Current visualization behavior for a stone creation event:
     * - anchor at stone position
     * - light dust on the surface of the stone's chunk
     *
     * NOTE: This intentionally ignores topology and influence radius for now.
     */
    fun emitStoneCreated(world: ServerWorld, stone: StoneView) {
        // Anchor visualization at stone position (slow, intentional)
        spawnStoneAnchor(world, stone)

        // Chunk enumeration (single chunk derived from stone position)
        val chunkPos = ChunkPos(stone.position)

        // Surface dust
        spawnChunkSurfaceDust(world, stone, chunkPos)
    }

    private fun spawnStoneAnchor(world: ServerWorld, stone: StoneView) {
        when (stone) {
            is WitherstoneView -> {
                // Active, slightly ominous
                world.spawnParticles(
                    ParticleTypes.ASH,
                    stone.position.x + 0.5,
                    stone.position.y + 1.2,
                    stone.position.z + 0.5,
                    10,
                    0.20,
                    0.20,
                    0.20,
                    0.001
                )
            }

            is LorestoneView -> {
                // Calm, protective, stable
                world.spawnParticles(
                    ParticleTypes.HAPPY_VILLAGER,
                    stone.position.x + 0.5,
                    stone.position.y + 1.2,
                    stone.position.z + 0.5,
                    8,
                    0.15,
                    0.15,
                    0.15,
                    0.001
                )
            }

            else -> {
                // Fallback: unknown stone types (future)
                world.spawnParticles(
                    ParticleTypes.END_ROD,
                    stone.position.x + 0.5,
                    stone.position.y + 1.2,
                    stone.position.z + 0.5,
                    6,
                    0.15,
                    0.15,
                    0.15,
                    0.001
                )
            }
        }
    }

    private fun spawnChunkSurfaceDust(world: ServerWorld, stone: StoneView, chunkPos: ChunkPos) {
        val points = enumerateChunkSurface(world, chunkPos)

        val particle: ParticleEffect = when (stone) {
            is WitherstoneView -> ParticleTypes.SMOKE
            is LorestoneView -> ParticleTypes.WAX_ON
            else -> ParticleTypes.END_ROD
        }

        // Keep it light: sample a handful of positions to avoid flooding.
        val random = Random(stone.position.asLong())
        val sample = if (points.size <= 32) points else points.shuffled(random).take(32)

        for (p in sample) {
            world.spawnParticles(
                particle,
                p.x + 0.5,
                p.y + 1.0,
                p.z + 0.5,
                1,
                0.0,
                0.0,
                0.0,
                0.0
            )
        }
    }

    /**
     * Enumerate the top surface of a chunk using MOTION_BLOCKING_NO_LEAVES.
     *
     * This prefers ground over canopy and avoids floating visuals in forests.
     */
    private fun enumerateChunkSurface(world: ServerWorld, chunkPos: ChunkPos): List<BlockPos> {
        val baseX = chunkPos.x shl 4
        val baseZ = chunkPos.z shl 4

        val out = ArrayList<BlockPos>(16 * 16)
        for (dx in 0 until 16) {
            val x = baseX + dx
            for (dz in 0 until 16) {
                val z = baseZ + dz
                val topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)
                out.add(BlockPos(x, topY, z))
            }
        }
        return out
    }
}
