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
        RenewalTracker.observeChunkLoaded(world, pos)
    }

    fun onChunkUnloaded(world: RegistryKey<World>, pos: ChunkPos) {
        RenewalTracker.observeChunkUnloaded(world, pos)
    }
}
