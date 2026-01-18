package ch.oliverlanz.memento.domain.memento

/** Influence flags projected onto a chunk. */
data class StoneInfluenceFlags(
    val hasWitherstoneInfluence: Boolean,
    val hasLorestoneInfluence: Boolean,
)

data class ChunkMementoView(
    val key: ChunkKey,
    val signals: ChunkSignals,
    val influence: StoneInfluenceFlags,
)

/** Domain-owned, shaped surface produced by superimposing influence onto the substrate. */
class WorldMementoTopology(
    val entries: List<ChunkMementoView>,
)
