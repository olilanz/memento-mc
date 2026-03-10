package ch.oliverlanz.memento.domain.renewal.election

import ch.oliverlanz.memento.domain.renewal.projection.RegionKey
import ch.oliverlanz.memento.domain.renewal.projection.AmbientRenewalStrategy
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
)

data class RenewalRankedElectionEntry(
    val action: RenewalCandidateAction,
    val rank: Int,
    val worldKey: String,
    val regionX: Int? = null,
    val regionZ: Int? = null,
    val chunkX: Int? = null,
    val chunkZ: Int? = null,
)

/**
 * Deterministic election authority over boolean projection outputs.
 *
 * Election policy:
 * - region-prune candidates first in deterministic order
 * - chunk-renew candidates are derived independently from chunk-local eligibility
 * - region-first ordering is an execution policy, not a derivation dependency
 * - no numeric scoring surfaces
 */
object RenewalElection {

    private val evaluationCounter = java.util.concurrent.atomic.AtomicLong(0L)

    fun evaluate(
        input: RenewalElectionInput,
        deterministicTransactionId: String? = null,
        suppressAmbientChunkStrategy: Boolean = true,
    ): ElectionResult {
        // Domain-separation contract:
        // - Region prune and chunk renew are distinct eligibility domains.
        // - Region-prune candidates are evaluated first.
        // - Chunk-renew candidates are derived from chunk-local eligibility only.
        // - Region forgettability must not be used as a fallback/override gate for chunk renewal.
        val transactionId = deterministicTransactionId
            ?: "elect-${input.generation}-${evaluationCounter.incrementAndGet()}"

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
            .filter { (_, derivation) ->
                if (!derivation.eligibleChunkRenewal) return@filter false

                // Explicit stone intent remains actionable.
                if (derivation.explicitRenewalIntent) return@filter true

                if (!suppressAmbientChunkStrategy) {
                    // Diagnostic/projection view: include ambient chunk strategy.
                    return@filter true
                }

                // Release cut gate (explicit and local):
                // ambient chunk strategy remains projected/observable,
                // but is intentionally unelected for execution.
                if (derivation.ambientStrategy == AmbientRenewalStrategy.CHUNK) return@filter false

                // No stone intent and ambient-chunk path suppressed: not actionable in election.
                false
            }
            .map { (key, _) -> key }
            .sortedWith(
                compareBy<ChunkKey> { it.world.value.toString() }
                    .thenBy { it.regionX }
                    .thenBy { it.regionZ }
                    .thenBy { it.chunkX }
                    .thenBy { it.chunkZ }
            )
            .toList()

        MementoLog.debug(
            MementoConcept.RENEWAL,
            "election deterministic generation={} transaction={} regionCandidates={} chunkCandidates={} regionFirstPolicy=true",
            input.generation,
            transactionId,
            sortedRegions.size,
            chunkCandidates.size,
        )

        return ElectionResult(
            projectionGeneration = input.generation,
            transactionId = transactionId,
            electedRegions = sortedRegions,
            electedChunks = chunkCandidates,
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
            )
            rank++
        }

        result.electedChunks.forEach { chunk ->
            out += ch.oliverlanz.memento.domain.renewal.projection.RenewalRankedCandidate(
                id = RenewalCandidateId(
                    action = RenewalCandidateAction.CHUNK_RENEW,
                    worldKey = chunk.world.value.toString(),
                    chunkX = chunk.chunkX,
                    chunkZ = chunk.chunkZ,
                ),
                rank = rank,
            )
            rank++
        }

        return out
    }

    fun asContinuousRankedEntries(result: ElectionResult): List<RenewalRankedElectionEntry> {
        val out = mutableListOf<RenewalRankedElectionEntry>()
        var rank = 1

        result.electedRegions.forEach { region ->
            out += RenewalRankedElectionEntry(
                action = RenewalCandidateAction.REGION_PRUNE,
                rank = rank,
                worldKey = region.worldId,
                regionX = region.regionX,
                regionZ = region.regionZ,
            )
            rank++
        }

        result.electedChunks.forEach { chunk ->
            out += RenewalRankedElectionEntry(
                action = RenewalCandidateAction.CHUNK_RENEW,
                rank = rank,
                worldKey = chunk.world.value.toString(),
                chunkX = chunk.chunkX,
                chunkZ = chunk.chunkZ,
            )
            rank++
        }

        return out
    }
}
