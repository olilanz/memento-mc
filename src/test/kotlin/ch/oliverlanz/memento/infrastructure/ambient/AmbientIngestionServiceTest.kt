package ch.oliverlanz.memento.infrastructure.ambient

import ch.oliverlanz.memento.domain.worldmap.WorldMapService
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World

class AmbientIngestionServiceTest {

    @Test
    fun very_low_pulse_metadata_miss_drops_tracker_entry_without_retry_loop() {
        val service = AmbientIngestionService(WorldMapService().also { it.attachForTesting() }, ChunkLoadDriver())
        val tracker = trackerOf(service)

        tracker.onLoaded(LoadedChunkKey(overworld(), 10, 20))
        assertEquals(1, tracker.size())

        // Driver has no attached server; getMetadataIfLoaded(...) returns null.
        // Contract lock: null-miss is expected and tracker entry is dropped.
        service.onVeryLowPulse()
        assertEquals(0, tracker.size())

        // Subsequent pulse should not re-process dropped entry.
        service.onVeryLowPulse()
        assertEquals(0, tracker.size())
    }

    private fun trackerOf(service: AmbientIngestionService): LoadedChunkTracker {
        val field = AmbientIngestionService::class.java.getDeclaredField("tracker")
        field.isAccessible = true
        return field.get(service) as LoadedChunkTracker
    }

    private fun overworld(): RegistryKey<World> =
        RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft:overworld"))
}

