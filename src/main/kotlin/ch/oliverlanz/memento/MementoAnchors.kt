package ch.oliverlanz.memento

import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

import net.minecraft.util.math.ChunkPos
import kotlin.math.abs

object MementoAnchors {

    const val DEFAULT_RADIUS: Int = 5
    const val DEFAULT_DAYS: Int = 5

    enum class Kind { REMEMBER, FORGET }

    data class Anchor(
        val name: String,
        val kind: Kind,
        val dimension: RegistryKey<World>,
        val pos: BlockPos,
        val radius: Int,
        val days: Int?,              // only for FORGET; null for REMEMBER
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
     * Current experiment slice:
     * Only the chunk that *contains* the FORGET anchor itself is forgettable.
     *
     * (Radius/days/heatmaps come later.)
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
        val anchorChunkX = anchor.pos.x shr 4
        val anchorChunkZ = anchor.pos.z shr 4

        val dx = abs(anchorChunkX - chunkPos.x)
        val dz = abs(anchorChunkZ - chunkPos.z)

        // radius is defined in chunks
        return dx <= anchor.radius && dz <= anchor.radius
    }
}
