package ch.oliverlanz.memento.infrastructure.worldscan

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.renewal.election.RenewalElection
import ch.oliverlanz.memento.domain.renewal.election.RenewalRankedElectionEntry
import ch.oliverlanz.memento.domain.renewal.projection.RegionKey
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCandidateAction
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCommittedSnapshot
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance
import ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import net.minecraft.server.MinecraftServer
import net.minecraft.util.WorldSavePath

/**
 * Operator CSV observability writer.
 *
 * Contract:
 * - no previously emitted world-fact field is removed,
 * - projection/election columns are additive only,
 * - election ordering is read-only and deterministic from committed projection head.
 */
object MementoCsvWriter {

    fun writeOperatorWorldviewSnapshot(
        server: MinecraftServer,
        worldSnapshot: List<ChunkScanSnapshotEntry>,
        projectionSnapshot: RenewalCommittedSnapshot,
    ): Path {
        val path = server.getSavePath(WorldSavePath.ROOT)
            .resolve(MementoConstants.MEMENTO_SCAN_CSV_FILE)

        Files.createDirectories(path.parent)

        val election = RenewalElection.evaluate(
            input =
                ch.oliverlanz.memento.domain.renewal.projection.RenewalElectionInput(
                    generation = projectionSnapshot.generation,
                    regionForgettableByRegion = projectionSnapshot.regionForgettableByRegion,
                    chunkDerivationByChunk = projectionSnapshot.chunkDerivationByChunk,
                ),
            deterministicTransactionId = "csv-g${projectionSnapshot.generation}",
        )
        val projectedElection = RenewalElection.evaluate(
            input =
                ch.oliverlanz.memento.domain.renewal.projection.RenewalElectionInput(
                    generation = projectionSnapshot.generation,
                    regionForgettableByRegion = projectionSnapshot.regionForgettableByRegion,
                    chunkDerivationByChunk = projectionSnapshot.chunkDerivationByChunk,
                ),
            deterministicTransactionId = "csv-projected-g${projectionSnapshot.generation}",
            suppressAmbientChunkStrategy = false,
        )
        val rankedElection = RenewalElection.asContinuousRankedEntries(election)
        val projectedRankedElection = RenewalElection.asContinuousRankedEntries(projectedElection)

        val byRegion = rankedElection
            .filter { it.action == RenewalCandidateAction.REGION_PRUNE }
            .associateBy { keyOfRegion(it.worldKey, it.regionX, it.regionZ) }
        val byChunk = rankedElection
            .filter { it.action == RenewalCandidateAction.CHUNK_RENEW }
            .associateBy { keyOfChunk(it.worldKey, it.chunkX, it.chunkZ) }
        val projectedRankByRegion = projectedRankedElection
            .filter { it.action == RenewalCandidateAction.REGION_PRUNE }
            .associate { keyOfRegion(it.worldKey, it.regionX, it.regionZ) to it.rank }
        val projectedRankByChunk = projectedRankedElection
            .filter { it.action == RenewalCandidateAction.CHUNK_RENEW }
            .associate { keyOfChunk(it.worldKey, it.chunkX, it.chunkZ) to it.rank }

        val sb = StringBuilder()
        // Baseline world snapshot fields (unchanged, no removals) + additive projection/election fields.
        // Compatibility rule: new columns append-only.
        sb.append("dimension,chunkX,chunkZ,scanTick,inhabitedTicks,dominantStone,surfaceY,biome,isSpawn,source,status,regionForgettable,memorable,eligibleChunkRenewal,renewalAction,renewalRank,dominantStoneSignal,dominantStoneEffect,ambientStrategy\n")

        worldSnapshot
            .sortedWith(
                compareBy<ChunkScanSnapshotEntry> { it.key.world.value.toString() }
                    .thenBy { it.key.regionX }
                    .thenBy { it.key.regionZ }
                    .thenBy { it.key.chunkX }
                    .thenBy { it.key.chunkZ },
            )
            .forEach { entry ->
                val key = entry.key
                val signals = entry.signals
                val dim = key.world.value.toString()

                val dominant = entry.dominantStone.name
                val dominantStoneSignal = entry.dominantStone.name
                val dominantStoneEffect = entry.dominantStoneEffect.name

                val derivation = projectionSnapshot.chunkDerivationByChunk[key]
                val regionForgettable = projectionSnapshot.regionForgettableByRegion[
                    RegionKey(worldId = dim, regionX = key.regionX, regionZ = key.regionZ)
                ] == true

                val electedRegion = byRegion[keyOfRegion(dim, key.regionX, key.regionZ)]
                val electedChunk = byChunk[keyOfChunk(dim, key.chunkX, key.chunkZ)]
                val elected = electedRegion ?: electedChunk
                val action = elected?.action?.name ?: "NONE"
                val projectedRank =
                    projectedRankByRegion[keyOfRegion(dim, key.regionX, key.regionZ)]
                        ?: projectedRankByChunk[keyOfChunk(dim, key.chunkX, key.chunkZ)]
                val rank = projectedRank?.toString() ?: ""
                val ambientStrategy = derivation?.ambientStrategy?.name ?: "NONE"

                sb.append(dim)
                    .append(',').append(key.chunkX)
                    .append(',').append(key.chunkZ)
                    .append(',').append(entry.scanTick)
                    .append(',').append(signals?.inhabitedTimeTicks?.toString() ?: "")
                    .append(',').append(dominant)
                    .append(',').append(signals?.surfaceY?.toString() ?: "")
                    .append(',').append(signals?.biomeId ?: "")
                    .append(',').append(if (signals?.isSpawnChunk == true) 1 else 0)
                    .append(',').append(projectSource(entry.provenance))
                    .append(',').append(projectStatus(entry))
                    .append(',').append(if (regionForgettable) 1 else 0)
                    .append(',').append(if (derivation?.memorable == true) 1 else 0)
                    .append(',').append(if (derivation?.eligibleChunkRenewal == true) 1 else 0)
                    .append(',').append(action)
                    .append(',').append(rank)
                    .append(',').append(dominantStoneSignal)
                    .append(',').append(dominantStoneEffect)
                    .append(',').append(ambientStrategy)
                    .append('\n')
            }

        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8)
        MementoLog.info(
            MementoConcept.OPERATOR,
            "csv worldview written path={} rows={} projectionGeneration={} rankedElectionEntries={}",
            path,
            worldSnapshot.size,
            projectionSnapshot.generation,
            rankedElection.size,
        )
        return path
    }

    private fun projectSource(provenance: ChunkScanProvenance): String {
        return when (provenance) {
            ChunkScanProvenance.FILE_PRIMARY -> "NBT"
            ChunkScanProvenance.ENGINE_FALLBACK,
            ChunkScanProvenance.ENGINE_AMBIENT -> "ENGINE"
        }
    }

    private fun projectStatus(entry: ChunkScanSnapshotEntry): String {
        if (entry.signals != null) return "OK"
        return entry.unresolvedReason?.name ?: "UNKNOWN"
    }

    private fun keyOfRegion(worldKey: String, regionX: Int?, regionZ: Int?): String {
        return "$worldKey:${regionX ?: ""}:${regionZ ?: ""}"
    }

    private fun keyOfChunk(worldKey: String, chunkX: Int?, chunkZ: Int?): String {
        return "$worldKey:${chunkX ?: ""}:${chunkZ ?: ""}"
    }
}
