package ch.oliverlanz.memento.infrastructure.worldscan

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.renewal.projection.RegionKey
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCandidateAction
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCommittedSnapshot
import ch.oliverlanz.memento.domain.worldmap.DominantStoneEffectSignal
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
 * Operator worldview CSV writer.
 *
 * Authority boundary:
 * - Produces an observational export artifact; it is not a domain-state authority.
 * - Consumes provided world/projection read surfaces and must not infer missing world facts.
 *
 * Locked contract (source of truth is local to this class):
 * - header order and names are fixed by [LOCKED_SCHEMA],
 * - writer must copy election ranks from committed snapshot and never recompute ranks,
 * - writer emits chunk-granular rows; region purge rank is replicated across all chunks in that region,
 * - rank/action and enum domains are fail-fast validated before file write.
 */
object MementoCsvWriter {

    private val LOCKED_SCHEMA = listOf(
        "dimension",
        "regionX",
        "regionZ",
        "chunkX",
        "chunkZ",
        "scanTick",
        "inhabitedTicks",
        "surfaceY",
        "biome",
        "isSpawn",
        "dominantStone",
        "dominantStoneEffect",
        "chunkMemorable",
        "chunkForgettable",
        "renewalAction",
        "renewalRank",
        "source",
        "status",
    )
    private const val REGION_WIDTH = 32

    fun writeOperatorWorldviewSnapshot(
        server: MinecraftServer,
        worldSnapshot: List<ChunkScanSnapshotEntry>,
        projectionSnapshot: RenewalCommittedSnapshot,
    ): Path {
        val path = server.getSavePath(WorldSavePath.ROOT)
            .resolve(MementoConstants.MEMENTO_SCAN_CSV_FILE)

        Files.createDirectories(path.parent)

        val csv = renderOperatorWorldviewCsv(worldSnapshot, projectionSnapshot)
        Files.writeString(path, csv, StandardCharsets.UTF_8)
        MementoLog.info(
            MementoConcept.OPERATOR,
            "csv worldview written path={} rows={} projectionGeneration={} rankedElectionEntries={}",
            path,
            worldSnapshot.size,
            projectionSnapshot.generation,
            projectionSnapshot.rankedCandidates.size,
        )
        return path
    }

    fun renderOperatorWorldviewCsv(
        worldSnapshot: List<ChunkScanSnapshotEntry>,
        projectionSnapshot: RenewalCommittedSnapshot,
    ): String {
        val rankedElection = projectionSnapshot.rankedCandidates

        val rankByRegion = rankedElection
            .filter { it.id.action == RenewalCandidateAction.REGION_PRUNE }
            .associate { keyOfRegion(it.id.worldKey, it.id.regionX, it.id.regionZ) to it.rank }
        val rankByChunk = rankedElection
            .filter { it.id.action == RenewalCandidateAction.CHUNK_RENEW }
            .associate { keyOfChunk(it.id.worldKey, it.id.chunkX, it.id.chunkZ) to it.rank }

        val header = LOCKED_SCHEMA.joinToString(",")
        require(
            header == "dimension,regionX,regionZ,chunkX,chunkZ,scanTick,inhabitedTicks,surfaceY,biome,isSpawn,dominantStone,dominantStoneEffect,chunkMemorable,chunkForgettable,renewalAction,renewalRank,source,status"
        ) {
            "csv schema drift detected"
        }

        val sb = StringBuilder()
        sb.append(header).append('\n')

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
                val regionKey = keyOfRegion(dim, key.regionX, key.regionZ)
                val chunkKey = keyOfChunk(dim, key.chunkX, key.chunkZ)

                val dominant = entry.dominantStone.name
                val dominantStoneEffect = mapDominantStoneEffect(entry.dominantStoneEffect)
                validateDominantStoneEffect(dominantStoneEffect)

                val derivation = projectionSnapshot.chunkDerivationByChunk[key]
                val regionRank = rankByRegion[regionKey]
                val chunkRank = rankByChunk[chunkKey]
                val action = when {
                    regionRank != null -> "REGION_PURGE"
                    chunkRank != null -> "CHUNK_RENEW"
                    else -> "NONE"
                }
                validateRenewalAction(action)

                val rank = (regionRank ?: chunkRank)?.toString() ?: ""
                require((action == "NONE") == rank.isEmpty()) {
                    "rank/action invariant violated action=$action rank='$rank' key=$chunkKey"
                }

                val chunkMemorable = derivation?.memorable == true
                val chunkForgettable = projectionSnapshot.regionForgettableByRegion[
                    RegionKey(worldId = dim, regionX = key.regionX, regionZ = key.regionZ)
                ] == true

                val row = listOf(
                    dim,
                    key.regionX.toString(),
                    key.regionZ.toString(),
                    floorModChunk(key.chunkX).toString(),
                    floorModChunk(key.chunkZ).toString(),
                    entry.scanTick.toString(),
                    signals?.inhabitedTimeTicks?.toString() ?: "",
                    signals?.surfaceY?.toString() ?: "",
                    signals?.biomeId ?: "",
                    if (signals?.isSpawnChunk == true) "1" else "0",
                    dominant,
                    dominantStoneEffect,
                    if (chunkMemorable) "1" else "0",
                    if (chunkForgettable) "1" else "0",
                    action,
                    rank,
                    projectSource(entry.provenance),
                    projectStatus(entry),
                )

                require(row.size == LOCKED_SCHEMA.size) {
                    "csv row width mismatch expected=${LOCKED_SCHEMA.size} actual=${row.size}"
                }
                sb.append(row.joinToString(",")).append('\n')
            }

        return sb.toString()
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

    private fun floorModChunk(globalChunk: Int): Int = Math.floorMod(globalChunk, REGION_WIDTH)

    private fun mapDominantStoneEffect(effect: DominantStoneEffectSignal): String {
        return when (effect) {
            DominantStoneEffectSignal.NONE -> "NONE"
            DominantStoneEffectSignal.LORE_PROTECT -> "PROTECT"
            DominantStoneEffectSignal.WITHER_FORGET -> "RENEW"
        }
    }

    private fun validateDominantStoneEffect(effect: String) {
        require(effect == "NONE" || effect == "PROTECT" || effect == "RENEW") {
            "unknown dominantStoneEffect='$effect'"
        }
    }

    private fun validateRenewalAction(action: String) {
        require(action == "NONE" || action == "CHUNK_RENEW" || action == "REGION_PURGE") {
            "unknown renewalAction='$action'"
        }
    }
}
