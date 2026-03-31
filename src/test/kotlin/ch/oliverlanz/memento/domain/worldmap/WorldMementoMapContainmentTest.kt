package ch.oliverlanz.memento.domain.worldmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World

class WorldMementoMapContainmentTest {

    @Test
    fun discovered_surface_includes_unscanned_chunks_while_scanned_surface_excludes_them() {
        val map = WorldMementoMap()
        val world = overworld()

        val discoveredOnly = key(world, 10, 10)
        val scanned = key(world, 11, 10)

        map.ensureExists(discoveredOnly)
        map.markScanned(
            key = scanned,
            scanTick = 33L,
            provenance = ChunkScanProvenance.FILE_PRIMARY,
        )

        val discoveredUniverse = map.discoveredUniverseKeys().toSet()
        val scannedSubset = map.snapshot().map { it.key }.toSet()

        assertEquals(setOf(discoveredOnly, scanned), discoveredUniverse)
        assertEquals(setOf(scanned), scannedSubset)
        assertTrue(discoveredOnly in discoveredUniverse)
        assertFalse(discoveredOnly in scannedSubset)
    }

    @Test
    fun scanned_subset_and_metadata_subset_remain_within_discovered_universe() {
        val map = WorldMementoMap()
        val world = overworld()

        val discoveredOnly = key(world, 0, 0)
        val scannedWithMetadata = key(world, 1, 0)

        map.ensureExists(discoveredOnly)
        map.markScanned(
            key = scannedWithMetadata,
            scanTick = 10L,
            provenance = ChunkScanProvenance.FILE_PRIMARY,
        )
        map.upsertSignals(
            key = scannedWithMetadata,
            signals = ChunkSignals(
                inhabitedTimeTicks = 42L,
                lastUpdateTicks = 100L,
                surfaceY = 70,
                biomeId = "minecraft:plains",
                isSpawnChunk = false,
            ),
        )

        val discoveredUniverse = map.discoveredUniverseKeys().toSet()
        val scannedSubset = map.snapshot().map { it.key }.toSet()
        val metadataSubset = map.snapshot().filter { it.signals != null }.map { it.key }.toSet()

        assertEquals(setOf(discoveredOnly, scannedWithMetadata), discoveredUniverse)
        assertEquals(setOf(scannedWithMetadata), scannedSubset)
        assertEquals(setOf(scannedWithMetadata), metadataSubset)

        assertTrue(scannedSubset.all { it in discoveredUniverse })
        assertTrue(metadataSubset.all { it in discoveredUniverse })
    }

    @Test
    fun late_discovery_after_metadata_does_not_downgrade_existing_metadata() {
        val map = WorldMementoMap()
        val key = key(overworld(), 2, 0)

        map.markScanned(
            key = key,
            scanTick = 12L,
            provenance = ChunkScanProvenance.FILE_PRIMARY,
            unresolvedReason = null,
        )
        map.upsertSignals(
            key = key,
            signals = ChunkSignals(
                inhabitedTimeTicks = 7L,
                lastUpdateTicks = 90L,
                surfaceY = 64,
                biomeId = "minecraft:forest",
                isSpawnChunk = false,
            ),
        )

        val before = map.snapshot()
        val inserted = map.ensureExistsAndReportInserted(key)
        val after = map.snapshot()

        assertFalse(inserted)
        assertEquals(before, after)
        assertTrue(map.hasSignals(key))
        assertFalse(map.isMissing(key))
    }

    private fun overworld(): RegistryKey<World> =
        RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft:overworld"))

    private fun key(world: RegistryKey<World>, chunkX: Int, chunkZ: Int): ChunkKey =
        ChunkKey(
            world = world,
            regionX = Math.floorDiv(chunkX, 32),
            regionZ = Math.floorDiv(chunkZ, 32),
            chunkX = chunkX,
            chunkZ = chunkZ,
        )
}
