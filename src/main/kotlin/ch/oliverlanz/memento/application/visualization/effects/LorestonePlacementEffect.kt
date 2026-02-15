package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.infrastructure.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceAreaSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceOutlineSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.domain.stones.LorestoneView

class LorestonePlacementEffect(stone: LorestoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        // Global
        profile.lifetime = GameHours(0.25)  // Placement: short-lived, intense, unmistakable.

        // Stone block lane
        // no override; base defaults

        // Stone chunk lane
        profile.stoneChunk.verticalSpan = 0..2
        profile.stoneChunk.emissionsPerGameHour = 2080

        // Influence area lane
        profile.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        profile.influenceArea.emissionsPerGameHour = 3360
        profile.influenceArea.verticalSpan = 0..2
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.30)

        // Influence outline lane
        profile.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone, thicknessBlocks = 4)
        profile.influenceOutline.emissionsPerGameHour = 1920
        profile.influenceOutline.verticalSpan = 0..2
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.45)
    }
}
