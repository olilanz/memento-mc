
package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
import ch.oliverlanz.memento.infrastructure.chunk.ChunkRef
import net.minecraft.server.world.ServerWorld
import net.minecraft.world.chunk.WorldChunk

class ChunkMetadataConsumer(
    private val plan: WorldScanPlan
) : ChunkAvailabilityListener {

    override fun onChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        val ref = ChunkRef(world.registryKey, chunk.pos)
        plan.markCompleted(ref)
    }
}
