package ch.oliverlanz.memento.infrastructure.worldscan

import ch.oliverlanz.memento.domain.renewal.RenewalExecutionGrain
import ch.oliverlanz.memento.domain.renewal.election.ElectionResult
import ch.oliverlanz.memento.domain.renewal.projection.RenewalChunkMetrics
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCandidateAction
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCommittedSnapshot
import ch.oliverlanz.memento.domain.worldmap.WorldMementoTopology
import ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.stones.StoneMapService
import ch.oliverlanz.memento.MementoConstants
import net.minecraft.server.MinecraftServer
import net.minecraft.util.WorldSavePath
import net.minecraft.util.math.ChunkPos
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Application-level projection of topology and renewal candidate/election views into CSV files.
 *
 * Local policy ownership:
 * - election CSV export is bound to the same projection-derived command transaction id,
 * - exported candidate ordering reflects the exact ordered candidate set consumed by command handling,
 * - schema-level fields such as `renewal_rank` and `renewal_by_region_prune` are owned here.
 */
object MementoCsvWriter {


    fun writeSnapshot(server: MinecraftServer, snapshot: List<ChunkScanSnapshotEntry>) {
        val path = server.getSavePath(WorldSavePath.ROOT)
            .resolve(MementoConstants.MEMENTO_SCAN_CSV_FILE)

        Files.createDirectories(path.parent)

        val sb = StringBuilder()
        sb.append("dimension,chunk_x,chunk_z,scan_tick,inhabited_ticks,dominant_stone,surface_y,biome_id,is_spawn_chunk,source,status\n")

        // Dominant influence map per world (already lore-resolved by StoneAuthority)
        val dominantByWorld = linkedMapOf<net.minecraft.registry.RegistryKey<net.minecraft.world.World>, Map<ChunkPos, kotlin.reflect.KClass<out ch.oliverlanz.memento.domain.stones.Stone>>>()

        snapshot.forEach { entry ->
            val key = entry.key
            val signals = entry.signals

            val dominantByChunk = dominantByWorld.getOrPut(key.world) {
                StoneMapService.dominantByChunk(key.world)
            }
            val dominant = dominantByChunk[ChunkPos(key.chunkX, key.chunkZ)]?.simpleName ?: ""

            val dim = key.world.value.toString()
            val inhabited = signals?.inhabitedTimeTicks?.toString() ?: ""
            val surfaceY = signals?.surfaceY?.toString() ?: ""
            val biome = signals?.biomeId ?: ""
            val isSpawn = signals?.isSpawnChunk?.toString() ?: ""
            val source = projectSource(entry)
            val status = projectStatus(entry)

            sb.append(dim)
                .append(',').append(key.chunkX)
                .append(',').append(key.chunkZ)
                .append(',').append(entry.scanTick)
                .append(',').append(inhabited)
                .append(',').append(dominant)
                .append(',').append(surfaceY)
                .append(',').append(biome)
                .append(',').append(isSpawn)
                .append(',').append(source)
                .append(',').append(status)
                .append('\n')
        }

        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8)
        MementoLog.info(MementoConcept.SCANNER, "Wrote scan CSV: {}", path)
    }

    fun writeElectionSnapshot(
        server: MinecraftServer,
        snapshot: RenewalCommittedSnapshot,
    ) {
        val path = server.getSavePath(WorldSavePath.ROOT)
            .resolve(MementoConstants.MEMENTO_SCAN_CSV_FILE)

        Files.createDirectories(path.parent)

        val regionCandidates = snapshot.electionCandidates
            .filter { it.id.action == RenewalCandidateAction.REGION_PRUNE }
            .associateBy { "${it.id.worldKey}:${it.id.regionX}:${it.id.regionZ}" }

        val chunkCandidates = snapshot.electionCandidates
            .filter { it.id.action == RenewalCandidateAction.CHUNK_RENEW }
            .associateBy { "${it.id.worldKey}:${it.id.chunkX}:${it.id.chunkZ}" }

        val sb = StringBuilder()
        sb.append("projection_generation,dimension,chunk_x,chunk_z,scan_tick,inhabited_ticks,dominant_stone,surface_y,biome_id,is_spawn_chunk,source,status,forgettability_index,memorability_index,renewal_rank,renewal_by_region_prune\n")

        val dominantByWorld = linkedMapOf<net.minecraft.registry.RegistryKey<net.minecraft.world.World>, Map<ChunkPos, kotlin.reflect.KClass<out ch.oliverlanz.memento.domain.stones.Stone>>>()

        snapshot.snapshotEntries.forEach { entry ->
            val key = entry.key
            val signals = entry.signals
            val metrics = snapshot.metricsByChunk[key] ?: RenewalChunkMetrics()

            val dominantByChunk = dominantByWorld.getOrPut(key.world) {
                StoneMapService.dominantByChunk(key.world)
            }
            val dominant = dominantByChunk[ChunkPos(key.chunkX, key.chunkZ)]?.simpleName ?: ""

            val dim = key.world.value.toString()
            val inhabited = signals?.inhabitedTimeTicks?.toString() ?: ""
            val surfaceY = signals?.surfaceY?.toString() ?: ""
            val biome = signals?.biomeId ?: ""
            val isSpawn = signals?.isSpawnChunk?.toString() ?: ""
            val source = projectSource(entry)
            val status = projectStatus(entry)

            val regionKey = "$dim:${key.regionX}:${key.regionZ}"
            val chunkKey = "$dim:${key.chunkX}:${key.chunkZ}"
            val regionCandidate = regionCandidates[regionKey]
            val chunkCandidate = chunkCandidates[chunkKey]
            val selected = regionCandidate ?: chunkCandidate
            val renewalRank = selected?.rank?.toString() ?: ""
            val renewalByRegionPrune = if (regionCandidate != null) "true" else "false"

            sb.append(snapshot.generation)
                .append(',').append(dim)
                .append(',').append(key.chunkX)
                .append(',').append(key.chunkZ)
                .append(',').append(entry.scanTick)
                .append(',').append(inhabited)
                .append(',').append(dominant)
                .append(',').append(surfaceY)
                .append(',').append(biome)
                .append(',').append(isSpawn)
                .append(',').append(source)
                .append(',').append(status)
                .append(',').append(metrics.forgettabilityIndex)
                .append(',').append(metrics.memorabilityIndex)
                .append(',').append(renewalRank)
                .append(',').append(renewalByRegionPrune)
                .append('\n')
        }

        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8)
        MementoLog.info(
            MementoConcept.RENEWAL,
            "Wrote election CSV: {} generation={} candidates={}",
            path,
            snapshot.generation,
            snapshot.electionCandidates.size,
        )
    }

    fun writeElectionSnapshot(
        server: MinecraftServer,
        snapshot: List<ChunkScanSnapshotEntry>,
        metricsByChunk: Map<ChunkKey, RenewalChunkMetrics>,
        result: ElectionResult,
    ) {
        val path = server.getSavePath(WorldSavePath.ROOT)
            .resolve(MementoConstants.MEMENTO_SCAN_CSV_FILE)

        Files.createDirectories(path.parent)

        val sb = StringBuilder()
        sb.append("projection_generation,election_transaction_id,dimension,chunk_x,chunk_z,scan_tick,inhabited_ticks,dominant_stone,surface_y,biome_id,is_spawn_chunk,source,status,forgettability_index,memorability_index,elected_as_region,elected_as_chunk\n")

        val electedRegionSet = result.electedRegions.toSet()
        val selectedChunkSet = when (val selected = result.selectedExecutionGrain) {
            is RenewalExecutionGrain.ChunkBatch -> selected.chunks.toSet()
            else -> emptySet()
        }

        val dominantByWorld = linkedMapOf<net.minecraft.registry.RegistryKey<net.minecraft.world.World>, Map<ChunkPos, kotlin.reflect.KClass<out ch.oliverlanz.memento.domain.stones.Stone>>>()

        snapshot.forEach { entry ->
            val key = entry.key
            val signals = entry.signals
            val metrics = metricsByChunk[key] ?: RenewalChunkMetrics()

            val dominantByChunk = dominantByWorld.getOrPut(key.world) {
                StoneMapService.dominantByChunk(key.world)
            }
            val dominant = dominantByChunk[ChunkPos(key.chunkX, key.chunkZ)]?.simpleName ?: ""

            val dim = key.world.value.toString()
            val inhabited = signals?.inhabitedTimeTicks?.toString() ?: ""
            val surfaceY = signals?.surfaceY?.toString() ?: ""
            val biome = signals?.biomeId ?: ""
            val isSpawn = signals?.isSpawnChunk?.toString() ?: ""
            val source = projectSource(entry)
            val status = projectStatus(entry)
            val electedAsRegion = electedRegionSet.any {
                it.worldId == key.world.value.toString() && it.regionX == key.regionX && it.regionZ == key.regionZ
            }
            val electedAsChunk = key in selectedChunkSet

            sb.append(result.projectionGeneration)
                .append(',').append(result.transactionId)
                .append(',').append(dim)
                .append(',').append(key.chunkX)
                .append(',').append(key.chunkZ)
                .append(',').append(entry.scanTick)
                .append(',').append(inhabited)
                .append(',').append(dominant)
                .append(',').append(surfaceY)
                .append(',').append(biome)
                .append(',').append(isSpawn)
                .append(',').append(source)
                .append(',').append(status)
                .append(',').append(metrics.forgettabilityIndex)
                .append(',').append(metrics.memorabilityIndex)
                .append(',').append(if (electedAsRegion) 1 else 0)
                .append(',').append(if (electedAsChunk) 1 else 0)
                .append('\n')
        }

        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8)
        MementoLog.info(
            MementoConcept.RENEWAL,
            "Wrote election CSV: {} generation={} transaction={} electedRegions={} electedChunks={} selectedGrain={}",
            path,
            result.projectionGeneration,
            result.transactionId,
            result.electedRegions.size,
            result.electedChunks.size,
            result.selectedExecutionGrain?.javaClass?.simpleName ?: "NONE",
        )
    }

    private fun projectSource(entry: ChunkScanSnapshotEntry): String {
        return when (entry.provenance) {
            ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance.FILE_PRIMARY -> "NBT"
            ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance.ENGINE_FALLBACK,
            ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance.ENGINE_AMBIENT -> "ENGINE"
        }
    }

    private fun projectStatus(entry: ChunkScanSnapshotEntry): String {
        if (entry.signals != null) return "OK"
        return entry.unresolvedReason?.name ?: "UNKNOWN"
    }

    fun write(server: MinecraftServer, topology: WorldMementoTopology): Path {
        val root = server.getSavePath(WorldSavePath.ROOT)
        val path = root.resolve(MementoConstants.MEMENTO_SCAN_CSV_FILE)

        // LOCKED SCHEMA (scanner responsibility only; no derived fields, no optional fields):
        // dimension,chunk_x,chunk_z,inhabited_ticks,dominant_stone,surface_y,biome_id,is_spawn_chunk
        val sb = StringBuilder()
        sb.append("dimension,chunk_x,chunk_z,inhabited_ticks,dominant_stone,surface_y,biome_id,is_spawn_chunk\n")

        topology.entries.forEach { entry ->
            val dim = entry.key.world.value.toString()
            val signals = entry.signals
            val biome = signals?.biomeId ?: ""
            val surfaceY = signals?.surfaceY?.toString() ?: ""
            val inhabited = signals?.inhabitedTimeTicks?.toString() ?: ""
            val dominant = entry.dominantStoneKind?.simpleName ?: ""

            sb.append(dim)
                .append(',').append(entry.key.chunkX)
                .append(',').append(entry.key.chunkZ)
                .append(',').append(inhabited)
                .append(',').append(dominant)
                .append(',').append(surfaceY)
                .append(',').append(biome)
                .append(',').append(if (signals?.isSpawnChunk == true) 1 else 0)
                .append('\n')
        }

        Files.write(path, sb.toString().toByteArray(StandardCharsets.UTF_8))
        MementoLog.info(MementoConcept.SCANNER, "snapshot written csv={} rows={}", path, topology.entries.size)
        return path
    }
}
