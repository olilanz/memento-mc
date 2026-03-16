package ch.oliverlanz.memento.domain.harness

import ch.oliverlanz.memento.domain.renewal.projection.RenewalCandidateAction
import ch.oliverlanz.memento.domain.renewal.projection.RenewalCommittedSnapshot
import ch.oliverlanz.memento.domain.renewal.projection.toChunkKeyOrNull
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World

fun assertWorldviewConsistency(
    discoveredChunkKeys: Set<ChunkKey>,
    scannedChunkKeys: Set<ChunkKey> = discoveredChunkKeys,
    projectionSnapshot: RenewalCommittedSnapshot,
    csv: String,
) {
    val rows = csvRowsByColumn(csv)

    val exportedChunkKeys = rows
        .map { row ->
            ChunkKey(
                world = worldKey(row.getValue("dimension")),
                regionX = row.getValue("regionX").toInt(),
                regionZ = row.getValue("regionZ").toInt(),
                chunkX = row.getValue("regionX").toInt() * 32 + row.getValue("chunkX").toInt(),
                chunkZ = row.getValue("regionZ").toInt() * 32 + row.getValue("chunkZ").toInt(),
            )
        }
        .toSet()

    assertTrue(
        scannedChunkKeys.all { discoveredChunkKeys.contains(it) },
        "metadata/scanned subset must stay within discovered universe",
    )
    assertTrue(
        exportedChunkKeys.all { discoveredChunkKeys.contains(it) },
        "csv rows must stay within discovered universe",
    )
    assertTrue(
        exportedChunkKeys.all { scannedChunkKeys.contains(it) },
        "csv rows must stay within scanned subset",
    )

    val memorableChunkKeys = projectionSnapshot.chunkDerivationByChunk
        .filterValues { it.memorable }
        .keys
    assertTrue(memorableChunkKeys.all { discoveredChunkKeys.contains(it) }, "memorable chunks must be discovered")

    val forgettableChunkKeys = projectionSnapshot.chunkDerivationByChunk
        .filterValues { !it.memorable }
        .keys
    assertTrue(forgettableChunkKeys.all { discoveredChunkKeys.contains(it) }, "forgettable chunks must be discovered")

    val rankedChunkKeys = projectionSnapshot.rankedCandidates
        .filter { it.id.action == RenewalCandidateAction.CHUNK_RENEW }
        .mapNotNull { it.id.toChunkKeyOrNull() }
        .toSet()
    assertTrue(rankedChunkKeys.all { discoveredChunkKeys.contains(it) }, "ranked chunk candidates must be discovered")

    rows.forEach { row ->
        val memorable = row.getValue("chunkMemorable") == "1"
        val forgettable = row.getValue("chunkForgettable") == "1"
        assertFalse(memorable && forgettable, "chunkMemorable and chunkForgettable must not both be 1")

        val rank = row.getValue("renewalRank")
        val action = row.getValue("renewalAction")
        if (rank.isNotEmpty()) {
            assertTrue(action == "REGION_PURGE" || action == "CHUNK_RENEW", "ranked rows must carry ranked action")
        }
    }

    val regionRanks = rows
        .filter { it.getValue("renewalAction") == "REGION_PURGE" }
        .groupBy { Triple(it.getValue("dimension"), it.getValue("regionX"), it.getValue("regionZ")) }
        .mapValues { (_, grouped) -> grouped.map { it.getValue("renewalRank") }.toSet() }

    regionRanks.forEach { (region, ranks) ->
        assertEquals(1, ranks.size, "all rows in ranked region must share rank for region=$region")
    }
}

private fun csvRowsByColumn(csv: String): List<Map<String, String>> {
    val lines = csv.trim().split('\n')
    val header = lines.first().split(',')
    return lines.drop(1).map { line ->
        val values = line.split(',')
        header.zip(values).toMap()
    }
}

private fun worldKey(worldId: String): RegistryKey<World> {
    @Suppress("UNCHECKED_CAST")
    return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(worldId)) as RegistryKey<World>
}
