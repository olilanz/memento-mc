
package ch.oliverlanz.memento.infrastructure.chunk

import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk
import org.slf4j.LoggerFactory

class ChunkLoadDriver(
    val activeLoadIntervalTicks: Int,
    val passiveGraceTicks: Int,
    val ticketMaxAgeTicks: Int
) {
    private val providers = mutableListOf<ChunkLoadProvider>()
    private val listeners = mutableListOf<ChunkAvailabilityListener>()
    private var server: MinecraftServer? = null

    private val log = LoggerFactory.getLogger("memento")

    private var tickCounter: Long = 0
    private var lastExternalLoadTick: Long = Long.MIN_VALUE
    private var lastModeLogTick: Long = 0
    private var lastIntentLogTick: Long = 0

    fun attach(server: MinecraftServer) {
        this.server = server
    }

    fun detach() {
        providers.clear()
        listeners.clear()
        server = null
    }

    fun registerProvider(provider: ChunkLoadProvider) {
        providers += provider
    }

    fun registerConsumer(listener: ChunkAvailabilityListener) {
        listeners += listener
    }

    fun onEngineChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        // Treat all engine loads as "external" for mode observability.
        // Driver-caused loads (tickets) should ideally be distinguished by the real scheduling logic.
        lastExternalLoadTick = tickCounter
        listeners.forEach { it.onChunkLoaded(world, chunk) }
    }

    fun onEngineChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
        listeners.forEach { it.onChunkUnloaded(world, pos) }
    }

    fun tick() {
        val s = server ?: return
        tickCounter++

        val passive = (tickCounter - lastExternalLoadTick) <= passiveGraceTicks
        // Log mode occasionally (rate-limited).
        if ((tickCounter - lastModeLogTick) >= 100) {
            lastModeLogTick = tickCounter
            log.info("[DRIVER] mode={} tick={} externalAge={} graceTicks={}", if (passive) "PASSIVE" else "ACTIVE", tickCounter, (tickCounter - lastExternalLoadTick), passiveGraceTicks)
        }

        // If there is declared intent but we're passive, log why scans/renewals may appear stalled.
        if (passive && (tickCounter - lastIntentLogTick) >= 100) {
            val hasIntent = providers.asSequence().flatMap { it.desiredChunks() }.iterator().hasNext()
            if (hasIntent) {
                lastIntentLogTick = tickCounter
                log.info("[DRIVER] suppressing proactive loads reason=EXTERNAL_LOADS externalAge={} graceTicks={}", (tickCounter - lastExternalLoadTick), passiveGraceTicks)
            }
        }

        tick(s)
    }

    private fun tick(server: MinecraftServer) {
        // existing scheduling logic
    }
}