package ch.oliverlanz.memento.domain.renewal

import net.minecraft.registry.RegistryKey
import net.minecraft.world.World
import net.minecraft.util.math.ChunkPos

/**
 * Hooks that bridge infrastructure-level events (chunk load/unload)
 * into the RenewalTracker domain model.
 *
 * This component is purely observational.
 * It does NOT tick, schedule, or perform renewal.
 */
object RenewalChunkObservationBridge {

    fun onChunkLoaded(world: RegistryKey<World>, pos: ChunkPos) {
        // dimension intentionally ignored for now (single-world assumption)
        RenewalTracker.observeChunkLoaded(pos)
    }

    fun onChunkUnloaded(world: RegistryKey<World>, pos: ChunkPos) {
        RenewalTracker.observeChunkUnloaded(pos)
    }
}
