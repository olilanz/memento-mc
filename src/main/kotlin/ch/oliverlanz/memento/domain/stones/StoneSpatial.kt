package ch.oliverlanz.memento.domain.stones

import net.minecraft.util.math.ChunkPos

/**
 * Spatial helpers for stone influence and overlaps.
 *
 * Stones express their radius in **chunks** and use a square (Chebyshev) footprint:
 * - (dx, dz) are measured in chunk coordinates
 * - a chunk is inside the footprint iff abs(dx) <= radius AND abs(dz) <= radius
 *
 * This matches how Witherstone batches are derived (dx/dz ranges).
 */
object StoneSpatial {

    /** Chunk-space center of a stone (derived from its block position). */
    fun centerChunk(stone: Stone): ChunkPos =
        ChunkPos(stone.position.x shr 4, stone.position.z shr 4)

    /** True if [chunk] is inside [stone]'s influence footprint. */
    fun containsChunk(stone: Stone, chunk: ChunkPos): Boolean {
        val c = centerChunk(stone)
        val dx = kotlin.math.abs(chunk.x - c.x)
        val dz = kotlin.math.abs(chunk.z - c.z)
        return dx <= stone.radius && dz <= stone.radius
    }

    /**
     * True if the influence areas of [a] and [b] overlap.
     *
     * Dimension-aware: stones in different dimensions never overlap.
     */
    fun overlaps(a: Stone, b: Stone): Boolean {
        if (a.dimension != b.dimension) return false
        val ca = centerChunk(a)
        val cb = centerChunk(b)
        val dx = kotlin.math.abs(ca.x - cb.x)
        val dz = kotlin.math.abs(ca.z - cb.z)
        return dx <= (a.radius + b.radius) && dz <= (a.radius + b.radius)
    }
}
