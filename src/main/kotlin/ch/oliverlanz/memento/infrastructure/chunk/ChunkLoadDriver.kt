package ch.oliverlanz.memento.infrastructure.chunk

import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ChunkTicketType
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.ChunkStatus
import net.minecraft.world.chunk.WorldChunk
import org.slf4j.LoggerFactory

/**
 * Cooperative chunk-load orchestrator.
 *
 * Locked semantics:
 * - The driver is the only active component that may request proactive chunk loads (tickets).
 * - Providers are passive and declarative: they expose desired chunk loads via [ChunkLoadProvider.desiredChunkLoads].
 * - Provider registration order defines precedence (renewal first, scanner last).
 * - At most ONE proactive ticket may be active at any time.
 * - Any EXTERNAL (unsolicited) chunk load moves the driver into PASSIVE mode for [passiveGraceTicks].
 * - Renewal is prioritized whenever the driver is ACTIVE.
 * - Scanner runs opportunistically, only when no renewal intent exists.
 *
 * The driver also acts as the single fan-out point for observed chunk loads:
 * application and domain code should not subscribe to Fabric chunk events directly.
 */
class ChunkLoadDriver(
    private val activeLoadIntervalTicks: Int,
    private val passiveGraceTicks: Int,
    private val ticketMaxAgeTicks: Int,
) {

    private val log = LoggerFactory.getLogger("Memento/ChunkLoadDriver")

    private var server: MinecraftServer? = null

    private val providers = mutableListOf<ChunkLoadProvider>()
    private val consumers = mutableListOf<ChunkLoadConsumer>()

    private var active: ActiveTicket? = null

    // Throttling / politeness
    private var ticksSinceLastProactiveIssue: Int = 0
    private var ticksSinceLastExternalLoad: Int = Int.MAX_VALUE

    fun attach(server: MinecraftServer) {
        this.server = server
        this.ticksSinceLastProactiveIssue = 0
        this.ticksSinceLastExternalLoad = Int.MAX_VALUE
    }

    fun detach() {
        providers.clear()
        consumers.clear()
        active = null
        server = null
        ticksSinceLastProactiveIssue = 0
        ticksSinceLastExternalLoad = Int.MAX_VALUE
    }

    /**
     * Provider registration order defines precedence.
     * Register renewal providers first.
     */
    fun registerProvider(provider: ChunkLoadProvider) {
        providers.add(provider)
    }

    /** Register a consumer of observed chunk availability. */
    fun registerConsumer(consumer: ChunkLoadConsumer) {
        consumers.add(consumer)
    }

    fun tick() {
        ticksSinceLastProactiveIssue++
        ticksSinceLastExternalLoad++

        // Age active ticket (even while passive).
        val a = active
        if (a != null) {
            a.ageTicks++
            if (a.ageTicks >= ticketMaxAgeTicks) {
                log.warn(
                    "[LOADER] ticket expired provider='{}' label='{}' dim={} chunk={} ageTicks={} (releasing)",
                    a.providerName,
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

        // Politeness gating: if external load activity is ongoing, stay passive.
        if (ticksSinceLastExternalLoad < passiveGraceTicks) return

        // Throttle proactive issues.
        if (ticksSinceLastProactiveIssue < activeLoadIntervalTicks) return

        // Choose the next feasible request, honoring provider precedence.
        val selection = selectNextRequest() ?: return
        issueOrSynthesize(selection.providerName, selection.request)
    }

    /**
     * Engine chunk load observation (single fan-out point).
     *
     * Cause vs effect is handled internally:
     * - If the observed load satisfies our active ticket, we detach the ticket.
     * - Otherwise, this is EXTERNAL activity; we enter PASSIVE mode (idle timer reset).
     *
     * Consumers are notified for ALL loads (driver-caused or external).
     */
    fun onEngineChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        val pos = chunk.pos
        val key = RequestKey(world.registryKey, pos)

        val a = active
        val matched = (a != null && a.key == key)

        if (matched) {
            // Cause → effect: detach ticket and clear before notifying (so consumer logic may schedule new intent).
            releaseTicket(a!!.request)
            active = null
        } else {
            // External activity: remain passive for a while before issuing proactive loads.
            ticksSinceLastExternalLoad = 0
            ticksSinceLastProactiveIssue = 0
        }

        // Fan out.
        for (c in consumers) {
            try {
                c.onChunkLoaded(world, chunk)
            } catch (e: Exception) {
                log.error("[LOADER] consumer failed onChunkLoaded consumer={}", c.javaClass.simpleName, e)
            }
        }
    }

    fun onEngineChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
        for (c in consumers) {
            try {
                c.onChunkUnloaded(world, pos)
            } catch (e: Exception) {
                log.error("[LOADER] consumer failed onChunkUnloaded consumer={}", c.javaClass.simpleName, e)
            }
        }
    }

    private data class Selection(val providerName: String, val request: ChunkLoadRequest)

    private fun selectNextRequest(): Selection? {
        val srv = server ?: return null

        for (provider in providers) {
            val candidate = provider.desiredChunkLoads().firstOrNull() ?: continue

            // If world is missing, skip this provider for now.
            val world = srv.getWorld(candidate.dimension) ?: continue

            // If already loaded, we can synthesize immediately; selection still valid.
            // Existence checks for non-loaded chunks remain best-effort.
            return Selection(provider.name, candidate)
        }

        return null
    }

    private fun issueOrSynthesize(providerName: String, request: ChunkLoadRequest) {
        val srv = server ?: return
        val world = srv.getWorld(request.dimension) ?: return

        // If already loaded, synthesize availability without issuing a ticket.
        val alreadyLoaded = loadedChunkOrNull(world, request.pos)
        if (alreadyLoaded != null) {
            log.debug(
                "[LOADER] synthesize loaded provider='{}' label='{}' dim={} chunk={}",
                providerName,
                request.label,
                request.dimension.value,
                "${request.pos.x},${request.pos.z}",
            )
            // Treat as internal fulfillment; do not enter passive mode.
            ticksSinceLastProactiveIssue = 0
            for (c in consumers) {
                try {
                    c.onChunkLoaded(world, alreadyLoaded)
                } catch (e: Exception) {
                    log.error("[LOADER] consumer failed onChunkLoaded consumer={}", c.javaClass.simpleName, e)
                }
            }
            return
        }

        // Best-effort "exists" guard: avoid creating or generating.
        if (!likelyExistsOnDisk(world, request.pos)) {
            // Intent may be stale or derived; simply skip.
            return
        }

        world.chunkManager.addTicket(
            ChunkTicketType.UNKNOWN,
            request.pos,
            1
        )

        active = ActiveTicket(
            key = RequestKey(request.dimension, request.pos),
            request = request,
            providerName = providerName
        )
        ticksSinceLastProactiveIssue = 0

        // After issuing, re-check loaded state to handle the "already loaded" race.
        // If it is already loaded, no CHUNK_LOAD event will fire. We must not leak the ticket.
        val loadedAfterIssue = loadedChunkOrNull(world, request.pos)
        if (loadedAfterIssue != null) {
            releaseTicket(request)
            active = null
            log.debug(
                "[LOADER] race: already loaded after ticket provider='{}' label='{}' dim={} chunk={} (detached, synthesizing)",
                providerName,
                request.label,
                request.dimension.value,
                "${request.pos.x},${request.pos.z}",
            )
            for (c in consumers) {
                try {
                    c.onChunkLoaded(world, loadedAfterIssue)
                } catch (e: Exception) {
                    log.error("[LOADER] consumer failed onChunkLoaded consumer={}", c.javaClass.simpleName, e)
                }
            }
            return
        }

        log.debug(
            "[LOADER] requested provider='{}' label='{}' dim={} chunk={}",
            providerName,
            request.label,
            request.dimension.value,
            "${request.pos.x},${request.pos.z}",
        )
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

    private fun loadedChunkOrNull(world: ServerWorld, pos: ChunkPos): WorldChunk? {
        // create=false: must not trigger IO or generation.
        val c = world.chunkManager.getChunk(pos.x, pos.z, ChunkStatus.FULL, false)
        return c as? WorldChunk
    }

    private fun likelyExistsOnDisk(world: ServerWorld, pos: ChunkPos): Boolean {
        // Non-loading heuristic:
        // - If already loaded, it exists.
        // - Otherwise, do not try to force generation. We keep this permissive for now and
        //   rely on provider discipline derived from region discovery.
        return loadedChunkOrNull(world, pos) != null || true
    }

    private data class RequestKey(
        val dimension: net.minecraft.registry.RegistryKey<net.minecraft.world.World>,
        val pos: ChunkPos,
    )

    private data class ActiveTicket(
        val key: RequestKey,
        val request: ChunkLoadRequest,
        val providerName: String,
        var ageTicks: Int = 0,
    )
}
