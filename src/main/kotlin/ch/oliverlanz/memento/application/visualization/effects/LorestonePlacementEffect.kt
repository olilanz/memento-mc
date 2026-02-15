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
        profile.stoneBlock.verticalSpan = 0..24
        profile.stoneBlock.plan = RateEffectPlan(emissionsPerGameHour = 64)
        profile.stoneBlock.dominantLoreSystem = lorestoneParticles()
        profile.stoneBlock.dominantWitherSystem = lorestoneParticles()

        // Stone chunk lane
        profile.stoneChunk.verticalSpan = 2..2
        profile.stoneChunk.plan = RateEffectPlan(emissionsPerGameHour = 2080)
        profile.stoneChunk.dominantLoreSystem = lorestoneParticles()
        profile.stoneChunk.dominantWitherSystem = witherstoneParticles()

        // Influence area lane
        profile.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        profile.influenceArea.plan = PulsatingEffectPlan(
            pulseEveryGameHours = 0.04,
            emissionsPerPulse = 84,
        )
        profile.influenceArea.verticalSpan = 1..1
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.30)

        // Influence outline lane
        profile.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone, thicknessBlocks = 4)
        profile.influenceOutline.plan = RunningEffectPlan(
            speedChunksPerGameHour = 96.0,
            maxCursorSpacingBlocks = 10,
        )
        profile.influenceOutline.verticalSpan = 1..1
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.45)
    }
}
