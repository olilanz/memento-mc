package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.samplers.InfluenceAreaSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceOutlineSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.domain.stones.WitherstoneView

class WitherstoneWaitingEffect(stone: WitherstoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        // Global
        profile.lifetime = null             // Waiting: infinite until externally terminated.

        // Stone block lane
        // no override; base defaults

        // Stone chunk lane
        profile.stoneChunk.verticalSpan = 0..5
        profile.stoneChunk.emissionsPerGameHour = 1920

        // Influence area lane
        profile.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        profile.influenceArea.verticalSpan = 0..6
        profile.influenceArea.emissionsPerGameHour = 3360
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.28)
        profile.influenceArea.dominantLoreSystem = null

        // Influence outline lane
        profile.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone, thicknessBlocks = 6)
        profile.influenceOutline.verticalSpan = 0..4
        profile.influenceOutline.emissionsPerGameHour = 1440
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.35)
        profile.influenceOutline.dominantLoreSystem = null
    }
}
