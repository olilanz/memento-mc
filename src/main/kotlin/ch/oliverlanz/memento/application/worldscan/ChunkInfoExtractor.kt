package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.domain.memento.ChunkKey
import ch.oliverlanz.memento.domain.memento.ChunkSignals
import ch.oliverlanz.memento.domain.memento.WorldMementoSubstrate
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.Heightmap
import org.slf4j.LoggerFactory
import kotlin.math.min

class ChunkInfoExtractor {

    private val log = LoggerFactory.getLogger("memento")

    private var plan: WorldDiscoveryPlan? = null
    private var substrate: WorldMementoSubstrate? = null

    private var worldIdx = 0
    private var regionIdx = 0
    private var chunkIdx = 0

    private var currentWorld: ServerWorld? = null
    private var runtimeReader: ChunkRuntimeMetadataReader? = null

    fun start(plan: WorldDiscoveryPlan, substrate: WorldMementoSubstrate) {
        this.plan = plan
        this.substrate = substrate
        worldIdx = 0
        regionIdx = 0
        chunkIdx = 0
        currentWorld = null
        runtimeReader = null
    }

    fun readNext(server: MinecraftServer, maxChunkSlots: Int): Boolean {
        val p = plan ?: return false
        val s = substrate ?: return false

        var remaining = maxChunkSlots
        while (remaining > 0) {
            val worldPlan = p.worlds.getOrNull(worldIdx) ?: return false
            val region = worldPlan.regions.getOrNull(regionIdx) ?: run {
                worldIdx++
                regionIdx = 0
                chunkIdx = 0
                currentWorld = null
                runtimeReader = null
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
            if (chunkIdx >= chunks.size) {
                regionIdx++
                chunkIdx = 0
                continue
            }

            val processedNow = min(remaining, chunks.size - chunkIdx)
            for (i in 0 until processedNow) {
                val ref = chunks[chunkIdx + i]

                val chunkX = region.x * 32 + ref.localX
                val chunkZ = region.z * 32 + ref.localZ
                val chunkPos = ChunkPos(chunkX, chunkZ)

                val metadata = runtimeReader!!.loadAndReadExisting(chunkPos)
                    ?: continue

                val w = currentWorld!!
                val centerX = chunkX * 16 + 8
                val centerZ = chunkZ * 16 + 8
                val surfaceY = w.getTopY(Heightmap.Type.WORLD_SURFACE, centerX, centerZ)

                val biomeId = w
                    .getBiome(BlockPos(centerX, surfaceY, centerZ))
                    .value()
                    .toString()

                val key = ChunkKey(
                    world = worldPlan.world,
                    regionX = region.x,
                    regionZ = region.z,
                    chunkX = chunkX,
                    chunkZ = chunkZ,
                )

                val signals = ChunkSignals(
                    inhabitedTimeTicks = metadata.inhabitedTimeTicks,
                    lastUpdateTicks = null,
                    surfaceY = surfaceY,
                    biomeId = biomeId,
                    isSpawnChunk = false,
                )

                s.upsert(key, signals)
            }

            chunkIdx += processedNow
            remaining -= processedNow
        }

        return true
    }
}
