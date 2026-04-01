package ch.oliverlanz.memento.domain.worldmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World

class WorldMapServiceStaleMarkTest {

    @Test
    fun force_stale_observation_tick_preserves_signals_and_metadata() {
        val service = WorldMapService().also { it.attachForTesting() }
        val key = key(overworld(), 2, 3)

        service.applyFactOnTickThread(
            ChunkMetadataFact(
                key = key,
                source = ChunkScanProvenance.ENGINE_FALLBACK,
                unresolvedReason = ChunkScanUnresolvedReason.FILE_IO_ERROR,
                signals = ChunkSignals(
                    inhabitedTimeTicks = 123L,
                    lastUpdateTicks = 456L,
                    surfaceY = 77,
                    biomeId = "minecraft:plains",
                    isSpawnChunk = false,
                ),
                scanTick = 900L,
            ),
        )

        val applied = service.forceStaleObservationTickOnTickThread(key = key, staleScanTick = -99L)
        assertTrue(applied)

        val entry = assertNotNull(service.substrate().scannedEntry(key))
        val s = assertNotNull(entry.signals)
        assertEquals(-99L, entry.scanTick)
        assertEquals(123L, s.inhabitedTimeTicks)
        assertEquals(456L, s.lastUpdateTicks)
        assertEquals(77, s.surfaceY)
        assertEquals("minecraft:plains", s.biomeId)
        assertEquals(ChunkScanProvenance.ENGINE_FALLBACK, entry.provenance)
        assertEquals(ChunkScanUnresolvedReason.FILE_IO_ERROR, entry.unresolvedReason)
    }

    @Test
    fun force_stale_observation_tick_returns_false_when_entry_missing() {
        val service = WorldMapService().also { it.attachForTesting() }
        val key = key(overworld(), 30, 31)

        val applied = service.forceStaleObservationTickOnTickThread(key = key, staleScanTick = -10L)
        assertTrue(!applied)
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
