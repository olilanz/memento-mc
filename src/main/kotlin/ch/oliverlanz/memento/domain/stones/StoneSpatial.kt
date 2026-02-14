package ch.oliverlanz.memento.domain.stones

import net.minecraft.util.math.ChunkPos

/**
 * Spatial helpers for stone influence and overlaps.
 *
 * ### How stone "radius" works (important)
 *
 * Stones express their radius in **chunks**, but eligibility is evaluated in **world space**:
 *
 * - A chunk is eligible if its **chunk-center** lies within the stone's influence circle.
 * - Distances are measured from the **stone's block-center** to the **chunk-center**.
 *
 * This intentionally yields a circle-like footprint (approximated on the chunk grid),
 * rather than an axis-aligned square.
 *
 * #### Why the +12 block margin?
 *
 * If we only used `(radiusChunks * 16)` blocks, a stone placed near a chunk corner could
 * end up with *no* chunk center within the radius — not even its own chunk.
 *
 * The farthest possible distance from any block position inside a chunk to that chunk's
 * center is `sqrt(8^2 + 8^2) ≈ 11.31` blocks. We therefore add a fixed **12 block** margin
 * to guarantee that the stone's **own chunk is always included**, without special cases.
 *
 * Implementation note:
 * We compute squared distances in **half-block units** (integer math) to avoid floating
 * point edge cases while still measuring from block-centers.
 */
object StoneSpatial {

    /** Additional safety margin in blocks (see KDoc above). */
    private const val CENTER_MARGIN_BLOCKS: Int = 12

    /** Chunk that contains the stone (derived from its block position). */
    fun centerChunk(stone: Stone): ChunkPos =
        ChunkPos(stone.position.x shr 4, stone.position.z shr 4)

    /**
     * Influence radius in blocks with the same margin used by [containsChunk].
     *
     * This keeps block-space visualizations semantically aligned with chunk influence projection.
     */
    fun influenceRadiusBlocks(stone: StoneView): Int = stone.radius * 16 + CENTER_MARGIN_BLOCKS

    /**
     * True if [chunk] is inside [stone]'s influence footprint.
     *
     * The footprint is a circle in world space, evaluated against the chunk center.
     */
    fun containsChunk(stone: Stone, chunk: ChunkPos): Boolean {
        // Use half-block units (x2) so we can represent block-centers exactly as integers.
        val stoneCenterX2 = stone.position.x.toLong() * 2L + 1L
        val stoneCenterZ2 = stone.position.z.toLong() * 2L + 1L

        // Chunk center in block coordinates is (chunkX*16 + 8, chunkZ*16 + 8).
        val chunkCenterX2 = (chunk.x.toLong() * 16L + 8L) * 2L
        val chunkCenterZ2 = (chunk.z.toLong() * 16L + 8L) * 2L

        val dx = chunkCenterX2 - stoneCenterX2
        val dz = chunkCenterZ2 - stoneCenterZ2

        val dist2 = dx * dx + dz * dz

        val radiusBlocks = stone.radius * 16 + CENTER_MARGIN_BLOCKS
        val radiusX2 = radiusBlocks.toLong() * 2L
        val radius2 = radiusX2 * radiusX2

        return dist2 <= radius2
    }

    /**
     * True if the influence areas of [a] and [b] overlap.
     *
     * Dimension-aware: stones in different dimensions never overlap.
     *
     * This uses the same circle semantics as [containsChunk], but between stone centers:
     * if the distance between stone block-centers is within the sum of both radii (with
     * the same +12 margin applied), we treat them as overlapping.
     */
    fun overlaps(a: Stone, b: Stone): Boolean {
        if (a.dimension != b.dimension) return false

        val ax2 = a.position.x.toLong() * 2L + 1L
        val az2 = a.position.z.toLong() * 2L + 1L
        val bx2 = b.position.x.toLong() * 2L + 1L
        val bz2 = b.position.z.toLong() * 2L + 1L

        val dx = bx2 - ax2
        val dz = bz2 - az2

        val dist2 = dx * dx + dz * dz

        val raBlocks = a.radius * 16 + CENTER_MARGIN_BLOCKS
        val rbBlocks = b.radius * 16 + CENTER_MARGIN_BLOCKS
        val rSumX2 = (raBlocks + rbBlocks).toLong() * 2L
        val rSum2 = rSumX2 * rSumX2

        return dist2 <= rSum2
    }
}
