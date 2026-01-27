
package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.infrastructure.chunk.ChunkRef

class WorldScanPlan {
    private val pending = LinkedHashSet<ChunkRef>()
    private val completed = HashSet<ChunkRef>()

    fun desiredChunks(): Set<ChunkRef> = pending - completed

    fun add(ref: ChunkRef) {
        pending.add(ref)
    }

    fun markCompleted(ref: ChunkRef) {
        completed.add(ref)
    }

    fun isComplete(): Boolean =
        pending.isNotEmpty() && completed.containsAll(pending)
}
