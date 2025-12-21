package ch.oliverlanz.memento

import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

import net.minecraft.util.math.ChunkPos

object MementoAnchors {

    enum class Kind { REMEMBER, FORGET }

    /**
     * Explicit lifecycle state for Witherstone (FORGET) anchors.
     *
     * This is persisted so the meaning of an anchor is not inferred purely from counters.
     *
     * - MATURING: time to maturity remains (> 0 days)
     * - MATURED: time to maturity elapsed (0 days); land may be forgotten once it becomes free
     * - CONSUMED: terminal; anchor is removed from persistence
     */
    enum class WitherstoneState { MATURING, MATURED, CONSUMED }

    data class Anchor(
        val name: String,
        val kind: Kind,
        val dimension: RegistryKey<World>,
        val pos: BlockPos,
        val radius: Int,
        val days: Int?,              // only for FORGET; null for REMEMBER
        val state: WitherstoneState? = null, // only for FORGET; null for REMEMBER
        val createdGameTime: Long
    )

    private val anchors = mutableMapOf<String, Anchor>()

    fun list(): Collection<Anchor> = anchors.values

    fun get(name: String): Anchor? = anchors[name]

    /**
     * Add or replace an anchor with the same name.
     * This matches the command semantics.
     */
    fun addOrReplace(anchor: Anchor) {
        anchors[anchor.name] = anchor
    }

    /**
     * Add only if the name does not exist.
     * (Kept for possible future use.)
     */
    fun add(anchor: Anchor): Boolean {
        if (anchors.containsKey(anchor.name)) return false
        anchors[anchor.name] = anchor
        return true
    }

    fun remove(name: String): Boolean =
        anchors.remove(name) != null

    fun clear() {
        anchors.clear()
    }

    fun putAll(newAnchors: Collection<Anchor>) {
        anchors.clear()
        for (a in newAnchors) {
            anchors[a.name] = a
        }
    }

    /**
     * Legacy helper used during early experiments.
     *
     * The current model uses anchor-based *chunk groups* (radius) and an
     * execution gate that waits until all chunks in a group are unloaded.
     * This function is kept for reference and for possible future test cases.
     */
    fun shouldForgetExactChunk(
        dimension: RegistryKey<World>,
        chunkPos: ChunkPos
    ): Boolean {
        return anchors.values.any { anchor ->
            anchor.kind == Kind.FORGET &&
                anchor.dimension == dimension &&
                (anchor.pos.x shr 4) == chunkPos.x &&
                (anchor.pos.z shr 4) == chunkPos.z
        }
    }

    /**
     * Compute all chunks within a circular radius around an anchor position.
     *
     * Radius semantics (locked for this slice):
     * - radius is given in *chunks* (1 == 16 blocks)
     * - a chunk is included if its *center* is within the radius from the anchor position
     *
     * We use this for anchor-based chunk groups.
     */
    fun computeChunksInRadius(anchorPos: BlockPos, radiusChunks: Int): Set<ChunkPos> {
        val radiusBlocks = radiusChunks * 16.0
        val radiusSq = radiusBlocks * radiusBlocks

        val anchorX = anchorPos.x.toDouble()
        val anchorZ = anchorPos.z.toDouble()

        val anchorChunkX = anchorPos.x shr 4
        val anchorChunkZ = anchorPos.z shr 4

        val result = LinkedHashSet<ChunkPos>()

        // Bounding square in chunk space, then filter by circle distance using chunk centers.
        for (cx in (anchorChunkX - radiusChunks)..(anchorChunkX + radiusChunks)) {
            for (cz in (anchorChunkZ - radiusChunks)..(anchorChunkZ + radiusChunks)) {
                val centerX = (cx * 16 + 8).toDouble()
                val centerZ = (cz * 16 + 8).toDouble()
                val dx = centerX - anchorX
                val dz = centerZ - anchorZ
                if ((dx * dx + dz * dz) <= radiusSq) {
                    result.add(ChunkPos(cx, cz))
                }
            }
        }
        return result
    }

    /**
     * Returns true if there exists a FORGET anchor whose radius
     * covers the given chunk in the given dimension.
     *
     * This is the authoritative forgettability check.
     */
    fun shouldForgetChunk(
        dimension: RegistryKey<World>,
        chunkPos: ChunkPos
    ): Boolean {
        return anchors.values.any { anchor ->
            anchor.kind == Kind.FORGET &&
                    anchor.dimension == dimension &&
                    isChunkWithinAnchorRadius(anchor, chunkPos)
        }
    }

    private fun isChunkWithinAnchorRadius(
        anchor: Anchor,
        chunkPos: ChunkPos
    ): Boolean {
        // Reuse the same semantics as the group builder.
        // This method is not currently used for regeneration, but it is an authoritative
        // "coverage" check for future slices.
        val radiusBlocks = anchor.radius * 16.0
        val radiusSq = radiusBlocks * radiusBlocks

        val centerX = (chunkPos.x * 16 + 8).toDouble()
        val centerZ = (chunkPos.z * 16 + 8).toDouble()
        val dx = centerX - anchor.pos.x.toDouble()
        val dz = centerZ - anchor.pos.z.toDouble()

        return (dx * dx + dz * dz) <= radiusSq
    }
}
