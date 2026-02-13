package ch.oliverlanz.memento.infrastructure.worldscan

import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.World

/**
 * Stage 1 of the /memento scan pipeline: discover runtime worlds.
 *
 * Design lock:
 * - We trust the server's loaded worlds as the authoritative set.
 * - Ordering is deterministic.
 */
class WorldDiscovery {

    fun discover(server: MinecraftServer): List<RegistryKey<World>> {
        return server.worlds
            .map { it.registryKey }
            .sortedBy { it.value.toString() }
    }
}
