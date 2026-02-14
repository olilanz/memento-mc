package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.infrastructure.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceAreaSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceOutlineSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.domain.stones.StoneView

class StoneInspectionEffect(stone: StoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        profile.lifetime = GameHours(0.15)  // Inspection: short-lived and high signal.

        profile.stoneChunk.emissionsPerGameHour = 440
        profile.stoneChunk.verticalSpan = 0..3
        profile.stoneChunk.system = endRodSystem(12, 0.45, 0.15, 0.45, 0.01, 1.0)

        profile.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        profile.influenceArea.emissionsPerGameHour = 1000
        profile.influenceArea.verticalSpan = 0..2
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.35)
        profile.influenceArea.dominanceMode = EffectProfile.DominanceMode.COLOR_BY_DOMINANT
        profile.influenceArea.system = endRodSystem(10, 0.375, 0.12, 0.375, 0.01, 1.0)
        profile.influenceArea.dominantLoreSystem = happyVillagerSystem(10, 0.375, 0.12, 0.375, 0.01, 1.0)
        profile.influenceArea.dominantWitherSystem = soulFireFlameSystem(10, 0.375, 0.12, 0.375, 0.01, 1.0)

        profile.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone, thicknessBlocks = 4)
        profile.influenceOutline.emissionsPerGameHour = 520
        profile.influenceOutline.verticalSpan = 0..2
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.50)
        profile.influenceOutline.dominanceMode = EffectProfile.DominanceMode.COLOR_BY_DOMINANT
        profile.influenceOutline.system = witchSystem(10, 0.30, 0.075, 0.30, 0.01, 1.0)
        profile.influenceOutline.dominantLoreSystem = happyVillagerSystem(10, 0.30, 0.075, 0.30, 0.01, 1.0)
        profile.influenceOutline.dominantWitherSystem = soulFireFlameSystem(10, 0.30, 0.075, 0.30, 0.01, 1.0)
    }
}
