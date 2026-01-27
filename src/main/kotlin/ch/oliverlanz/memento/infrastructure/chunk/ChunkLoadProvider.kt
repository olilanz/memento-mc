
package ch.oliverlanz.memento.infrastructure.chunk

interface ChunkLoadProvider {
    fun desiredChunks(): Sequence<ChunkRef>
}
