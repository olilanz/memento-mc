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
        profile.lifetime = GameHours(0.15)  // Inspection: short-lived and high signal.

        // Stone block lane
        profile.stoneBlock.verticalSpan = 0..24
        profile.stoneBlock.plan = RateEffectPlan(emissionsPerGameHour = 64)
        profile.stoneBlock.dominantLoreSystem = lorestoneParticles()
        profile.stoneBlock.dominantWitherSystem = lorestoneParticles()

        // Stone chunk lane
        profile.stoneChunk.plan = RateEffectPlan(emissionsPerGameHour = 1760)
        profile.stoneChunk.verticalSpan = 2..2
        profile.stoneChunk.dominantLoreSystem = lorestoneParticles()
        profile.stoneChunk.dominantWitherSystem = witherstoneParticles()

        // Influence area lane
        profile.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        profile.influenceArea.plan = PulsatingEffectPlan(
            pulseEveryGameHours = 0.04,
            emissionsPerPulse = 80,
        )
        profile.influenceArea.verticalSpan = 1..1
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.35)

        // Influence outline lane
        profile.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone, thicknessBlocks = 4)
        profile.influenceOutline.plan = RunningEffectPlan(
            speedChunksPerGameHour = 120.0,
            maxCursorSpacingBlocks = 10,
        )
        profile.influenceOutline.verticalSpan = 1..1
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.50)
    }
}
