package ch.oliverlanz.memento.domain.harness.fixtures

import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

/**
 * Stone fixture inputs only.
 *
 * This builder intentionally does not compute or inject dominance outputs.
 */
class StoneFixtureBuilder {
    data class WitherPlacement(
        val name: String,
        val world: RegistryKey<World>,
        val position: BlockPos,
        val radius: Int,
        val daysToMaturity: Int,
    )

    data class LorePlacement(
        val name: String,
        val world: RegistryKey<World>,
        val position: BlockPos,
        val radius: Int,
    )

    private val withers = mutableListOf<WitherPlacement>()
    private val lores = mutableListOf<LorePlacement>()

    fun wither(
        name: String,
        world: RegistryKey<World>,
        position: BlockPos,
        radius: Int,
        daysToMaturity: Int,
    ): StoneFixtureBuilder {
        withers += WitherPlacement(name, world, position, radius, daysToMaturity)
        return this
    }

    fun lore(
        name: String,
        world: RegistryKey<World>,
        position: BlockPos,
        radius: Int,
    ): StoneFixtureBuilder {
        lores += LorePlacement(name, world, position, radius)
        return this
    }

    fun build(): Pair<List<WitherPlacement>, List<LorePlacement>> = withers.toList() to lores.toList()
}

