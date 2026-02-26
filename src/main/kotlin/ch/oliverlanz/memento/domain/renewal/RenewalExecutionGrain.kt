package ch.oliverlanz.memento.domain.renewal

import ch.oliverlanz.memento.domain.renewal.projection.RegionKey
import ch.oliverlanz.memento.domain.worldmap.ChunkKey

/**
 * Defines the execution granularity selected by election.
 *
 * Interpretation and world mutation are owned by the execution layer.
 * This type carries no execution behavior.
 */
sealed interface RenewalExecutionGrain {
    data class Region(val region: RegionKey) : RenewalExecutionGrain
    data class ChunkBatch(val chunks: List<ChunkKey>) : RenewalExecutionGrain
}
