
package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadProvider
import ch.oliverlanz.memento.infrastructure.chunk.ChunkRef
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import net.minecraft.server.world.ServerWorld
import net.minecraft.world.chunk.WorldChunk

class RenewalChunkLoadProvider : ChunkLoadProvider, ChunkAvailabilityListener {

    override fun desiredChunks(): Sequence<ChunkRef> = emptySequence()

    override fun onChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        // existing renewal logic remains
    }

    fun onRenewalEvent(event: RenewalEvent) {
        // delegate to existing renewal logic if needed
    }
}
