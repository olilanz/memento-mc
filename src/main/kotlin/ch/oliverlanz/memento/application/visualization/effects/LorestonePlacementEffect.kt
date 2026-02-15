package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.effectplans.PulsatingEffectPlan
import ch.oliverlanz.memento.application.visualization.effectplans.RateEffectPlan
import ch.oliverlanz.memento.application.visualization.effectplans.RunningEffectPlan
import ch.oliverlanz.memento.infrastructure.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceAreaSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceOutlineSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.domain.stones.LorestoneView

/**
 * Lorestone placement effect: immediate, unambiguous confirmation of protective intent.
 *
 * Lane intent:
 * - Stone block/chunk: steady rate signal to pin local origin.
 * - Influence area: pulsating expansion pulses for footprint readability.
 * - Influence outline: running wrapped perimeter cursor with spacing trail illusion.
 */
class LorestonePlacementEffect(stone: LorestoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        // Global
        profile.lifetime = GameHours(0.25)  // Placement: short-lived, intense, unmistakable.

        // Stone block lane
        // use dfaults

        // Stone chunk lane
        // use dfaults

        // Influence area lane
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.30)

        // Influence outline lane
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.45)
    }
}
