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
 * Slice: runtime metadata inspection (Option 2).
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

    private var metaOk = 0L
    private var metaFailed = 0L
    private var metaMissing = 0L

    fun start(plan: WorldDiscoveryPlan, substrate: WorldMementoSubstrate) {
        this.plan = plan
        this.substrate = substrate

        worldIdx = 0
        regionIdx = 0
        chunkIdx = 0

        currentWorld = null
        runtimeReader = null

        metaOk = 0
        metaFailed = 0
        metaMissing = 0
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

                val key = ChunkKey(
                    world = worldPlan.world,
                    regionX = region.x,
                    regionZ = region.z,
                    chunkX = chunkX,
                    chunkZ = chunkZ,
                )

                val meta = runtimeReader!!.read(chunkPos)
                if (meta.ok) metaOk++ else metaFailed++
                if (!meta.hasValues) metaMissing++

                val signals = ChunkSignals(
                    inhabitedTimeTicks = meta.inhabitedTimeTicks,
                    lastUpdateTicks = meta.lastUpdateTicks,
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
        val total = metaOk + metaFailed
        log.info(
            "[RUN] chunk metadata extraction summary total={} ok={} failed={} missingOrZero={}",
            total,
            metaOk,
            metaFailed,
            metaMissing,
        )
        return false
    }
}
