package ch.oliverlanz.memento.domain.renewal.projection

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
 * Region identifier shared by eligibility/command-level selection results.
 */
