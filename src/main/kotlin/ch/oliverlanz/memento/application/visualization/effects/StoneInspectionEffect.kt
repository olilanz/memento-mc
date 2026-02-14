package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.infrastructure.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceAreaSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceOutlineSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.domain.stones.StoneView

class StoneInspectionEffect(stone: StoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        profile.lifetime = GameHours(0.15)  // Inspection: short-lived and high signal.

        profile.stoneChunk.emissionsPerGameHour = 220
        profile.stoneChunk.verticalSpan = 0..2

        profile.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        profile.influenceArea.emissionsPerGameHour = 500
        profile.influenceArea.verticalSpan = 0..1
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.35)
        profile.influenceArea.dominanceMode = EffectProfile.DominanceMode.COLOR_BY_DOMINANT
        profile.influenceArea.system = endRodSystem(5, 0.25, 0.08, 0.25, 0.01, 1.0)
        profile.influenceArea.dominantLoreSystem = happyVillagerSystem(5, 0.25, 0.08, 0.25, 0.01, 1.0)
        profile.influenceArea.dominantWitherSystem = soulFireFlameSystem(5, 0.25, 0.08, 0.25, 0.01, 1.0)

        profile.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone, thicknessBlocks = 4)
        profile.influenceOutline.emissionsPerGameHour = 260
        profile.influenceOutline.verticalSpan = 0..1
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.50)
        profile.influenceOutline.dominanceMode = EffectProfile.DominanceMode.COLOR_BY_DOMINANT
        profile.influenceOutline.system = witchSystem(5, 0.20, 0.05, 0.20, 0.01, 1.0)
        profile.influenceOutline.dominantLoreSystem = happyVillagerSystem(5, 0.20, 0.05, 0.20, 0.01, 1.0)
        profile.influenceOutline.dominantWitherSystem = soulFireFlameSystem(5, 0.20, 0.05, 0.20, 0.01, 1.0)
    }
}
