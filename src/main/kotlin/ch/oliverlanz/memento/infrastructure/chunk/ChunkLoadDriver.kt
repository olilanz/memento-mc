
package ch.oliverlanz.memento.infrastructure.chunk

import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk

class ChunkLoadDriver(
    val activeLoadIntervalTicks: Int,
    val passiveGraceTicks: Int,
    val ticketMaxAgeTicks: Int
) {
    private val providers = mutableListOf<ChunkLoadProvider>()
    private val listeners = mutableListOf<ChunkAvailabilityListener>()
    private var server: MinecraftServer? = null

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
        listeners.forEach { it.onChunkLoaded(world, chunk) }
    }

    fun onEngineChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
        listeners.forEach { it.onChunkUnloaded(world, pos) }
    }

    fun tick() {
        val s = server ?: return
        tick(s)
    }

    private fun tick(server: MinecraftServer) {
        // existing scheduling logic
    }
}
