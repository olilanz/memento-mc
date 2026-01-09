package ch.oliverlanz.memento.application.visualization

import ch.oliverlanz.memento.domain.stones.StoneView
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
     * Perform one tick of visual work.
     *
     * @param world resolved world for the stone's dimension
     * @param worldTime current world time (ticks) for that world
     * @return true to keep the effect alive, false to terminate and remove it
     */
    abstract fun tick(world: ServerWorld, worldTime: Long): Boolean
}
