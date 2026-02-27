package ch.oliverlanz.memento.domain.renewal.election

import ch.oliverlanz.memento.domain.renewal.RenewalExecutionGrain
import ch.oliverlanz.memento.domain.renewal.projection.RegionKey
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCandidateAction
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCandidateId
import ch.oliverlanz.memento.domain.renewal.projection.RenewalElectionInput
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog

data class ElectionResult(
    val projectionGeneration: Long,
    val transactionId: String,
    val electedRegions: List<RegionKey>,
    val electedChunks: List<ChunkKey>,
    val selectedExecutionGrain: RenewalExecutionGrain?,
)

/**
 * Deterministic projection election over boolean projection outputs.
 *
 * Election policy:
 * - region-prune candidates first in deterministic order
 * - chunk-renew candidates only from non-forgettable regions
 * - no numeric scoring surfaces
 */
object RenewalElection {

    private val evaluationCounter = java.util.concurrent.atomic.AtomicLong(0L)

    fun evaluate(input: RenewalElectionInput): ElectionResult {
        // Domain-separation contract:
        // - Region prune and chunk renew are distinct eligibility domains.
        // - Region-prune candidates are evaluated first.
        // - Chunk-renew candidates are evaluated only from non-forgettable regions.
        // - The region filter inside chunk assembly is a defensive consistency guard,
        //   not a semantic derivation source for chunk eligibility.
        val transactionId = "elect-${input.generation}-${evaluationCounter.incrementAndGet()}"

        val sortedRegions = input.regionForgettableByRegion
            .asSequence()
            .filter { (_, forgettable) -> forgettable }
            .map { (region, _) -> region }
            .sortedWith(
                compareBy<RegionKey> { it.worldId }
                    .thenBy { it.regionX }
                    .thenBy { it.regionZ }
            )
            .toList()

        val chunkCandidates = input.chunkDerivationByChunk
            .asSequence()
            .filter { (_, derivation) -> derivation.eligibleChunkRenewal }
            .map { (key, _) -> key }
            .filter { key ->
                input.regionForgettableByRegion[RegionKey(
                    worldId = key.world.value.toString(),
                    regionX = key.regionX,
                    regionZ = key.regionZ,
                )] != true
            }
            .sortedWith(
                compareBy<ChunkKey> { it.world.value.toString() }
                    .thenBy { it.regionX }
                    .thenBy { it.regionZ }
                    .thenBy { it.chunkX }
                    .thenBy { it.chunkZ }
            )
            .toList()

        val selected = when {
            sortedRegions.isNotEmpty() -> RenewalExecutionGrain.Region(sortedRegions.first())
            chunkCandidates.isNotEmpty() -> RenewalExecutionGrain.ChunkBatch(chunkCandidates)
            else -> null
        }

        MementoLog.info(
            MementoConcept.RENEWAL,
            "election deterministic generation={} transaction={} regionCandidates={} chunkCandidates={} selected={}",
            input.generation,
            transactionId,
            sortedRegions.size,
            chunkCandidates.size,
            selected?.javaClass?.simpleName ?: "NONE",
        )

        return ElectionResult(
            projectionGeneration = input.generation,
            transactionId = transactionId,
            electedRegions = sortedRegions,
            electedChunks = chunkCandidates,
            selectedExecutionGrain = selected,
        )
    }

    fun asRankedCandidates(result: ElectionResult): List<ch.oliverlanz.memento.domain.renewal.projection.RenewalRankedCandidate> {
        val out = mutableListOf<ch.oliverlanz.memento.domain.renewal.projection.RenewalRankedCandidate>()
        var rank = 1

        result.electedRegions.forEach { region ->
            out += ch.oliverlanz.memento.domain.renewal.projection.RenewalRankedCandidate(
                id = RenewalCandidateId(
                    action = RenewalCandidateAction.REGION_PRUNE,
                    worldKey = region.worldId,
                    regionX = region.regionX,
                    regionZ = region.regionZ,
                ),
                rank = rank,
                byRegionPrune = true,
            )
            rank++
        }

        if (result.electedRegions.isEmpty()) {
            result.electedChunks.forEach { chunk ->
                out += ch.oliverlanz.memento.domain.renewal.projection.RenewalRankedCandidate(
                    id = RenewalCandidateId(
                        action = RenewalCandidateAction.CHUNK_RENEW,
                        worldKey = chunk.world.value.toString(),
                        chunkX = chunk.chunkX,
                        chunkZ = chunk.chunkZ,
                    ),
                    rank = rank,
                    byRegionPrune = false,
                )
                rank++
            }
        }

        return out
    }
}
