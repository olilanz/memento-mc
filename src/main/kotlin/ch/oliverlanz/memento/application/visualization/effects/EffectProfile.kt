package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.infrastructure.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.application.visualization.samplers.StoneSampler

/**
 * Declarative configuration for a visual effect.
 *
 * Locked semantics:
 * - One lifecycle per effect instance (expressed in game time).
 * - Four fixed lanes: stone block, stone chunk, influence area, influence outline.
 * - Each lane declares what, where, and a composable time-driven plan.
 * - No tick-based concepts.
 * - Finite effects are bounded by lifetime (when set).
 * - Emission pacing is always derived from game-time deltas.
 */
class EffectProfile {
    /**
     * Composable lane execution plans.
     *
     * Locked semantics:
     * - Plans are evaluated from [GameClock] delta game-time, never from server tick counts.
     * - Running motion speed is expressed in chunks per game hour.
     * - Running spacing is expressed in blocks and creates a pseudo-multi-cursor trail.
     */
    sealed interface LanePlan {
        /** Steady emissions paced by game-time throughput. */
        data class Rate(
            var emissionsPerGameHour: Int = 0,
        ) : LanePlan

        /** Discrete bursts at a fixed game-time interval. */
        data class Pulsating(
            var pulseEveryGameHours: Double = 0.10,
            var emissionsPerPulse: Int = 1,
        ) : LanePlan

        /**
         * One true cursor runs on a wrapped lane path.
         *
         * Trail emissions are produced every [maxCursorSpacingBlocks] while the
         * cursor advances, creating an illusion of multiple cursors.
         */
        data class Running(
            var speedChunksPerGameHour: Double = 0.0,
            var maxCursorSpacingBlocks: Int = 16,
        ) : LanePlan
    }

    data class LaneProfile(
        var sampler: StoneSampler? = null,
        var verticalSpan: IntRange = 0..0,
        var plan: LanePlan = LanePlan.Rate(),
        var materialization: SamplerMaterializationConfig = SamplerMaterializationConfig(),
        var dominantLoreSystem: EffectBase.ParticleSystemPrototype? = null,
        var dominantWitherSystem: EffectBase.ParticleSystemPrototype? = null,
    )

    /** Optional finite lifetime. Null means infinite / external termination. */
    var lifetime: GameHours? = null

    /** Stone identity lane (single block anchor semantics). */
    var stoneBlock: LaneProfile = LaneProfile()

    /** Chunk that contains the stone. */
    var stoneChunk: LaneProfile = LaneProfile()

    /** All chunks inside this stone's influence footprint. */
    var influenceArea: LaneProfile = LaneProfile()

    /** Block-space outline ring around influence boundary. */
    var influenceOutline: LaneProfile = LaneProfile()
}
