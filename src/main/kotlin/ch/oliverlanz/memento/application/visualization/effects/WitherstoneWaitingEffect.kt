package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.samplers.InfluenceAreaSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceOutlineSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.domain.stones.WitherstoneView

class WitherstoneWaitingEffect(stone: WitherstoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        profile.lifetime = null             // Waiting: infinite until externally terminated.
        profile.stoneChunk.verticalSpan = 0..5
        profile.stoneChunk.emissionsPerGameHour = 480
        profile.stoneChunk.system = endRodSystem(12, 0.45, 0.15, 0.45, 0.01, 1.0)

        profile.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        profile.influenceArea.verticalSpan = 0..6
        profile.influenceArea.emissionsPerGameHour = 840
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.28)
        profile.influenceArea.dominanceMode = EffectProfile.DominanceMode.WITHER_ONLY
        profile.influenceArea.system = soulFireFlameSystem(14, 1.65, 1.65, 1.65, 0.02, 1.0)
        profile.influenceArea.dominantWitherSystem = soulFireFlameSystem(14, 1.65, 1.65, 1.65, 0.02, 1.0)

        profile.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone, thicknessBlocks = 6)
        profile.influenceOutline.verticalSpan = 0..4
        profile.influenceOutline.emissionsPerGameHour = 360
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.35)
        profile.influenceOutline.dominanceMode = EffectProfile.DominanceMode.WITHER_ONLY
        profile.influenceOutline.system = campfireSmokeSystem(10, 1.05, 1.35, 1.05, 0.01, 1.0)
        profile.influenceOutline.dominantWitherSystem = campfireSmokeSystem(10, 1.05, 1.35, 1.05, 0.01, 1.0)
    }
}
