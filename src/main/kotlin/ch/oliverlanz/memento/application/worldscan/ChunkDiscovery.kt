package ch.oliverlanz.memento.application.worldscan

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Stage 3 of the /memento run pipeline: discover chunk work units inside each region.
 *
 * Slice (real chunk existence discovery):
 * - Reads the Anvil region header (first 4096 bytes = location table).
 * - Treats a chunk as existing if its sector offset is non-zero.
 * - Emits region-local chunk coordinates (0..31) in deterministic header order.
 *
 * Out of scope for this slice:
 * - Parsing chunk payloads (NBT)
 * - Using region timestamps
 * - Validating chunk offsets/sizes
 */
class ChunkDiscovery {

    private val log = LoggerFactory.getLogger("memento")

    fun discover(plan: WorldDiscoveryPlan): WorldDiscoveryPlan {
        val discovered = plan.worlds.map { world ->
            val regionsWithChunks = world.regions.map { region ->
                val chunks = discoverExistingChunks(region.file)
                region.copy(chunks = chunks)
            }
            world.copy(regions = regionsWithChunks)
        }
        return WorldDiscoveryPlan(worlds = discovered)
    }

    private fun discoverExistingChunks(regionFile: Path): List<ChunkRef> {
        val header = tryReadLocationTable(regionFile) ?: return emptyList()

        // 1024 entries * 4 bytes = 4096 bytes. Header entry index maps to (localX, localZ):
        // localX = i % 32, localZ = i / 32
        val out = ArrayList<ChunkRef>(128)
        for (i in 0 until LOCATION_TABLE_ENTRIES) {
            val base = i * LOCATION_ENTRY_BYTES
            val b0 = header[base].toInt() and 0xFF
            val b1 = header[base + 1].toInt() and 0xFF
            val b2 = header[base + 2].toInt() and 0xFF
            val sectorOffset = (b0 shl 16) or (b1 shl 8) or b2
            val sectorCount = header[base + 3].toInt() and 0xFF

            if (sectorOffset != 0) {
                val localX = i % REGION_WIDTH
                val localZ = i / REGION_WIDTH
                out.add(
                    ChunkRef(
                        localX = localX,
                        localZ = localZ,
                        sectorOffset = sectorOffset,
                        sectorCount = sectorCount,
                    ),
                )
            }
        }
        return out
    }

    /**
     * Reads the location table (first 4096 bytes) or returns null if the file can't be used.
     *
     * Error handling is intentionally "log + skip" to keep /memento run inspectable and robust.
     */
    private fun tryReadLocationTable(regionFile: Path): ByteArray? {
        val size = try {
            Files.size(regionFile)
        } catch (e: IOException) {
            log.info("[RUN] region file unreadable; skipping file={} error={}", regionFile, e.message)
            return null
        } catch (e: SecurityException) {
            log.info("[RUN] region file access denied; skipping file={} error={}", regionFile, e.message)
            return null
        }

        if (size < LOCATION_TABLE_BYTES.toLong()) {
            log.info(
                "[RUN] region file too small; skipping file={} sizeBytes={}",
                regionFile,
                size,
            )
            return null
        }

        return try {
            Files.newInputStream(regionFile).use { input ->
                val buf = ByteArray(LOCATION_TABLE_BYTES)
                var readTotal = 0
                while (readTotal < buf.size) {
                    val r = input.read(buf, readTotal, buf.size - readTotal)
                    if (r < 0) break
                    readTotal += r
                }
                if (readTotal < buf.size) {
                    log.info(
                        "[RUN] region header short read; skipping file={} readBytes={} expectedBytes={}",
                        regionFile,
                        readTotal,
                        buf.size,
                    )
                    null
                } else {
                    buf
                }
            }
        } catch (e: IOException) {
            log.info("[RUN] region file read failed; skipping file={} error={}", regionFile, e.message)
            null
        } catch (e: SecurityException) {
            log.info("[RUN] region file read denied; skipping file={} error={}", regionFile, e.message)
            null
        }
    }

    private companion object {
        private const val REGION_WIDTH: Int = 32
        private const val LOCATION_ENTRY_BYTES: Int = 4
        private const val LOCATION_TABLE_ENTRIES: Int = REGION_WIDTH * REGION_WIDTH
        private const val LOCATION_TABLE_BYTES: Int = LOCATION_TABLE_ENTRIES * LOCATION_ENTRY_BYTES
    }
}
