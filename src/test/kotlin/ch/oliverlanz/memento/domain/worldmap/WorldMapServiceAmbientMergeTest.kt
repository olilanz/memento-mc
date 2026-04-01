package ch.oliverlanz.memento.domain.worldmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World

class WorldMapServiceAmbientMergeTest {

    @Test
    fun newer_ambient_observation_does_not_erase_richer_existing_fields() {
        val service = WorldMapService().also { it.attachForTesting() }
        val key = key(overworld(), 4, 5)

        service.applyFactOnTickThread(
            ChunkMetadataFact(
                key = key,
                source = ChunkScanProvenance.FILE_PRIMARY,
                unresolvedReason = null,
                signals = ChunkSignals(
                    inhabitedTimeTicks = 12L,
                    lastUpdateTicks = 999L,
                    surfaceY = 70,
                    biomeId = "minecraft:plains",
                    isSpawnChunk = true,
                ),
                scanTick = 200L,
            ),
        )

        val applied = service.applyAmbientFactOnTickThread(
            ChunkMetadataFact(
                key = key,
                source = ChunkScanProvenance.ENGINE_AMBIENT,
                unresolvedReason = null,
                signals = ChunkSignals(
                    inhabitedTimeTicks = 33L,
                    lastUpdateTicks = null,
                    surfaceY = null,
                    biomeId = null,
                    isSpawnChunk = false,
                ),
                scanTick = 300L,
            ),
        )

        assertTrue(applied)
        val entry = assertNotNull(service.substrate().scannedEntry(key))
        val s = assertNotNull(entry.signals)
        assertEquals(33L, s.inhabitedTimeTicks)
        assertEquals(999L, s.lastUpdateTicks)
        assertEquals(70, s.surfaceY)
        assertEquals("minecraft:plains", s.biomeId)
        assertTrue(s.isSpawnChunk)
        assertEquals(300L, entry.scanTick)
    }

    @Test
    fun older_ambient_completeness_update_keeps_newer_observation_tick() {
        val service = WorldMapService().also { it.attachForTesting() }
        val key = key(overworld(), 6, 7)

        service.applyFactOnTickThread(
            ChunkMetadataFact(
                key = key,
                source = ChunkScanProvenance.FILE_PRIMARY,
                unresolvedReason = null,
                signals = ChunkSignals(
                    inhabitedTimeTicks = null,
                    lastUpdateTicks = 321L,
                    surfaceY = 80,
                    biomeId = "minecraft:forest",
                    isSpawnChunk = false,
                ),
                scanTick = 500L,
            ),
        )

        val applied = service.applyAmbientFactOnTickThread(
            ChunkMetadataFact(
                key = key,
                source = ChunkScanProvenance.ENGINE_AMBIENT,
                unresolvedReason = null,
                signals = ChunkSignals(
                    inhabitedTimeTicks = 77L,
                    lastUpdateTicks = null,
                    surfaceY = null,
                    biomeId = null,
                    isSpawnChunk = false,
                ),
                scanTick = 120L,
            ),
        )

        assertTrue(applied)
        val entry = assertNotNull(service.substrate().scannedEntry(key))
        val s = assertNotNull(entry.signals)
        assertEquals(77L, s.inhabitedTimeTicks)
        assertEquals(321L, s.lastUpdateTicks)
        assertEquals(80, s.surfaceY)
        assertEquals("minecraft:forest", s.biomeId)
        assertEquals(500L, entry.scanTick)
    }

    @Test
    fun older_ambient_completeness_update_preserves_existing_provenance_and_unresolved_reason() {
        val service = WorldMapService().also { it.attachForTesting() }
        val key = key(overworld(), 9, 10)

        service.applyFactOnTickThread(
            ChunkMetadataFact(
                key = key,
                source = ChunkScanProvenance.ENGINE_FALLBACK,
                unresolvedReason = ChunkScanUnresolvedReason.FILE_IO_ERROR,
                signals = ChunkSignals(
                    inhabitedTimeTicks = null,
                    lastUpdateTicks = 111L,
                    surfaceY = 65,
                    biomeId = "minecraft:savanna",
                    isSpawnChunk = false,
                ),
                scanTick = 500L,
            ),
        )

        val applied = service.applyAmbientFactOnTickThread(
            ChunkMetadataFact(
                key = key,
                source = ChunkScanProvenance.ENGINE_AMBIENT,
                unresolvedReason = ChunkScanUnresolvedReason.FILE_MISSING,
                signals = ChunkSignals(
                    inhabitedTimeTicks = 42L,
                    lastUpdateTicks = null,
                    surfaceY = null,
                    biomeId = null,
                    isSpawnChunk = false,
                ),
                scanTick = 120L,
            ),
        )

        assertTrue(applied)
        val entry = assertNotNull(service.substrate().scannedEntry(key))
        val s = assertNotNull(entry.signals)
        assertEquals(42L, s.inhabitedTimeTicks)
        assertEquals(111L, s.lastUpdateTicks)
        assertEquals(65, s.surfaceY)
        assertEquals("minecraft:savanna", s.biomeId)
        assertEquals(500L, entry.scanTick)
        assertEquals(ChunkScanProvenance.ENGINE_FALLBACK, entry.provenance)
        assertEquals(ChunkScanUnresolvedReason.FILE_IO_ERROR, entry.unresolvedReason)
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
