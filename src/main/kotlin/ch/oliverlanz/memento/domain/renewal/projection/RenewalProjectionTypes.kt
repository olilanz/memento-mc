package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import net.minecraft.registry.RegistryKey
import net.minecraft.world.World

/**
 * Boolean projection output attached to a factual chunk key.
 */
/**
 * Diagnostic ambient execution preference derived from current projection facts.
 *
 * This is not an action and not a second eligibility authority.
 *
 * - [REGION]: ambient renewal would be handled by region pruning.
 * - [CHUNK]: ambient renewal would require chunk-level handling, but is currently unelected.
 * - [NONE]: no ambient renewal path is currently indicated.
 */
enum class AmbientRenewalStrategy {
    REGION,
    CHUNK,
    NONE,
}

data class RenewalChunkDerivation(
    val memorable: Boolean = false,
    val eligibleChunkRenewal: Boolean = false,
    val ambientStrategy: AmbientRenewalStrategy = AmbientRenewalStrategy.NONE,
    /**
     * Stone-intent marker derived from dominant WITHER_FORGET influence.
     *
     * This is used to keep stone-driven operator renewal actionable while
     * ambient chunk strategy is temporarily suppressed in election.
     */
    val explicitRenewalIntent: Boolean = false,
)

/** Read-only operational status exposed to observational command surfaces. */
data class RenewalProjectionStatusView(
    val pendingWorkSetSize: Int,
    val trackedChunks: Int,
    val trackedRegions: Int,
    val committedGeneration: Long,
    val blockedOnGate: Boolean = false,
    val runningDurationMs: Long? = null,
    val lastCompletedDurationMs: Long? = null,
    val lastCompletedAtMs: Long? = null,
    val lastCompletedReason: String? = null,
)

enum class RenewalCandidateAction {
    REGION_PRUNE,
    CHUNK_RENEW,
}

/**
 * Stable identity contract for preview binding and drift revalidation.
 *
 * worldKey must match canonical WorldMap/projection keying (`RegistryKey.value.toString()`).
 */
data class RenewalCandidateId(
    val action: RenewalCandidateAction,
    val worldKey: String,
    val regionX: Int? = null,
    val regionZ: Int? = null,
    val chunkX: Int? = null,
    val chunkZ: Int? = null,
)

data class RenewalRankedCandidate(
    val id: RenewalCandidateId,
    val rank: Int,
)

/**
 * Immutable world memory view at a specific projection generation.
 *
 * Contains:
 * - memorability and forgettability indices
 * - derived memory metrics
 * - ranked proposal output produced from this snapshot
 *
 * This snapshot is read-only and safe for cross-thread publication.
 */
data class RenewalPublishedView(
    val generation: Long,
    val chunkDerivationByChunk: Map<ChunkKey, RenewalChunkDerivation>,
    val regionForgettableByRegion: Map<RegionKey, Boolean>,
    val rankedCandidates: List<RenewalRankedCandidate>,
)

/**
 * Immutable evaluation input for election derivation.
 *
 * Projection materializes this input first, then election computes without side-effects.
 */
data class RenewalElectionInput(
    val generation: Long,
    val regionForgettableByRegion: Map<RegionKey, Boolean>,
    val chunkDerivationByChunk: Map<ChunkKey, RenewalChunkDerivation>,
)

typealias RenewalCommittedSnapshot = RenewalPublishedView

fun RenewalCandidateId.toChunkKeyOrNull(): ChunkKey? {
    if (action != RenewalCandidateAction.CHUNK_RENEW) return null
    val x = chunkX ?: return null
    val z = chunkZ ?: return null
    val world = runCatching {
        @Suppress("UNCHECKED_CAST")
        RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, net.minecraft.util.Identifier.of(worldKey)) as RegistryKey<World>
    }.getOrNull() ?: return null
    return ChunkKey(
        world = world,
        regionX = Math.floorDiv(x, 32),
        regionZ = Math.floorDiv(z, 32),
        chunkX = x,
        chunkZ = z,
    )
}
