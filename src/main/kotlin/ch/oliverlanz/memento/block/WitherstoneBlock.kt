package ch.oliverlanz.memento.block

import net.minecraft.block.Block
import net.minecraft.block.AbstractBlock
import net.minecraft.block.BlockState
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.Registries
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.random.Random
import net.minecraft.world.World

class WitherstoneBlock(settings: AbstractBlock.Settings) : Block(settings) {

    override fun onBlockAdded(
        state: BlockState,
        world: World,
        pos: BlockPos,
        oldState: BlockState,
        notify: Boolean
    ) {
        if (!world.isClient) {
            world.scheduleBlockTick(pos, this, 10)
        }
    }

    override fun scheduledTick(
        state: BlockState,
        world: ServerWorld,
        pos: BlockPos,
        random: Random
    ) {
        world.spawnParticles(
            ParticleTypes.SOUL,
            pos.x + 0.5, pos.y + 1.1, pos.z + 0.5,
            2,
            0.0, 0.0, 0.0,
            0.03
        )

        world.scheduleBlockTick(pos, this, 10)
    }
}
