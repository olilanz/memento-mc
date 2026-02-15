package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.effectplans.PulsatingEffectPlan
import ch.oliverlanz.memento.application.visualization.effectplans.RateEffectPlan
import ch.oliverlanz.memento.application.visualization.effectplans.RunningEffectPlan
import ch.oliverlanz.memento.infrastructure.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceAreaSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceOutlineSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.domain.stones.StoneView

/**
 * Inspection effect: short-lived, high-information reveal of stone scope.
 *
 * Lane intent:
 * - Stone block/chunk: steady anchor/context signal via rate plans.
 * - Influence area: pulsating field that reads as a breathing footprint.
 * - Influence outline: running wrapped perimeter cursor with spacing-based trail
 *   illusion so operators can read boundary motion direction.
 */
class StoneInspectionEffect(stone: StoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        // Global
        profile.lifetime = GameHours(0.30)  // Inspection: short-lived and high signal.

        // Stone block lane
        // use dfaults

        // Stone chunk lane
        // use dfaults

        // Influence area lane
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.35)

        // Influence outline lane
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.50)
    }
}
