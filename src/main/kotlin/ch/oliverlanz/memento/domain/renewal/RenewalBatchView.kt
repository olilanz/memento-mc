package ch.oliverlanz.memento.domain.renewal

import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

/**
 * Immutable, read-only view of a renewal batch.
 *
 * This is carried by lifecycle transition events so that application components
 * (visualization, inspection, logging) can observe execution scope without
 * depending on RenewalTracker internals.
 *
 * Identity: A renewal batch is uniquely identified by its owning stone name.
 */
interface RenewalBatchView {
    /** Owning stone name (batch identity). */
    val name: String

    val dimension: RegistryKey<World>

    /** Authoritative chunk scope for this batch. */
    val chunks: Set<ChunkPos>

    val state: RenewalBatchState
}
