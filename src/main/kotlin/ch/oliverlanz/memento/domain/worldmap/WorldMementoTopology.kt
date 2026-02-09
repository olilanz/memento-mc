package ch.oliverlanz.memento.domain.worldmap

import ch.oliverlanz.memento.domain.stones.Stone
import kotlin.reflect.KClass

data class ChunkMementoView(
    val key: ChunkKey,
    val signals: ChunkSignals?,
    /** Dominant influence for this chunk, if any. Lore dominance is already resolved by StoneTopology. */
    val dominantStoneKind: KClass<out Stone>?,
    /** Convenience flag derived from [dominantStoneKind]. */
    val hasLorestoneInfluence: Boolean,
    /** Convenience flag derived from [dominantStoneKind]. */
    val hasWitherstoneInfluence: Boolean,
)

/** Domain-owned, shaped surface produced by superimposing influence onto the substrate. */
class WorldMementoTopology(
    val entries: List<ChunkMementoView>,
)
