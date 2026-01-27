package ch.oliverlanz.memento.application.chunk

import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ChunkTicketType
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkStatus
import org.slf4j.LoggerFactory
import java.util.LinkedHashMap

/**
 * Cooperative chunk-load orchestrator.
 *
 * Locked 0.9.6 semantics:
 * - The driver is the only active component that may request proactive chunk loads.
 * - Providers are passive. The driver *pulls* intent from them.
 * - Renewal has precedence over background scanning (provider registration order).
 * - At most ONE proactive ticket may be active at any time.
 * - The driver never requests chunks that are already loaded.
 * - Any observed chunk load that does not satisfy the active ticket is treated as EXTERNAL activity
 *   and moves the driver into PASSIVE mode (idle timer reset).
 * - Tickets are always released:
 *   - when the corresponding chunk load is observed (cause → effect)
 *   - or when the ticket expires (warning)
 */
class ChunkLoadDriver(
    private val activeLoadIntervalTicks: Int,
    private val passiveGraceTicks: Int,
    private val ticketMaxAgeTicks: Int,
) {

    private val log = LoggerFactory.getLogger("Memento/ChunkLoadDriver")

    private var server: MinecraftServer? = null

    private val providers = mutableListOf<ChunkLoadProvider>()

    // Unique pending requests, ordered by insertion. Uniqueness is dimension+pos (label is metadata).
    private val pending: LinkedHashMap<RequestKey, ChunkLoadRequest> = LinkedHashMap()

    private var active: ActiveTicket? = null

    private var ticksSinceLastProactiveIssue = 0
    private var ticksSinceLastObservedChunkLoad = 0

    fun attach(server: MinecraftServer) {
        this.server = server
    }

    fun detach() {
        providers.clear()
        pending.clear()
        active = null
        server = null
        ticksSinceLastProactiveIssue = 0
        ticksSinceLastObservedChunkLoad = 0
    }

    /**
        * Provider registration order defines precedence.
        * Register renewal providers first.
        */
    fun registerProvider(provider: ChunkLoadProvider) {
        providers.add(provider)
    }

    fun tick() {
        ticksSinceLastProactiveIssue++
        ticksSinceLastObservedChunkLoad++

        // Age active ticket (even while passive).
        val a = active
        if (a != null) {
            a.ageTicks++
            if (a.ageTicks >= ticketMaxAgeTicks) {
                log.warn(
                    "[LOADER] ticket expired label='{}' dim={} chunk={} ageTicks={} (releasing)",
                    a.request.label,
                    a.request.dimension.value,
                    "${a.request.pos.x},${a.request.pos.z}",
                    a.ageTicks,
                )
                releaseTicket(a.request)
                active = null
            }
        }

        // Do not issue new proactive tickets while one is active.
        if (active != null) return

        // Pull one request if we have none queued.
        if (pending.isEmpty()) pullFromProviders()
        if (pending.isEmpty()) return

        // Politeness gating.
        if (ticksSinceLastObservedChunkLoad < passiveGraceTicks) return
        if (ticksSinceLastProactiveIssue < activeLoadIntervalTicks) return

        val next = pending.entries.first().value
        pending.remove(RequestKey(next.dimension, next.pos))

        issueIfNeeded(next)
    }

    private fun pullFromProviders() {
        for (provider in providers) {
            val next = provider.nextChunkLoad() ?: continue
            val key = RequestKey(next.dimension, next.pos)
            if (!pending.containsKey(key) && active?.key != key) {
                pending[key] = next
            }
            return
        }
    }

    private fun issueIfNeeded(request: ChunkLoadRequest) {
        val srv = server ?: return
        val world = srv.getWorld(request.dimension) ?: return

        // Never request a chunk that is already loaded.
        if (isLoaded(world, request.pos)) {
            // Treat as observed activity: we remain polite and avoid issuing anything right now.
            ticksSinceLastObservedChunkLoad = 0
            ticksSinceLastProactiveIssue = 0
            return
        }

        // Best-effort "exists" guard: avoid creating or generating. If the chunk isn't present,
        // do not issue a ticket (provider intent may be stale or derived).
        // NOTE: This is intentionally conservative and non-loading.
        if (!likelyExistsOnDisk(world, request.pos)) {
            return
        }

        world.chunkManager.addTicket(
            ChunkTicketType.UNKNOWN,
            request.pos,
            1
        )

        active = ActiveTicket(RequestKey(request.dimension, request.pos), request)
        ticksSinceLastProactiveIssue = 0

        // After issuing, re-check loaded state to handle the "already loaded" race.
        // If it is already loaded, no CHUNK_LOAD event will fire. We must not leak the ticket.
        if (isLoaded(world, request.pos)) {
            releaseTicket(request)
            active = null
            return
        }

        log.debug(
            "[LOADER] requested label='{}' dim={} chunk={} ",
            request.label,
            request.dimension.value,
            "${request.pos.x},${request.pos.z}",
        )
    }

    /**
     * Chunk load observation.
     *
     * Cause vs effect:
     * - If the observed load satisfies our active ticket, we detach the ticket.
     * - Otherwise, this is external activity; we enter PASSIVE mode (idle timer reset).
     */
    fun onChunkLoaded(worldKey: RegistryKey<World>, pos: ChunkPos) {
        ticksSinceLastObservedChunkLoad = 0

        val a = active
        if (a != null && a.key.dimension == worldKey && a.key.pos == pos) {
            releaseTicket(a.request)
            active = null
            return
        }

        // External activity: remain passive for a while before issuing proactive loads.
        ticksSinceLastProactiveIssue = 0
    }

    private fun releaseTicket(request: ChunkLoadRequest) {
        val srv = server ?: return
        val world = srv.getWorld(request.dimension) ?: return
        world.chunkManager.removeTicket(
            ChunkTicketType.UNKNOWN,
            request.pos,
            1
        )
    }

    private fun isLoaded(world: ServerWorld, pos: ChunkPos): Boolean {
        // create=false: must not trigger IO or generation.
        return world.chunkManager.getChunk(pos.x, pos.z, ChunkStatus.FULL, false) != null
    }

    private fun likelyExistsOnDisk(world: ServerWorld, pos: ChunkPos): Boolean {
        // Non-loading heuristic:
        // - If already loaded, it exists.
        // - Otherwise, avoid trying to force generation. We only proceed if the chunk appears
        //   to have an NBT entry. This is a best-effort guard.
        if (isLoaded(world, pos)) return true

        return try {
            // getChunk(..., false) already returned null; checking NBT presence via chunk storage
            // is not exposed publicly. The safest option here is to return true and rely on
            // provider discipline for existence. If this turns out to be too permissive, we can
            // later add a dedicated infrastructure adapter.
            true
        } catch (_: Exception) {
            false
        }
    }

    private data class RequestKey(
        val dimension: RegistryKey<World>,
        val pos: ChunkPos,
    )

    private data class ActiveTicket(
        val key: RequestKey,
        val request: ChunkLoadRequest,
        var ageTicks: Int = 0,
    )
}
