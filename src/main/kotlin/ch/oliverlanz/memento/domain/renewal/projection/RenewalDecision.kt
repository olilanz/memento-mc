package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.domain.worldmap.ChunkKey

/**
 * Region identifier used by region-grain renewal decisions.
 *
 * Note: [worldId] is a stable textual dimension key (`RegistryKey.value.toString()`),
 * which keeps this type independent from runtime registry handles.
 */
data class RegionKey(
    val worldId: String,
    val regionX: Int,
    val regionZ: Int,
)

/**
 * Materialized renewal target outcome produced after projection reaches STABLE.
 *
 * - [Region]: one eligible region selected by locked farness/tie rules.
 * - [ChunkBatch]: up to 64 eligible chunks selected by locked farness/tie rules.
 *
 * This aggregate is command-consumed state and must not be recomputed lazily in command handlers.
 */
sealed interface RenewalDecision {
    data class Region(val region: RegionKey) : RenewalDecision
    data class ChunkBatch(val chunks: List<ChunkKey>) : RenewalDecision
}
