package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.samplers.InfluenceAreaSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceOutlineSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.domain.stones.WitherstoneView

class WitherstoneWaitingEffect(stone: WitherstoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        profile.lifetime = null             // Waiting: infinite until externally terminated.
        profile.stoneChunk.verticalSpan = 0..4
        profile.stoneChunk.emissionsPerGameHour = 240

        profile.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        profile.influenceArea.verticalSpan = 0..5
        profile.influenceArea.emissionsPerGameHour = 420
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.28)
        profile.influenceArea.dominanceMode = EffectProfile.DominanceMode.WITHER_ONLY
        profile.influenceArea.system = soulFireFlameSystem(7, 1.10, 1.10, 1.10, 0.02, 1.0)
        profile.influenceArea.dominantWitherSystem = soulFireFlameSystem(7, 1.10, 1.10, 1.10, 0.02, 1.0)

        profile.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone, thicknessBlocks = 6)
        profile.influenceOutline.verticalSpan = 0..3
        profile.influenceOutline.emissionsPerGameHour = 180
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.35)
        profile.influenceOutline.dominanceMode = EffectProfile.DominanceMode.WITHER_ONLY
        profile.influenceOutline.system = campfireSmokeSystem(5, 0.70, 0.90, 0.70, 0.01, 1.0)
        profile.influenceOutline.dominantWitherSystem = campfireSmokeSystem(5, 0.70, 0.90, 0.70, 0.01, 1.0)
    }
}
