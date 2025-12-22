package ch.oliverlanz.memento.application.stone

import ch.oliverlanz.memento.application.MementoAnchors
import ch.oliverlanz.memento.application.land.ChunkGroupForgetting
import ch.oliverlanz.memento.infrastructure.MementoDebug
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

/**
 * Facade coordinating the lifecycle of memento stones
 * and their derived effects on land.
 *
 * This preserves the legacy integration contract.
 */
object MementoStoneLifecycle {

    fun attachServer(server: MinecraftServer) {
        rebuildMarkedGroups(server)
        MementoDebug.info(server, "MementoStoneLifecycle attached")
    }

    fun detachServer(server: MinecraftServer) {
        MementoDebug.info(server, "MementoStoneLifecycle detached")
    }

    fun rebuildMarkedGroups(server: MinecraftServer) {
        ChunkGroupForgetting.rebuildFromAnchors(server)
    }

    fun ageAnchorsOnce(server: MinecraftServer): Int =
        MementoAnchors.ageOnce(server)

    fun sweep(server: MinecraftServer) {
        for (world in server.worlds) {
            if (world is ServerWorld) {
                ChunkGroupForgetting.refresh(world, server)
            }
        }
    }

    fun tick(server: MinecraftServer) {
        // kept for compatibility
    }

    fun onChunkUnloaded(
        server: MinecraftServer,
        world: ServerWorld,
        pos: ChunkPos
    ) {
        // unload is observed via refresh()
    }

    fun onChunkRenewalObserved(
        server: MinecraftServer,
        dimension: RegistryKey<World>,
        pos: ChunkPos
    ) {
        ChunkGroupForgetting.onChunkRenewed(server, dimension, pos)
    }

    fun shouldForgetNow(
        dimension: RegistryKey<World>,
        pos: ChunkPos
    ): Boolean =
        ChunkGroupForgetting.isMarked(dimension, pos)
}
