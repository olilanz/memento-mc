package ch.oliverlanz.memento.application.visualization

import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.application.time.GameClock
import net.minecraft.server.world.ServerWorld

/**
 * Base type for long-lived visualization projections.
 *
 * Visualization effects are:
 * - created from domain / operator events
 * - purely in-memory (not persisted)
 * - ticked by the VisualizationEngine
 *
 * The effect decides:
 * - where to sample (directly or via helpers)
 * - what to emit
 * - when to terminate (by returning false from tick)
 */
abstract class VisualAreaEffect(
    val stone: StoneView,
) {
    /**
     * Perform one clock-driven visual update.
     *
     * The clock is derived from the overworld by the application-level GameTimeTracker.
     * Server ticks are transport only and must not leak into visualization behavior.
     *
     * @param world resolved world for the stone's dimension
     * @param clock current game-time snapshot (medium frequency)
     * @return true to keep the effect alive, false to terminate and remove it
     */
    abstract fun tick(world: ServerWorld, clock: GameClock): Boolean
}
