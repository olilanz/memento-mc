package ch.oliverlanz.memento.domain.stones

import ch.oliverlanz.memento.domain.harness.fixtures.StoneFixtureBuilder
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StoneAuthorityTopologyDeterminismTest {

    @Test
    fun stone_fixture_builder_is_deterministic_for_identical_inputs() {
        val world = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft:overworld"))

        val a = StoneFixtureBuilder()
            .wither("w1", world, BlockPos(0, 64, 0), radius = 2, daysToMaturity = 1)
            .lore("l1", world, BlockPos(16, 64, 16), radius = 3)
            .build()

        val b = StoneFixtureBuilder()
            .wither("w1", world, BlockPos(0, 64, 0), radius = 2, daysToMaturity = 1)
            .lore("l1", world, BlockPos(16, 64, 16), radius = 3)
            .build()

        assertEquals(a, b)
    }

    @Test
    fun stone_fixture_builder_contains_only_input_placements_not_dominance_outputs() {
        val world = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft:overworld"))
        val (withers, lores) = StoneFixtureBuilder()
            .wither("w1", world, BlockPos(0, 64, 0), radius = 2, daysToMaturity = 1)
            .lore("l1", world, BlockPos(16, 64, 16), radius = 3)
            .build()

        assertEquals(1, withers.size)
        assertEquals(1, lores.size)
        assertFalse(withers.any { it.name == "dominant" })
        assertFalse(lores.any { it.name == "dominant" })
    }
}
