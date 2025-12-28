package ch.oliverlanz.memento.domain.renewal

import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

/**
 * Adapter hooks used by Memento.kt.
 * Keeps the call surface small and shadow-only.
 */

fun advanceTime(tick: Long) {
    // For now: time is observed, but we primarily key off explicit triggers.
    // Still useful to keep the hook stable.
    // (If you later want: periodic sanity logs or drift detection.)
}

fun onChunkUnloaded(
    server: MinecraftServer,
    dimension: RegistryKey<World>,
    chunk: ChunkPos,
    gameTime: Long,
) {
    RenewalTracker.onChunkUnloadedObserved(server, dimension, chunk, gameTime)
}

fun onChunkLoaded(
    server: MinecraftServer,
    dimension: RegistryKey<World>,
    chunk: ChunkPos,
    gameTime: Long,
) {
    RenewalTracker.onChunkLoadedObserved(server, dimension, chunk, gameTime)
}
