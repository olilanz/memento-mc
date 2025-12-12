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

class LorestoneBlock(settings: AbstractBlock.Settings) : Block(settings) {

    override fun onBlockAdded(
        state: BlockState,
        world: World,
        pos: BlockPos,
        oldState: BlockState,
        notify: Boolean
    ) {
        if (!world.isClient) {
            world.scheduleBlockTick(pos, this, 10)  // 10 ticks = 0.5s
        }
    }

    override fun scheduledTick(
        state: BlockState,
        world: ServerWorld,
        pos: BlockPos,
        random: Random
    ) {
        world.spawnParticles(
            ParticleTypes.ENCHANT,
            pos.x + 0.5, pos.y + 1.1, pos.z + 0.5,
            3,
            0.0, 0.2, 0.0,
            0.02
        )

        // Reschedule next tick
        world.scheduleBlockTick(pos, this, 10)
    }
}
