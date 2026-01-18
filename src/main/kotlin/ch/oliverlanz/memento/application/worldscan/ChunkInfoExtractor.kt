package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.domain.memento.ChunkKey
import ch.oliverlanz.memento.domain.memento.ChunkSignals
import ch.oliverlanz.memento.domain.memento.WorldMementoSubstrate
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import org.slf4j.LoggerFactory
import kotlin.math.min

/**
 * Application-layer, tick-paced extractor.
 *
 * Semantics:
 * - Only EXISTING chunks are processed
 * - Missing metadata is an error and surfaces as NULL
 * - No chunk generation
 */
class ChunkInfoExtractor {

    private val log = LoggerFactory.getLogger("memento")

    private var plan: WorldDiscoveryPlan? = null
    private var substrate: WorldMementoSubstrate? = null

    private var worldIdx = 0
    private var regionIdx = 0
    private var chunkIdx = 0

    private var currentWorld: ServerWorld? = null
    private var runtimeReader: ChunkRuntimeMetadataReader? = null

    private var metaErrors = 0L

    fun start(plan: WorldDiscoveryPlan, substrate: WorldMementoSubstrate) {
        this.plan = plan
        this.substrate = substrate

        worldIdx = 0
        regionIdx = 0
        chunkIdx = 0

        currentWorld = null
        runtimeReader = null

        metaErrors = 0
    }

    fun readNext(server: MinecraftServer, maxChunkSlots: Int): Boolean {
        val p = plan ?: return false
        val s = substrate ?: return false

        var remaining = maxChunkSlots
        while (remaining > 0) {
            val worldPlan = p.worlds.getOrNull(worldIdx) ?: return finishAll()
            val region = worldPlan.regions.getOrNull(regionIdx) ?: run {
                worldIdx++
                regionIdx = 0
                chunkIdx = 0
                continue
            }

            if (currentWorld == null) {
                val w = server.getWorld(worldPlan.world)
                if (w == null) {
                    worldIdx++
                    regionIdx = 0
                    chunkIdx = 0
                    continue
                }
                currentWorld = w
                runtimeReader = ChunkRuntimeMetadataReader(w)
            }

            val chunks = region.chunks
            if (chunks.isEmpty()) {
                regionIdx++
                chunkIdx = 0
                continue
            }

            if (chunkIdx >= chunks.size) {
                regionIdx++
                chunkIdx = 0
                continue
            }

            val processedNow = min(remaining, chunks.size - chunkIdx)
            for (i in 0 until processedNow) {
                val chunkRef = chunks[chunkIdx + i]

                val chunkX = region.x * 32 + chunkRef.localX
                val chunkZ = region.z * 32 + chunkRef.localZ
                val chunkPos = ChunkPos(chunkX, chunkZ)

                val metadata = try {
                    runtimeReader!!.read(chunkPos)
                } catch (e: Exception) {
                    metaErrors++
                    log.error(
                        "[RUN] metadata read failed world={} chunk=({}, {}) error={}",
                        worldPlan.world.value,
                        chunkX,
                        chunkZ,
                        e.message,
                    )
                    ChunkRuntimeMetadata(null, null)
                }

                // Skip non-existing chunks entirely
                if (metadata == null) {
                    continue
                }

                val key = ChunkKey(
                    world = worldPlan.world,
                    regionX = region.x,
                    regionZ = region.z,
                    chunkX = chunkX,
                    chunkZ = chunkZ,
                )

                val signals = ChunkSignals(
                    inhabitedTimeTicks = metadata.inhabitedTimeTicks,
                    lastUpdateTicks = metadata.lastUpdateTicks,
                    surfaceY = null,
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
        log.info(
            "[RUN] chunk metadata extraction completed metadataErrors={}",
            metaErrors,
        )
        return false
    }
}
