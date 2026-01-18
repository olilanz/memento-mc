package ch.oliverlanz.memento.application.run

import ch.oliverlanz.memento.domain.memento.ChunkKey
import ch.oliverlanz.memento.domain.memento.ChunkSignals
import ch.oliverlanz.memento.domain.memento.WorldMementoSubstrate
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import kotlin.math.min

/**
 * Application-layer, tick-paced extractor.
 *
 * Slice 1: produces deterministic dummy signals but exercises real scheduling and data flow.
 */
class ChunkInfoExtractor {

    private val log = LoggerFactory.getLogger("memento")

    private var plan: WorldDiscoveryPlan? = null
    private var substrate: WorldMementoSubstrate? = null

    private var worldIdx: Int = 0
    private var regionIdx: Int = 0
    private var chunkIdx: Int = 0

    private var currentRegionStartNanos: Long? = null
    private var currentRegionKey: String? = null

    /**
     * Prepare the extractor for a new run.
     */
    fun start(plan: WorldDiscoveryPlan, substrate: WorldMementoSubstrate) {
        this.plan = plan
        this.substrate = substrate
        worldIdx = 0
        regionIdx = 0
        chunkIdx = 0
        currentRegionStartNanos = null
        currentRegionKey = null
    }

    /**
     * Processes up to [maxChunkSlots] dummy "chunk slots" and returns:
     * - true if more work remains
     * - false if extraction is complete
     */
    fun readNext(server: MinecraftServer, maxChunkSlots: Int): Boolean {
        val p = plan ?: return false
        val s = substrate ?: return false

        var remaining = max(0, maxChunkSlots)
        while (remaining > 0) {
            val world = p.worlds.getOrNull(worldIdx) ?: return finishAll()
            val region = world.regions.getOrNull(regionIdx) ?: run {
                // Move to next world.
                worldIdx++
                regionIdx = 0
                chunkIdx = 0
                continue
            }

            val regionKey = "${world.world.value}:r${region.x},${region.z}"
            if (currentRegionKey != regionKey) {
                // Close previous region timing.
                val start = currentRegionStartNanos
                val prevKey = currentRegionKey
                if (start != null && prevKey != null) {
                    val ms = (System.nanoTime() - start) / 1_000_000
                    log.info("[RUN] region completed {} durationMs={} recordsSoFar={}", prevKey, ms, s.size())
                }

                currentRegionKey = regionKey
                currentRegionStartNanos = System.nanoTime()
                log.info("[RUN] region start {}", regionKey)
            }

            val chunks = region.chunks
            if (chunks.isEmpty()) {
                // Tracer-bullet: region has no planned chunk work. Move on.
                regionIdx++
                chunkIdx = 0
                continue
            }

            if (chunkIdx >= chunks.size) {
                // Move to next region.
                regionIdx++
                chunkIdx = 0
                continue
            }

            val processedNow = min(remaining, chunks.size - chunkIdx)
            for (i in 0 until processedNow) {
                val chunkRef = chunks[chunkIdx + i]
                val chunkX = region.x * 32 + chunkRef.localX
                val chunkZ = region.z * 32 + chunkRef.localZ

                val key = ChunkKey(
                    world = world.world,
                    regionX = region.x,
                    regionZ = region.z,
                    chunkX = chunkX,
                    chunkZ = chunkZ,
                )

                val signals = ChunkSignals(
                    inhabitedTimeTicks = ((chunkIdx + i).toLong() + 1) * 100L,
                    surfaceY = 64,
                    biomeId = null,
                    isSpawnChunk = (chunkX == 0 && chunkZ == 0),
                )

                s.upsert(key, signals)
            }

            chunkIdx += processedNow
            remaining -= processedNow
        }

        return true
    }

    private fun finishAll(): Boolean {
        val start = currentRegionStartNanos
        val prevKey = currentRegionKey
        if (start != null && prevKey != null) {
            val ms = (System.nanoTime() - start) / 1_000_000
            log.info("[RUN] region completed {} durationMs={} (final)", prevKey, ms)
        }
        currentRegionStartNanos = null
        currentRegionKey = null
        return false
    }

    private fun max(a: Int, b: Int): Int = if (a > b) a else b

    // Tracer-bullet: keep tiny and fast.
}
