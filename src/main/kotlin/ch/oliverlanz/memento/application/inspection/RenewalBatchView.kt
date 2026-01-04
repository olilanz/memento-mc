package ch.oliverlanz.memento.application.inspection

import ch.oliverlanz.memento.domain.renewal.RenewalBatchState
import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

/**
 * Read-only projection of a renewal batch for inspection/reporting.
 *
 * This interface is intentionally minimal and stable:
 * - No mutation
 * - No lifecycle operations
 * - No persistence
 *
 * Both legacy and shadow implementations may expose this view without leaking internals.
 */
interface RenewalBatchView {
    val name: String
    val dimension: RegistryKey<World>
    val chunks: Set<ChunkPos>
    val state: RenewalBatchState
}
