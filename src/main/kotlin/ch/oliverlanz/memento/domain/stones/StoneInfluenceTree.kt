package ch.oliverlanz.memento.domain.stones

import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

/**
 * Immutable influence snapshot owned by [StoneAuthority].
 *
 * This is a derived, deterministic view rebuilt whenever the stone set changes.
 *
 * ### Intent
 *
 * The topology is the sole authority for stone influence semantics, but multiple consumers
 * (renewal, visualization, pipelines) need *efficient* read access. This tree provides that
 * without duplicating logic outside [StoneAuthority].
 *
 * ### What is stored
 *
 * - [byStone]: for each stone name in the dimension, the set of chunks within its spatial radius.
 * - [dominantByChunk]: for each chunk, the dominant stone *kind* (type) affecting it.
 *
 * We intentionally store only the dominant kind (e.g., Lorestone vs Witherstone) and not an
 * identity reference to the dominating stone.
 */
data class StoneInfluenceTree(
    val dimensions: Map<RegistryKey<World>, DimensionInfluence>
) {
    companion object {
        val EMPTY: StoneInfluenceTree = StoneInfluenceTree(emptyMap())
    }
}

/** Influence data for a single dimension. */
data class DimensionInfluence(
    /** Stone name -> influenced chunk set (dimension is implied by the parent map key). */
    val byStone: Map<String, Set<ChunkPos>>,

    /** Chunk -> dominant stone kind (stored as the Kotlin class of the stone). */
    val dominantByChunk: Map<ChunkPos, kotlin.reflect.KClass<out Stone>>,
)
