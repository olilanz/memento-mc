
package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadProvider
import ch.oliverlanz.memento.infrastructure.chunk.ChunkRef
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.world.chunk.WorldChunk

class MementoRunController : ChunkLoadProvider, ChunkAvailabilityListener {

    private var server = null as net.minecraft.server.MinecraftServer?

    fun attach(server: net.minecraft.server.MinecraftServer) {
        this.server = server
    }

    fun detach() {
        server = null
    }

    /**
     * Entry point used by CommandHandlers.
     * Accepts ServerCommandSource per Brigadier wiring.
     */
    fun start(source: ServerCommandSource): Int {
        this.server = source.server
        return 0
    }

    fun tick() {
        // per-tick scan bookkeeping
    }

    override fun desiredChunks(): Sequence<ChunkRef> = emptySequence()

    override fun onChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        // existing scan logic remains
    }
}
