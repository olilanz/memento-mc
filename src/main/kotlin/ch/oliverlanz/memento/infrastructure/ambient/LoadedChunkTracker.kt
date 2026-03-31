package ch.oliverlanz.memento.infrastructure.ambient

import net.minecraft.registry.RegistryKey
import net.minecraft.world.World
import java.util.ArrayList
import java.util.LinkedHashSet

data class LoadedChunkKey(
    val dimension: RegistryKey<World>,
    val x: Int,
    val z: Int,
)

/**
 * Tracks currently loaded chunks for ambient freshness traversal.
 *
 * Scope is strictly bounded by currently loaded chunks. No additional retention cache is kept.
 */
class LoadedChunkTracker {
    private val loaded = LinkedHashSet<LoadedChunkKey>()

    fun onLoaded(ref: LoadedChunkKey) {
        loaded.add(ref)
    }

    fun onUnloaded(ref: LoadedChunkKey) {
        loaded.remove(ref)
    }

    fun clear() {
        loaded.clear()
    }

    fun snapshotOrdered(): List<LoadedChunkKey> = ArrayList(loaded)

    fun indexAfter(cursor: LoadedChunkKey?, ordered: List<LoadedChunkKey> = snapshotOrdered()): Int {
        if (ordered.isEmpty()) return 0
        val c = cursor ?: return 0
        val idx = ordered.indexOf(c)
        return if (idx < 0) 0 else (idx + 1) % ordered.size
    }

    fun size(): Int = loaded.size
}
