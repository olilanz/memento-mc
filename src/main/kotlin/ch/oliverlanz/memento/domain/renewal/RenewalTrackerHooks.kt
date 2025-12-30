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
object RenewalTrackerHooks {

    private var tracker: RenewalTracker? = null

    fun attach(tracker: RenewalTracker) {
        this.tracker = tracker
    }

    fun detach() {
        this.tracker = null
    }

    fun onChunkLoaded(world: RegistryKey<World>, pos: ChunkPos) {
        tracker?.onChunkLoaded(world, pos)
    }

    fun onChunkUnloaded(world: RegistryKey<World>, pos: ChunkPos) {
        tracker?.onChunkUnloaded(world, pos)
    }
}
