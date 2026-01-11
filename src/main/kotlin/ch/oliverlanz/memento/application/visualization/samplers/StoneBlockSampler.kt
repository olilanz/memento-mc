package ch.oliverlanz.memento.application.visualization.samplers

import ch.oliverlanz.memento.domain.stones.StoneView
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos

/**
 * Samples the stone's own block position.
 *
 * Used for anchor / identity visuals. Effects typically project this upward.
 */
class StoneBlockSampler(
    private val stone: StoneView
) : StoneSampler {

    override fun sample(world: ServerWorld): Set<BlockPos> = setOf(stone.position)
}
