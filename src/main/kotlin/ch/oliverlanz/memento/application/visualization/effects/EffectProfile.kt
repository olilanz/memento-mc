package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.samplers.StoneSampler

/**
 * Declarative configuration for a visual effect.
 *
 * Semantics live here; mechanics are owned by EffectBase.
 *
 * Notes on units:
 * - lifetime is expressed in GAME HOURS (Minecraft: 1 hour = 1000 game ticks).
 * - emission rates are expressed per GAME HOUR and are integrated using GameClock.deltaTicks.
 */
class EffectProfile {

    /** Optional finite lifetime expressed in GAME HOURS. Null means domain-managed / infinite. */
    var lifetimeGameHours: Double? = null

    /**
     * Optional burst window at the start of the effect.
     * When elapsedGameHours < burstDurationGameHours, rates are multiplied by burstMultiplier.
     *
     * Defaults are neutral (no burst).
     */
    var burstDurationGameHours: Double = 0.0
    var burstMultiplier: Double = 1.0

    /** Sampler for the anchor projection. Null disables anchor projection. */
    var anchorSampler: StoneSampler? = null

    /** Particle system prototype used for the anchor projection. Null disables anchor projection. */
    var anchorSystem: EffectBase.ParticleSystemPrototype? = null

    /** Anchor emission rate expressed as occurrences per GAME HOUR. */
    var anchorRatePerGameHour: Double = 0.0

    /** Sampler for surface projections. Null disables surface projections. */
    var surfaceSampler: StoneSampler? = null

    /** Particle system used for deterministic surface dust. Null disables dust plan. */
    var surfaceDustSystem: EffectBase.ParticleSystemPrototype? = null

    /** Deterministic surface dust emission rate expressed as occurrences per GAME HOUR. */
    var surfaceDustRatePerGameHour: Double = 0.0

    /** Size of deterministic surface subset for the dust plan. */
    var surfaceDustSubsetSize: Int = 32

    /** Seed for deterministic subset selection. */
    var surfaceDustSubsetSeed: Long = 0L

    /** Particle system used for local surface presence. Null disables presence plan. */
    var surfacePresenceSystem: EffectBase.ParticleSystemPrototype? = null

    /** Local surface presence emission rate expressed as occurrences per GAME HOUR. */
    var surfacePresenceRatePerGameHour: Double = 0.0

    /**
     * Inclusive vertical spread (whole blocks) applied on top of the system's baseYOffset.
     * Example: 1..4 means +1, +2, +3 or +4 blocks.
     */
    var yOffsetSpread: IntRange = 0..0
}
