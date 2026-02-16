package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.effectplans.RateEffectPlan
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceAreaSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceOutlineSurfaceSampler
import ch.oliverlanz.memento.domain.stones.WitherstoneView

/**
 * Witherstone waiting effect: persistent, operational signal while maturity is pending.
 *
 * Lane intent:
 * - Anchor point: steady rate signal to keep anchor present during long waits.
 * - Anchor chunk: disabled to avoid competing with area/outline during waiting.
 * - Influence area: pulsating field to indicate active influence pressure.
 * - Influence outline: running wrapped perimeter cursor for directional perimeter motion.
 *
 * Domain semantic: waiting phase is wither-only across influence lanes.
 */
class WitherstoneWaitingEffect(stone: WitherstoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        // Global
        profile.lifetime = null             // Waiting: infinite until externally terminated.

        // Anchor point lane
        // use defaults

        // Anchor chunk lane
        profile.anchorChunk.sampler = null

        // Influence area lane
        profile.influenceArea.verticalSpan = 0.0..15.0
        profile.influenceArea.planFactory = { RateEffectPlan(selectionDensityPerGameHour = 0.8) }
        profile.influenceArea.dominantLoreSystem = null

        // Influence outline lane
        profile.influenceOutline.dominantLoreSystem = null
    }
}
