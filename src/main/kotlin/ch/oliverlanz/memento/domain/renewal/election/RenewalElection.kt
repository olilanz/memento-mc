package ch.oliverlanz.memento.domain.renewal.election

import ch.oliverlanz.memento.domain.renewal.projection.RegionKey
import ch.oliverlanz.memento.domain.renewal.projection.AmbientRenewalStrategy
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCandidateAction
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCandidateId
import ch.oliverlanz.memento.domain.renewal.projection.RenewalElectionInput
import ch.oliverlanz.memento.domain.renewal.projection.RenewalRankedCandidate
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.util.Comparator

data class ElectionResult(
    val projectionGeneration: Long,
    val transactionId: String,
    val electedRegions: List<RegionKey>,
    val electedChunks: List<ChunkKey>,
)

/**
 * Deterministic election authority over boolean projection outputs.
 *
 * Ownership boundary:
 * - Consumes committed projection input only.
 * - Produces deterministic elected region/chunk sets and ranked candidates.
 * - Does not mutate WorldMap or projection caches.
 *
 * Election policy:
 * - region-prune candidates first in deterministic order
 * - chunk-renew candidates are derived independently from chunk-local eligibility
 * - region-first ordering is an execution policy, not a derivation dependency
 * - no numeric scoring surfaces
 *
 * Non-goals:
 * - command execution orchestration,
 * - runtime chunk lifecycle integration,
 * - deriving projection-world facts.
 */
object RenewalElection {

    private val evaluationCounter = java.util.concurrent.atomic.AtomicLong(0L)

    fun evaluate(
        input: RenewalElectionInput,
        deterministicTransactionId: String? = null,
        suppressAmbientChunkStrategy: Boolean = true,
        includeExplicitStoneIntent: Boolean = false,
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
                if (derivation.explicitRenewalIntent) return@filter includeExplicitStoneIntent

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

    fun asRankedCandidates(result: ElectionResult): List<RenewalRankedCandidate> {
        val out = mutableListOf<RenewalRankedCandidate>()

        val regionsByWorld = result.electedRegions.groupBy { it.worldId }
        val chunksByWorld = result.electedChunks.groupBy { it.world.value.toString() }

        val orderedWorlds = (regionsByWorld.keys + chunksByWorld.keys)
            .toSortedSet(worldOrder())

        orderedWorlds.forEach { worldId ->
            var rank = 1

            regionsByWorld[worldId].orEmpty().forEach { region ->
                out += RenewalRankedCandidate(
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

            chunksByWorld[worldId].orEmpty().forEach { chunk ->
                out += RenewalRankedCandidate(
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
        }

        return out
    }

    private fun worldOrder(): Comparator<String> {
        return compareBy<String> { worldPriority(it) }
            .thenBy { it }
    }

    private fun worldPriority(worldKey: String): Int {
        return when (worldKey) {
            "minecraft:overworld" -> 0
            "minecraft:the_nether" -> 1
            "minecraft:the_end" -> 2
            else -> {
                MementoLog.error(
                    MementoConcept.RENEWAL,
                    "unknown dimension encountered in worldPriority(): '{}'. dimension ordering assumptions may be violated; expected [minecraft:overworld, minecraft:the_nether, minecraft:the_end]",
                    worldKey,
                )
                Int.MAX_VALUE
            }
        }
    }
}
