package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.effectplans.PulsatingEffectPlan
import ch.oliverlanz.memento.application.visualization.effectplans.RateEffectPlan
import ch.oliverlanz.memento.application.visualization.effectplans.RunningEffectPlan
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceAreaSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceOutlineSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.domain.stones.WitherstoneView

/**
 * Witherstone waiting effect: persistent, operational signal while maturity is pending.
 *
 * Lane intent:
 * - Stone block: steady rate signal to keep anchor present during long waits.
 * - Stone chunk: disabled to avoid competing with area/outline during waiting.
 * - Influence area: pulsating field to indicate active influence pressure.
 * - Influence outline: running wrapped perimeter cursor for directional perimeter motion.
 *
 * Domain semantic: waiting phase is wither-only across influence lanes.
 */
class WitherstoneWaitingEffect(stone: WitherstoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        // Global
        profile.lifetime = null             // Waiting: infinite until externally terminated.

        // Stone block lane
        // use dfaults

        // Stone chunk lane
        profile.stoneChunk.sampler = null

        // Influence area lane
        profile.influenceArea.verticalSpan = 1..5
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.30)
        profile.influenceArea.dominantLoreSystem = null

        // Influence outline lane
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.45)
        profile.influenceOutline.dominantLoreSystem = null
    }
}
