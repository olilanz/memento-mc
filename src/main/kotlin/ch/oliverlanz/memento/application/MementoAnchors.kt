package ch.oliverlanz.memento.application

import ch.oliverlanz.memento.infrastructure.MementoDebug
import ch.oliverlanz.memento.infrastructure.MementoPersistence
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import kotlin.math.sqrt

object MementoAnchors {

    enum class Kind { REMEMBER, FORGET }

    /**
     * Explicit lifecycle state for Witherstone (FORGET) anchors.
     *
     * This is persisted so the meaning of an anchor is not inferred purely from counters.
     */
    enum class WitherstoneState {
        MATURING,
        MATURED,
        CONSUMED
    }

    data class Anchor(
        val name: String,
        val kind: Kind,
        val dimension: RegistryKey<World>,
        val pos: BlockPos,
        val radius: Int,
        val days: Int?,                    // only for FORGET; null for REMEMBER
        val state: WitherstoneState? = null, // only for FORGET; null for REMEMBER
        val createdGameTime: Long
    )

    private val anchors = mutableMapOf<String, Anchor>()

    fun list(): Collection<Anchor> = anchors.values

    fun get(name: String): Anchor? = anchors[name]

    fun clear() {
        anchors.clear()
    }

    fun putAll(loaded: Map<String, Anchor>) {
        anchors.putAll(loaded)
    }

    /**
     * Add or replace an anchor with the same name.
     */
    fun addOrReplace(anchor: Anchor) {
        anchors[anchor.name] = anchor
    }

    /**
     * Remove anchor by name.
     *
     * @return true if removed.
     */
    fun remove(name: String): Boolean =
        anchors.remove(name) != null

    fun isMaturedForgetAnchor(anchor: Anchor): Boolean =
        anchor.kind == Kind.FORGET && anchor.state == WitherstoneState.MATURED

    fun consume(name: String) {
        val a = anchors[name] ?: return
        if (a.kind != Kind.FORGET) return

        // Mark consumed (do not delete); stone removal is handled elsewhere.
        anchors[name] = a.copy(state = WitherstoneState.CONSUMED, days = 0)
    }

    /**
     * Ages witherstones by one day.
     *
     * This is the explicit "daily maturation" API.
     *
     * - Only affects FORGET anchors in MATURING state
     * - Decrements `days` until it reaches 0
     * - Transitions MATURING → MATURED when reaching 0
     * - Persists anchors if any change occurred
     *
     * @return number of witherstones that matured in this run
     */
    fun ageOnce(server: MinecraftServer): Int {
        var matured = 0
        var changed = false

        val updated = anchors.mapValues { (_, a) ->
            if (a.kind != Kind.FORGET) return@mapValues a
            if (a.state != WitherstoneState.MATURING) return@mapValues a

            val current = a.days ?: 0
            val next = current - 1

            if (next <= 0) {
                matured++
                changed = true
                MementoDebug.warn(
                    server,
                    "Witherstone '${a.name}' transitioned MATURING → MATURED"
                )
                a.copy(days = 0, state = WitherstoneState.MATURED)
            } else {
                changed = true
                a.copy(days = next)
            }
        }

        if (changed) {
            anchors.clear()
            anchors.putAll(updated)
            MementoPersistence.saveAnchors(server)
        }

        return matured
    }

    /**
     * Derive the set of chunks covered by an anchor radius.
     *
     * Semantics: circular coverage in chunk space, using chunk-center distance in block space.
     * This matches chunk-group derivation semantics.
     */
    fun computeChunksInRadius(anchorPos: BlockPos, radiusChunks: Int): Set<ChunkPos> {
        val anchorX = anchorPos.x.toDouble()
        val anchorZ = anchorPos.z.toDouble()

        val radiusBlocks = radiusChunks * 16.0
        val radiusSq = radiusBlocks * radiusBlocks

        val anchorChunkX = anchorPos.x shr 4
        val anchorChunkZ = anchorPos.z shr 4

        val result = LinkedHashSet<ChunkPos>()

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
     * Coverage check for a single chunk.
     *
     * This is authoritative "is this chunk within the anchor radius" logic,
     * aligned with computeChunksInRadius semantics.
     */
    fun coversChunk(anchor: Anchor, chunkPos: ChunkPos): Boolean {
        val radiusBlocks = anchor.radius * 16.0
        val radiusSq = radiusBlocks * radiusBlocks

        val centerX = (chunkPos.x * 16 + 8).toDouble()
        val centerZ = (chunkPos.z * 16 + 8).toDouble()
        val dx = centerX - anchor.pos.x.toDouble()
        val dz = centerZ - anchor.pos.z.toDouble()

        return (dx * dx + dz * dz) <= radiusSq
    }
}
