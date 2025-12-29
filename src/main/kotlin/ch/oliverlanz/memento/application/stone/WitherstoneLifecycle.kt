package ch.oliverlanz.memento.application.stone

import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

/**
 * Legacy lifecycle (kept for reference).
 *
 * This branch treats the domain implementation as authoritative. If anything calls into this
 * object, that is a wiring bug and should be fixed.
 */
object WitherstoneLifecycle {

    @JvmStatic
    fun legacyInvoked(): Nothing = error("Legacy WitherstoneLifecycle invoked. New domain implementation should be authoritative.")

    @JvmStatic
    fun onChunkRenewalObserved(server: MinecraftServer, dimension: RegistryKey<World>, pos: ChunkPos) {
        legacyInvoked()
    }

    @JvmStatic
    fun shouldForget(server: MinecraftServer, dimension: RegistryKey<World>, pos: ChunkPos): Boolean {
        legacyInvoked()
    }
}
