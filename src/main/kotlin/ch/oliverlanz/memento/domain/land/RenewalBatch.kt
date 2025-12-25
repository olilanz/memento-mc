package ch.oliverlanz.memento.domain.land

import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

/**
 * Derived (non-persisted) group of chunks scheduled for regeneration.
 *
 * This is a pure domain object:
 * - no Fabric callbacks
 * - no ticking
 * - no persistence
 *
 * Lifecycle is represented by [state], but transitions are still
 * orchestrated by application services (for now).
 */
data class RenewalBatch(
    val anchorName: String,
    val dimension: RegistryKey<World>,
    val stonePos: BlockPos,
    val radiusChunks: Int,
    val chunks: List<ChunkPos>,
    var state: RenewalBatchState = RenewalBatchState.MARKED
)
