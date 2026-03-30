package ch.oliverlanz.memento.infrastructure.worldscan

import ch.oliverlanz.memento.domain.renewal.projection.RegionKey
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCandidateAction
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCandidateId
import ch.oliverlanz.memento.domain.renewal.projection.RenewalChunkDerivation
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCommittedSnapshot
import ch.oliverlanz.memento.domain.renewal.projection.RenewalRankedCandidate
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance
import ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry
import ch.oliverlanz.memento.domain.worldmap.ChunkSignals
import ch.oliverlanz.memento.domain.worldmap.DominantStoneEffectSignal
import ch.oliverlanz.memento.domain.worldmap.DominantStoneSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World

class MementoCsvWriterRenderTest {

    @Test
    fun render_represents_unresolved_and_zero_inhabitance_without_inference_and_without_synthetic_non_existing_rows() {
        val world = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft:overworld"))

        val unresolved = chunkKey(world, 0, 0)
        val zero = chunkKey(world, 1, 0)

        val worldSnapshot = listOf(
            unresolvedEntry(unresolved, reason = ch.oliverlanz.memento.domain.worldmap.ChunkScanUnresolvedReason.FILE_IO_ERROR),
            snapshotEntry(zero, inhabited = 0L),
        )

        val committed = RenewalCommittedSnapshot(
            generation = 9L,
            chunkDerivationByChunk = mapOf(
                unresolved to RenewalChunkDerivation(memorabilityIndex = 0.0, memorable = false),
                zero to RenewalChunkDerivation(memorabilityIndex = 0.125, memorable = false),
            ),
            regionForgettableByRegion = emptyMap(),
            rankedCandidates = emptyList(),
        )

        val csv = MementoCsvWriter.renderOperatorWorldviewCsv(worldSnapshot, committed)
        val lines = csv.trim().split('\n')
        val header = lines.first()
        val rows = lines.drop(1).map { parseRow(header, it) }

        assertEquals(2, rows.size)

        val unresolvedRow = rows.first { it.getValue("chunkX") == "0" && it.getValue("chunkZ") == "0" }
        val zeroRow = rows.first { it.getValue("chunkX") == "1" && it.getValue("chunkZ") == "0" }

        // scanned + unresolved is represented explicitly, with no inhabited inference.
        assertEquals("FILE_IO_ERROR", unresolvedRow.getValue("status"))
        assertEquals("", unresolvedRow.getValue("inhabitedTicks"))
        assertEquals("0", unresolvedRow.getValue("chunkForgettable"))
        assertEquals("0.000000", unresolvedRow.getValue("memorabilityIndex"))

        // scanned + zero inhabitance is represented as explicit 0 and OK status.
        assertEquals("0", zeroRow.getValue("inhabitedTicks"))
        assertEquals("OK", zeroRow.getValue("status"))
        assertEquals("0", zeroRow.getValue("chunkForgettable"))
        assertEquals("0.125000", zeroRow.getValue("memorabilityIndex"))

        // csv keys are contained in scanned subset and therefore in discovered universe.
        val scannedKeys = worldSnapshot.map { it.key }.toSet()
        val exportedKeys = rows.map { row ->
            chunkKey(
                world = world,
                x = row.getValue("regionX").toInt() * 32 + row.getValue("chunkX").toInt(),
                z = row.getValue("regionZ").toInt() * 32 + row.getValue("chunkZ").toInt(),
            )
        }.toSet()
        assertTrue(exportedKeys.all { it in scannedKeys })

        // non-existing chunks must not be synthesized into csv output.
        assertFalse(rows.any { it.getValue("chunkX") == "2" && it.getValue("chunkZ") == "0" })
    }

    @Test
    fun render_preserves_locked_schema_and_row_universe_without_synthetic_rows() {
        val world = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft:overworld"))

        val c1 = chunkKey(world, 0, 0)
        val c2 = chunkKey(world, 1, 0)
        val c3 = chunkKey(world, 33, 0)

        val worldSnapshot = listOf(
            snapshotEntry(c1, inhabited = 10L),
            snapshotEntry(c2, inhabited = 0L),
            snapshotEntry(c3, inhabited = 0L),
        )

        val committed = RenewalCommittedSnapshot(
            generation = 7L,
            chunkDerivationByChunk = mapOf(
                c1 to RenewalChunkDerivation(memorabilityIndex = 0.900001, memorable = true),
                c2 to RenewalChunkDerivation(memorabilityIndex = 0.100000, memorable = false),
                c3 to RenewalChunkDerivation(memorabilityIndex = 0.200000, memorable = false),
            ),
            regionForgettableByRegion = mapOf(
                RegionKey(worldId = world.value.toString(), regionX = 0, regionZ = 0) to true,
            ),
            rankedCandidates = listOf(
                RenewalRankedCandidate(
                    id = RenewalCandidateId(
                        action = RenewalCandidateAction.REGION_PRUNE,
                        worldKey = world.value.toString(),
                        regionX = 0,
                        regionZ = 0,
                    ),
                    rank = 1,
                )
            ),
        )

        val csv = MementoCsvWriter.renderOperatorWorldviewCsv(worldSnapshot, committed)
        val lines = csv.trim().split('\n')

        val expectedHeader = "dimension,regionX,regionZ,chunkX,chunkZ,scanTick,inhabitedTicks,surfaceY,biome,isSpawn,dominantStone,dominantStoneEffect,memorabilityIndex,chunkMemorable,chunkForgettable,renewalAction,renewalRank,source,status"
        assertEquals(expectedHeader, lines.first())

        val rows = lines.drop(1).map { parseRow(expectedHeader, it) }
        assertEquals(3, rows.size)

        val exportedKeys = rows.map { row ->
            Triple(
                row.getValue("dimension"),
                row.getValue("regionX") + ":" + row.getValue("chunkX"),
                row.getValue("regionZ") + ":" + row.getValue("chunkZ"),
            )
        }.toSet()

        val discoveredKeys = worldSnapshot.map { entry ->
            Triple(
                entry.key.world.value.toString(),
                entry.key.regionX.toString() + ":" + Math.floorMod(entry.key.chunkX, 32).toString(),
                entry.key.regionZ.toString() + ":" + Math.floorMod(entry.key.chunkZ, 32).toString(),
            )
        }.toSet()

        assertEquals(discoveredKeys, exportedKeys)

        val rowC1 = rows.first { it.getValue("chunkX") == "0" && it.getValue("chunkZ") == "0" }
        val rowC2 = rows.first { it.getValue("chunkX") == "1" && it.getValue("chunkZ") == "0" }
        val rowC3 = rows.first { it.getValue("regionX") == "1" && it.getValue("chunkX") == "1" }

        assertEquals("REGION_PURGE", rowC1.getValue("renewalAction"))
        assertEquals("REGION_PURGE", rowC2.getValue("renewalAction"))
        assertEquals("NONE", rowC3.getValue("renewalAction"))

        assertEquals("0.900001", rowC1.getValue("memorabilityIndex"))
        assertEquals("0.100000", rowC2.getValue("memorabilityIndex"))
        assertEquals("0.200000", rowC3.getValue("memorabilityIndex"))

        // chunkForgettable reflects region-level forgettable authority on chunk rows.
        assertEquals("1", rowC1.getValue("chunkForgettable"))
        assertEquals("1", rowC2.getValue("chunkForgettable"))
        assertEquals("0", rowC3.getValue("chunkForgettable"))

        assertEquals("1", rowC1.getValue("renewalRank"))
        assertEquals("1", rowC2.getValue("renewalRank"))
        assertEquals("", rowC3.getValue("renewalRank"))

        assertFalse(rows.any { it.getValue("regionX") == "2" })
    }

    @Test
    fun render_exports_algorithmic_index_while_lore_override_keeps_chunk_memorable() {
        val world = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft:overworld"))
        val loreChunk = chunkKey(world, 0, 0)

        val worldSnapshot = listOf(snapshotEntry(loreChunk, inhabited = 0L))

        val committed = RenewalCommittedSnapshot(
            generation = 11L,
            chunkDerivationByChunk = mapOf(
                loreChunk to RenewalChunkDerivation(memorabilityIndex = 0.000000, memorable = true),
            ),
            regionForgettableByRegion = emptyMap(),
            rankedCandidates = emptyList(),
        )

        val csv = MementoCsvWriter.renderOperatorWorldviewCsv(worldSnapshot, committed)
        val lines = csv.trim().split('\n')
        val header = lines.first()
        val row = parseRow(header, lines[1])

        assertEquals("0.000000", row.getValue("memorabilityIndex"))
        assertEquals("1", row.getValue("chunkMemorable"))
    }

    private fun parseRow(header: String, line: String): Map<String, String> {
        val keys = header.split(',')
        val values = line.split(',')
        return keys.zip(values).toMap()
    }

    private fun chunkKey(world: RegistryKey<World>, x: Int, z: Int): ChunkKey {
        return ChunkKey(
            world = world,
            regionX = Math.floorDiv(x, 32),
            regionZ = Math.floorDiv(z, 32),
            chunkX = x,
            chunkZ = z,
        )
    }

    private fun snapshotEntry(key: ChunkKey, inhabited: Long): ChunkScanSnapshotEntry {
        return ChunkScanSnapshotEntry(
            key = key,
            signals = ChunkSignals(
                inhabitedTimeTicks = inhabited,
                lastUpdateTicks = null,
                surfaceY = 64,
                biomeId = "minecraft:plains",
                isSpawnChunk = false,
            ),
            dominantStone = DominantStoneSignal.NONE,
            dominantStoneEffect = DominantStoneEffectSignal.NONE,
            scanTick = 1L,
            provenance = ChunkScanProvenance.FILE_PRIMARY,
            unresolvedReason = null,
        )
    }

    private fun unresolvedEntry(
        key: ChunkKey,
        reason: ch.oliverlanz.memento.domain.worldmap.ChunkScanUnresolvedReason,
    ): ChunkScanSnapshotEntry {
        return ChunkScanSnapshotEntry(
            key = key,
            signals = null,
            dominantStone = DominantStoneSignal.NONE,
            dominantStoneEffect = DominantStoneEffectSignal.NONE,
            scanTick = 1L,
            provenance = ChunkScanProvenance.FILE_PRIMARY,
            unresolvedReason = reason,
        )
    }
}
