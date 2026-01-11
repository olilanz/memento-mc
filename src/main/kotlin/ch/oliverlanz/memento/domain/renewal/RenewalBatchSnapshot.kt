package ch.oliverlanz.memento.domain.renewal

import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

/**
 * Immutable snapshot implementation of [RenewalBatchView].
 *
 * Used for event payloads and inspection snapshots to avoid leaking mutable
 * RenewalBatch references outside the RenewalTracker.
 */
data class RenewalBatchSnapshot(
    override val name: String,
    override val dimension: RegistryKey<World>,
    override val chunks: Set<ChunkPos>,
    override val state: RenewalBatchState,
) : RenewalBatchView
