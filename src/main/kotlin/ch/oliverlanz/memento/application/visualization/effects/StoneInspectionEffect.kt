package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.infrastructure.time.GameHours
import ch.oliverlanz.memento.application.visualization.effects.EffectProfile.LanePlan
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
        profile.lifetime = GameHours(0.15)  // Inspection: short-lived and high signal.

        // Stone block lane
        // no override; base defaults

        // Stone chunk lane
        profile.stoneChunk.plan = LanePlan.Rate(emissionsPerGameHour = 1760)
        profile.stoneChunk.verticalSpan = 0..3

        // Influence area lane
        profile.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        profile.influenceArea.plan = LanePlan.Pulsating(
            pulseEveryGameHours = 0.02,
            emissionsPerPulse = 80,
        )
        profile.influenceArea.verticalSpan = 0..2
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.35)

        // Influence outline lane
        profile.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone, thicknessBlocks = 4)
        profile.influenceOutline.plan = LanePlan.Running(
            speedChunksPerGameHour = 120.0,
            maxCursorSpacingBlocks = 8,
        )
        profile.influenceOutline.verticalSpan = 0..2
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.50)
    }
}
