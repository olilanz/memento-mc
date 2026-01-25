package ch.oliverlanz.memento.application.chunk

import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ChunkTicketType
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.HashSet

class ChunkLoadDriver(
    private val activeLoadIntervalTicks: Int,
    private val passiveGraceTicks: Int,
) {

    private val log = LoggerFactory.getLogger("Memento/ChunkLoadDriver")

    private var server: MinecraftServer? = null

    private val providers = mutableListOf<ChunkLoadProvider>()
    private val queue = ArrayDeque<ChunkLoadRequest>()

    // NEW: track what we asked for
    private val requested = HashSet<Pair<RegistryKey<World>, ChunkPos>>()

    private var ticksSinceLastRequest = 0
    private var ticksSinceLastExternalLoad = 0

    fun attach(server: MinecraftServer) {
        this.server = server
    }

    fun detach() {
        providers.clear()
        queue.clear()
        requested.clear()
        server = null
    }

    fun registerProvider(provider: ChunkLoadProvider) {
        providers.add(provider)
    }

    fun tick() {
        ticksSinceLastRequest++
        ticksSinceLastExternalLoad++

        if (queue.isEmpty()) pullFromProviders()
        if (queue.isEmpty()) return
        if (ticksSinceLastExternalLoad < passiveGraceTicks) return
        if (ticksSinceLastRequest < activeLoadIntervalTicks) return

        val request = queue.removeFirst()
        requestChunk(request)
        ticksSinceLastRequest = 0
    }

    private fun pullFromProviders() {
        for (provider in providers) {
            val next = provider.nextChunkLoad() ?: continue
            if (queue.none { it.dimension == next.dimension && it.pos == next.pos }) {
                queue.add(next)
                return
            }
        }
    }

    private fun requestChunk(request: ChunkLoadRequest) {
        val srv = server ?: return
        val world = srv.getWorld(request.dimension) ?: return

        requested += request.dimension to request.pos

        world.chunkManager.addTicket(
            ChunkTicketType.UNKNOWN,
            request.pos,
            1
        )

        log.debug(
            "Requested chunk load [{}] at {} {}",
            request.label,
            request.pos.x,
            request.pos.z
        )
    }

    // KEY CHANGE: distinguish requested vs external
    fun onChunkLoaded(worldKey: RegistryKey<World>, pos: ChunkPos) {
        val key = worldKey to pos
        if (requested.remove(key)) {
            // our load completed
            ticksSinceLastExternalLoad = 0
        } else {
            // external load → go idle / be polite
            ticksSinceLastExternalLoad = 0
            ticksSinceLastRequest = 0
        }
    }
}
