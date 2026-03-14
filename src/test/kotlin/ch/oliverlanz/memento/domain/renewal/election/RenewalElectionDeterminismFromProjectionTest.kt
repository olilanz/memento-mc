package ch.oliverlanz.memento.domain.renewal.election

import ch.oliverlanz.memento.domain.renewal.projection.AmbientRenewalStrategy
import ch.oliverlanz.memento.domain.renewal.projection.RegionKey
import ch.oliverlanz.memento.domain.renewal.projection.RenewalChunkDerivation
import ch.oliverlanz.memento.domain.renewal.projection.RenewalElectionInput
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World
import kotlin.test.Test
import kotlin.test.assertEquals

class RenewalElectionDeterminismFromProjectionTest {

    @Test
    fun evaluate_is_deterministic_for_identical_projection_inputs() {
        val world = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft:overworld"))
        val region = RegionKey(worldId = "minecraft:overworld", regionX = 0, regionZ = 0)
        val chunk = ChunkKey(world = world, regionX = 0, regionZ = 0, chunkX = 0, chunkZ = 0)

        val input = RenewalElectionInput(
            generation = 42L,
            regionForgettableByRegion = mapOf(region to true),
            chunkDerivationByChunk = mapOf(
                chunk to RenewalChunkDerivation(
                    memorable = false,
                    eligibleChunkRenewal = true,
                    ambientStrategy = AmbientRenewalStrategy.NONE,
                    explicitRenewalIntent = true,
                )
            ),
        )

        val a = RenewalElection.evaluate(
            input = input,
            deterministicTransactionId = "tx-1",
            includeExplicitStoneIntent = true,
        )
        val b = RenewalElection.evaluate(
            input = input,
            deterministicTransactionId = "tx-1",
            includeExplicitStoneIntent = true,
        )

        assertEquals(a.electedRegions, b.electedRegions)
        assertEquals(a.electedChunks, b.electedChunks)
    }
}

