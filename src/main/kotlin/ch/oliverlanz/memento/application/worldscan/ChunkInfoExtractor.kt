package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.application.chunk.ChunkLoadRequest
import ch.oliverlanz.memento.domain.memento.ChunkKey
import ch.oliverlanz.memento.domain.memento.ChunkSignals
import ch.oliverlanz.memento.domain.memento.WorldMementoSubstrate
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.Heightmap
import net.minecraft.world.chunk.WorldChunk
import net.minecraft.world.chunk.ChunkStatus
import org.slf4j.LoggerFactory

class ChunkInfoExtractor {

    private val log = LoggerFactory.getLogger("memento")

    private var plan: WorldDiscoveryPlan? = null
    private var substrate: WorldMementoSubstrate? = null

    private var worldIdx = 0
    private var regionIdx = 0
    private var chunkIdx = 0

    private var currentWorld: ServerWorld? = null

    private val scannedKeys: MutableSet<ChunkKey> = mutableSetOf()

    private var pending: PendingScan? = null

    fun start(plan: WorldDiscoveryPlan, substrate: WorldMementoSubstrate) {
        this.plan = plan
        this.substrate = substrate
        worldIdx = 0
        regionIdx = 0
        chunkIdx = 0
        currentWorld = null
        scannedKeys.clear()
        pending = null
    }

    /**
     * Provider-style scan: offer at most one chunk-load request.
     *
     * The driver controls when to load; the extractor only decides what is next.
     */
    fun nextChunkLoad(server: MinecraftServer, label: String): ChunkLoadRequest? {
        val p = plan ?: return null
        val s = substrate ?: return null

        // Opportunistic path:
        // If we are waiting for a pending chunk but it is already loaded (external cause),
        // consume it immediately and continue without going through the driver.
        val pendNow = pending
        if (pendNow != null) {
            val w = server.getWorld(pendNow.worldKey)
            if (w != null) {
                val maybeLoaded = w.chunkManager.getChunk(pendNow.pos.x, pendNow.pos.z, ChunkStatus.FULL, false)
                if (maybeLoaded is WorldChunk) {
                    onChunkLoaded(w, maybeLoaded)
                }
            }
        }

        // If we already offered a chunk and are still waiting for it to be observed, do not advance.
        // (If it was already loaded, the opportunistic block above will have consumed it.)
        val inFlight = pending
        if (inFlight != null) {
            return ChunkLoadRequest(label = label, dimension = inFlight.worldKey, pos = inFlight.pos)
        }

        while (true) {
            val candidate = findNextCandidate(server, p, s) ?: return null

            // If the next candidate is already loaded, consume it immediately and keep searching.
            val w = server.getWorld(candidate.worldKey)
            if (w != null) {
                val maybeLoaded = w.chunkManager.getChunk(candidate.pos.x, candidate.pos.z, ChunkStatus.FULL, false)
                if (maybeLoaded is WorldChunk) {
                    // Opportunistic consume: the chunk is already loaded.
                    // Set it as pending so we advance the scan cursor deterministically.
                    pending = candidate
                    onChunkLoaded(w, maybeLoaded)
                    continue
                }
            }

            pending = candidate
            return ChunkLoadRequest(label = label, dimension = candidate.worldKey, pos = candidate.pos)
        }
    }

    /**
     * Must be called on observed chunk loads.
     *
     * Only advances the scan cursor when the observed load matches the currently pending request.
     */
    fun onChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        val p = plan ?: return
        val s = substrate ?: return
        val pend = pending ?: return
        if (pend.worldKey != world.registryKey) return
        if (pend.pos != chunk.pos) return
        currentWorld = world


        // IMPORTANT: never call World#getTopY / World#getChunk / World#getBiome here.
        // This callback may run while chunk generation is still resolving on the main thread,
        // and any synchronous chunk access can deadlock the server.
        //
        // We only read from the already-loaded chunk instance provided by the engine.

        val surfaceY = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(8, 8)

        // Biome lookup via ServerWorld can re-enter chunk access. Keep it non-blocking.
        // (CSV schema remains unchanged; value may be refined later.)
        val biomeId = "unknown"

        val signals = ChunkSignals(
            inhabitedTimeTicks = chunk.inhabitedTime,
            lastUpdateTicks = null,
            surfaceY = surfaceY,
            biomeId = biomeId,
            isSpawnChunk = false,
        )
        s.upsert(pend.key, signals)
        scannedKeys.add(pend.key)

        // Commit advancement by consuming exactly one chunk slot.
        chunkIdx++
        pending = null
    }

    fun isComplete(): Boolean {
        val p = plan ?: return true
        // Complete when we have no pending request AND no next candidate.
        if (pending != null) return false

        // Avoid mutating state during completion check; use a copy of indices.
        var wi = worldIdx
        var ri = regionIdx
        var ci = chunkIdx
        while (true) {
            val worldPlan = p.worlds.getOrNull(wi) ?: return true
            val region = worldPlan.regions.getOrNull(ri) ?: run {
                wi++
                ri = 0
                ci = 0
                continue
            }
            val chunks = region.chunks
            if (ci >= chunks.size) {
                ri++
                ci = 0
                continue
            }
            // There is still at least one chunk slot to consider.
            return false
        }
    }

    // Legacy tick-based API kept for compatibility; no longer used by 0.9.6 provider model.
    fun readNext(server: MinecraftServer, maxChunkSlots: Int): Boolean {
        val p = plan ?: return false
        val s = substrate ?: return false
        log.warn("[SCAN] legacy tick-based extraction invoked; prefer provider model")
        return false
    }

    private fun findNextCandidate(
        server: MinecraftServer,
        plan: WorldDiscoveryPlan,
        substrate: WorldMementoSubstrate,
    ): PendingScan? {
        while (true) {
            val worldPlan = plan.worlds.getOrNull(worldIdx) ?: return null
            val region = worldPlan.regions.getOrNull(regionIdx) ?: run {
                worldIdx++
                regionIdx = 0
                chunkIdx = 0
                currentWorld = null
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
            }

            val chunks = region.chunks
            if (chunkIdx >= chunks.size) {
                regionIdx++
                chunkIdx = 0
                continue
            }

            val ref = chunks[chunkIdx]
            val chunkX = region.x * 32 + ref.localX
            val chunkZ = region.z * 32 + ref.localZ
            val chunkPos = ChunkPos(chunkX, chunkZ)

            val key = ChunkKey(
                world = worldPlan.world,
                regionX = region.x,
                regionZ = region.z,
                chunkX = chunkX,
                chunkZ = chunkZ,
            )

            // Skip chunks already scanned (e.g. if we later ingest external loads).
            if (scannedKeys.contains(key)) {
                chunkIdx++
                continue
            }

            return PendingScan(
                worldKey = worldPlan.world,
                pos = chunkPos,
                key = key,
            )
        }
    }

    private data class PendingScan(
        val worldKey: net.minecraft.registry.RegistryKey<net.minecraft.world.World>,
        val pos: ChunkPos,
        val key: ChunkKey,
    )
}
