package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.StoneSampler

/**
 * Declarative configuration for a visual effect.
 *
 * Locked semantics:
 * - One lifecycle per effect instance (expressed in game time).
 * - Two emitters: anchor and surface.
 * - Each emitter declares what, where, and total emissions over the effect lifetime.
 * - No tick-based concepts.
 * - Finite effects declare totals over a finite lifetime.
 * - Infinite effects declare emissions per game hour.
 */
class EffectProfile {

    /** Optional finite lifetime. Null means infinite / external termination. */
    var lifetime: GameHours? = null

    /** Anchor vertical span (whole blocks) applied on top of the anchor system's baseYOffset. */
    var anchorVerticalSpan: IntRange = 0..0

    /** Surface vertical span (whole blocks) applied on top of the surface system's baseYOffset. */
    var surfaceVerticalSpan: IntRange = 0..0

    /** Anchor emitter (identity). */
    var anchorSampler: StoneSampler? = null
    var anchorSystem: EffectBase.ParticleSystemPrototype? = null

    /** Total emissions over the finite lifetime. Ignored when lifetime is null. */
    var anchorTotalEmissions: Int = 0

    /** Emissions per game hour when lifetime is null (infinite effects). */
    var anchorEmissionsPerGameHour: Int = 0

    /** Surface emitter (area presence). */
    var surfaceSampler: StoneSampler? = null
    var surfaceSystem: EffectBase.ParticleSystemPrototype? = null

    /** Total emissions over the finite lifetime. Ignored when lifetime is null. */
    var surfaceTotalEmissions: Int = 0

    /** Emissions per game hour when lifetime is null (infinite effects). */
    var surfaceEmissionsPerGameHour: Int = 0
}
