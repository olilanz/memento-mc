
package ch.oliverlanz.memento.domain.renewal

import net.minecraft.util.math.ChunkPos
import net.minecraft.registry.RegistryKey
import net.minecraft.world.World

/**
 * Adapter layer between Minecraft events and authoritative RenewalTracker.
 * Observability only – no lifecycle decisions here.
 */
object RenewalTrackerHooks {

    fun onChunkUnloaded(world: RegistryKey<World>, pos: ChunkPos) {
        val chunkKey = "${'$'}{pos.x},${'$'}{pos.z}"
        // batchName resolution is legacy/temporary; kept simple for now
        RenewalTracker.snapshot().forEach { batch ->
            RenewalTracker.onChunkUnloaded(batch.name, chunkKey)
        }
    }

    fun onChunkLoaded(world: RegistryKey<World>, pos: ChunkPos) {
        // Intentionally ignored for now.
        // Loading does not advance authoritative lifecycle at this stage.
    }
}
