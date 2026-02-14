package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.infrastructure.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceAreaSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceOutlineSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.domain.stones.StoneView

class StoneInspectionEffect(stone: StoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        // Global
        profile.lifetime = GameHours(0.15)  // Inspection: short-lived and high signal.

        // Stone block lane
        // no override; base defaults

        // Stone chunk lane
        profile.stoneChunk.emissionsPerGameHour = 1760
        profile.stoneChunk.verticalSpan = 0..3

        // Influence area lane
        profile.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        profile.influenceArea.emissionsPerGameHour = 4000
        profile.influenceArea.verticalSpan = 0..2
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.35)
        profile.influenceArea.dominanceMode = EffectProfile.DominanceMode.COLOR_BY_DOMINANT

        // Influence outline lane
        profile.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone, thicknessBlocks = 4)
        profile.influenceOutline.emissionsPerGameHour = 2080
        profile.influenceOutline.verticalSpan = 0..2
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.50)
        profile.influenceOutline.dominanceMode = EffectProfile.DominanceMode.COLOR_BY_DOMINANT
    }
}
