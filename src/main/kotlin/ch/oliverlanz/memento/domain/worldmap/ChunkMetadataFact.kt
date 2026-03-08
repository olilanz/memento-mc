package ch.oliverlanz.memento.domain.worldmap

/**
 * Boundary-safe chunk metadata fact emitted by infrastructure publishers.
 *
 * Contract:
 * - Contains semantic metadata only (no runtime chunk objects).
 * - Can be produced off-thread.
 * - Is applied to [WorldMementoMap] only through [WorldMapService] on tick thread.
 */
data class ChunkMetadataFact(
    val key: ChunkKey,
    val source: ChunkScanProvenance,
    val unresolvedReason: ChunkScanUnresolvedReason? = null,
    val signals: ChunkSignals? = null,
    /** Optional topology signal update authored by stone authority. */
    val dominantStone: DominantStoneSignal? = null,
    /** Optional effect signal update authored by stone authority. */
    val dominantStoneEffect: DominantStoneEffectSignal? = null,
    val scanTick: Long,
)

fun interface ChunkMetadataIngestionPort {
    fun ingest(fact: ChunkMetadataFact)
}
