package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.effectplans.PulsatingEffectPlan
import ch.oliverlanz.memento.application.visualization.effectplans.RateEffectPlan
import ch.oliverlanz.memento.application.visualization.effectplans.RunningEffectPlan
import ch.oliverlanz.memento.infrastructure.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceAreaSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceOutlineSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.domain.stones.WitherstoneView

/**
 * Witherstone placement effect: immediate, unambiguous confirmation of newly asserted intent.
 *
 * Lane intent:
 * - Stone block/chunk: steady rate signal to pin local origin.
 * - Influence area: pulsating expansion pulses for footprint readability.
 * - Influence outline: running wrapped perimeter cursor with spacing trail illusion.
 */
class WitherstonePlacementEffect(stone: WitherstoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        // Global
        profile.lifetime = GameHours(0.25)  // Placement: short-lived, intense, unmistakable.

        // Stone block lane
        // no override; base defaults

        // Stone chunk lane
        profile.stoneChunk.verticalSpan = 0..2
        profile.stoneChunk.plan = RateEffectPlan(emissionsPerGameHour = 2080)

        // Influence area lane
        profile.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        profile.influenceArea.plan = PulsatingEffectPlan(
            pulseEveryGameHours = 0.025,
            emissionsPerPulse = 84,
        )
        profile.influenceArea.verticalSpan = 0..2
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.30)

        // Influence outline lane
        profile.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone, thicknessBlocks = 4)
        profile.influenceOutline.plan = RunningEffectPlan(
            speedChunksPerGameHour = 96.0,
            maxCursorSpacingBlocks = 8,
        )
        profile.influenceOutline.verticalSpan = 0..2
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.45)
    }
}
