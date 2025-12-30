
package ch.oliverlanz.memento.domain.renewal

import net.minecraft.util.math.ChunkPos
import net.minecraft.registry.RegistryKey
import net.minecraft.world.World

object RenewalTracker {

    private val batches = mutableMapOf<String, RenewalBatch>()
    private val listeners = mutableListOf<(RenewalEvent) -> Unit>()

    fun register(batch: RenewalBatch) {
        batches[batch.name] = batch
        emit(RenewalEvent.BatchRegistered(batch.name))
    }

    fun onChunkUnloaded(world: RegistryKey<World>, pos: ChunkPos) {
        batches.values.forEach { batch ->
            batch.onChunkUnloaded(world, pos)?.let { emit(it) }
        }
    }

    fun onChunkLoaded(world: RegistryKey<World>, pos: ChunkPos) {
        batches.values.forEach { batch ->
            batch.onChunkLoaded(world, pos)?.let { emit(it) }
        }
    }

    fun tick(gameTime: Long) {
        batches.values.forEach { batch ->
            batch.tick(gameTime)?.let { emit(it) }
        }
    }

    fun subscribe(listener: (RenewalEvent) -> Unit) {
        listeners += listener
    }

    private fun emit(event: RenewalEvent) {
        listeners.forEach { it(event) }
    }
}
