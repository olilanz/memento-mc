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
 * - Each lane declares what, where, and an emission rate.
 * - No tick-based concepts.
 * - Finite effects are bounded by lifetime (when set).
 * - Emission pacing is always expressed as emissions per game hour.
 */
class EffectProfile {
    data class LaneProfile(
        var sampler: StoneSampler? = null,
        var verticalSpan: IntRange = 0..0,
        var emissionsPerGameHour: Int = 0,
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
