package ch.oliverlanz.memento.infrastructure.ambient

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World

class LoadedChunkTrackerTest {

    @Test
    fun preserves_insertion_order_and_index_after_cursor() {
        val tracker = LoadedChunkTracker()
        val world = overworld()
        val a = LoadedChunkKey(world, 1, 1)
        val b = LoadedChunkKey(world, 2, 2)
        val c = LoadedChunkKey(world, 3, 3)

        tracker.onLoaded(a)
        tracker.onLoaded(b)
        tracker.onLoaded(c)

        val ordered = tracker.snapshotOrdered()
        assertEquals(listOf(a, b, c), ordered)
        assertEquals(0, tracker.indexAfter(null, ordered))
        assertEquals(1, tracker.indexAfter(a, ordered))
        assertEquals(0, tracker.indexAfter(LoadedChunkKey(world, 99, 99), ordered))
    }

    @Test
    fun clear_resets_loaded_state_for_lifecycle_reuse() {
        val tracker = LoadedChunkTracker()
        val world = overworld()
        tracker.onLoaded(LoadedChunkKey(world, 1, 1))
        tracker.onLoaded(LoadedChunkKey(world, 2, 2))

        tracker.clear()

        assertEquals(0, tracker.size())
        assertTrue(tracker.snapshotOrdered().isEmpty())
        assertEquals(0, tracker.indexAfter(null))
    }

    private fun overworld(): RegistryKey<World> =
        RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft:overworld"))
}
