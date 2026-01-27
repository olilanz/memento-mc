package ch.oliverlanz.memento.infrastructure.chunk

import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

/**
 * A single chunk-load request.
 *
 * This is intentionally dumb data.
 * - The driver decides when to act (politeness gates).
 * - Providers decide what to request.
 */
data class ChunkLoadRequest(
    /**
     * A stable label for logging/inspection.
     * For renewal this is the batch name; for scanning it may be a region label.
     */
    val label: String,
    val dimension: RegistryKey<World>,
    val pos: ChunkPos,
)
