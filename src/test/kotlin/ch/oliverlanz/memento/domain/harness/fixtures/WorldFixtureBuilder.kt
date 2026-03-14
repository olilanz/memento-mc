package ch.oliverlanz.memento.domain.harness.fixtures

import ch.oliverlanz.memento.domain.harness.TestWorldModel
import ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World

class WorldFixtureBuilder {
    private val modelBuilder = TestWorldModel.Builder()

    fun linearRegions(
        world: RegistryKey<World>,
        startRegionX: Int,
        count: Int,
        regionZ: Int,
        inhabitedTimeTicks: Long,
        source: ChunkScanProvenance = ChunkScanProvenance.FILE_PRIMARY,
        scanTickStart: Long = 1L,
    ): WorldFixtureBuilder {
        require(count >= 0) { "count must be >= 0" }

        for (i in 0 until count) {
            val regionX = startRegionX + i
            val chunkX = regionX * 32
            val chunkZ = regionZ * 32
            modelBuilder.chunk(
                world = world,
                chunkX = chunkX,
                chunkZ = chunkZ,
                inhabitedTimeTicks = inhabitedTimeTicks,
                isSpawnChunk = false,
                source = source,
                scanTick = scanTickStart + i,
            )
        }

        return this
    }

    fun singleChunk(
        world: RegistryKey<World>,
        chunkX: Int,
        chunkZ: Int,
        inhabitedTimeTicks: Long,
        scanTick: Long = 1L,
        isSpawnChunk: Boolean = false,
    ): WorldFixtureBuilder {
        modelBuilder.chunk(
            world = world,
            chunkX = chunkX,
            chunkZ = chunkZ,
            inhabitedTimeTicks = inhabitedTimeTicks,
            isSpawnChunk = isSpawnChunk,
            scanTick = scanTick,
        )
        return this
    }

    fun build(): TestWorldModel = modelBuilder.build()

    companion object {
        fun overworld(): RegistryKey<World> = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft:overworld"))
    }
}
