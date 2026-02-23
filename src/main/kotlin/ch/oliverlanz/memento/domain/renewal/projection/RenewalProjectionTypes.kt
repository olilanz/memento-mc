package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry
import net.minecraft.registry.RegistryKey
import net.minecraft.world.World

/**
 * Derived renewal metrics attached to a factual chunk key in the projection layer.
 *
 * Indices are modeled as Double for algorithm replaceability; current slice uses binary values.
 */
data class RenewalChunkMetrics(
    val forgettabilityIndex: Double = 0.0,
    val livelinessIndex: Double = 0.0,
)

/** Read-only operational status exposed to observational command surfaces. */
data class RenewalProjectionStatusView(
    val pendingWorkSetSize: Int,
    val trackedChunks: Int,
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
    val byRegionPrune: Boolean,
)

/**
 * Commit snapshot payload.
 */
data class RenewalCommittedSnapshot(
    val generation: Long,
    val snapshotEntries: List<ChunkScanSnapshotEntry>,
    val metricsByChunk: Map<ChunkKey, RenewalChunkMetrics>,
    val rankedCandidates: List<RenewalRankedCandidate>,
)

typealias RenewalStableSnapshot = RenewalCommittedSnapshot

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
