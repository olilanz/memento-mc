package ch.oliverlanz.memento.domain.renewal

import net.minecraft.util.math.ChunkPos

/**
 * Adapter hooks for RenewalTracker.
 *
 * These functions exist solely to preserve the existing call surface
 * used by Memento.kt while routing events into the new, observational
 * RenewalTracker.
 *
 * IMPORTANT:
 * - No authority
 * - No decisions
 * - No persistence
 * - Observability only
 */

/**
 * Observes time progression.
 * Legacy logic remains authoritative elsewhere.
 */
fun advanceTime(tick: Long) {
    RenewalTracker.advanceTime(tick)
}

/**
 * Observes chunk load events.
 * Dimension is intentionally omitted at this stage.
 */
fun onChunkLoaded(chunk: ChunkPos) {
    RenewalTracker.onChunkLoaded(chunk)
}

/**
 * Observes chunk unload events.
 * Dimension is intentionally omitted at this stage.
 */
fun onChunkUnloaded(chunk: ChunkPos) {
    RenewalTracker.onChunkUnloaded(chunk)
}
