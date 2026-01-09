package ch.oliverlanz.memento.application.visualization

import ch.oliverlanz.memento.domain.events.StoneCreated
import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.stones.LorestoneView
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.particle.ParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.Heightmap
import kotlin.random.Random

/**
 * Visualization is an application-layer concern.
 *
 * Current slice:
 * - React to StoneCreated (known-good trigger)
 * - Always emit particles at stone position (UX + debug anchor)
 * - Additionally emit surface particles in the stone's chunk
 *
 * No topology queries.
 * No lifecycle awareness.
 * One-shot emission only.
 */
object StoneVisualizationEngine {

    private var server: MinecraftServer? = null

    fun attach(server: MinecraftServer) {
        this.server = server
        StoneDomainEvents.subscribeToStoneCreated(::onStoneCreated)
    }

    fun detach() {
        StoneDomainEvents.unsubscribeFromStoneCreated(::onStoneCreated)
        this.server = null
    }

    private fun onStoneCreated(event: StoneCreated) {
        val server = this.server ?: return
        val stone = event.stone
        val world: ServerWorld = server.getWorld(stone.dimension) ?: return

        // ------------------------------------------------------------
        // Anchor visualization at stone position (slow, intentional)
        // ------------------------------------------------------------
        spawnStoneAnchor(world, stone)

        // ------------------------------------------------------------
        // Chunk enumeration (single chunk derived from stone position)
        // ------------------------------------------------------------
        val chunkPos = ChunkPos(stone.position)

        // ------------------------------------------------------------
        // Surface enumeration + random sampling (scaffold)
        // ------------------------------------------------------------
        val surfacePositions = enumerateChunkSurface(world, chunkPos)
        val sampled = sample(surfacePositions, sampleCount = 64, rng = Random.Default)

        // ------------------------------------------------------------
        // One-shot surface emission (subtle, secondary)
        // ------------------------------------------------------------
        val surfaceParticle: ParticleEffect =
            when (stone) {
                is WitherstoneView -> ParticleTypes.SMOKE
                is LorestoneView -> ParticleTypes.HAPPY_VILLAGER
                else -> ParticleTypes.END_ROD
            }

        emitSurface(world, surfaceParticle, sampled)
    }

    private fun spawnStoneAnchor(world: ServerWorld, stone: ch.oliverlanz.memento.domain.stones.StoneView) {
        when (stone) {
            is WitherstoneView -> {
                // Slow, creeping, ominous
                world.spawnParticles(
                    ParticleTypes.SMOKE,
                    false,
                    true,
                    stone.position.x + 0.5,
                    stone.position.y + 1.2,
                    stone.position.z + 0.5,
                    6,
                    0.15,
                    0.15,
                    0.15,
                    0.002
                )
            }

            is LorestoneView -> {
                // Calm, protective, stable
                world.spawnParticles(
                    ParticleTypes.HAPPY_VILLAGER,
                    false,
                    true,
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
                    false,
                    true,
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

    /**
     * Enumerate surface positions using MOTION_BLOCKING_NO_LEAVES.
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

    /**
     * Random sampling with replacement (duplicates allowed).
     * Intentional scaffold for later refinement.
     */
    private fun <T> sample(source: List<T>, sampleCount: Int, rng: Random): List<T> {
        if (source.isEmpty() || sampleCount <= 0) return emptyList()
        val out = ArrayList<T>(sampleCount)
        repeat(sampleCount) {
            out.add(source[rng.nextInt(source.size)])
        }
        return out
    }

    /**
     * Emit particles slightly above surface blocks to avoid culling.
     */
    private fun emitSurface(world: ServerWorld, particle: ParticleEffect, positions: List<BlockPos>) {
        for (p in positions) {
            world.spawnParticles(
                particle,
                false,
                true,
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
}
