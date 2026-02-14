package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.infrastructure.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceAreaSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceOutlineSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.domain.stones.WitherstoneView

class WitherstonePlacementEffect(stone: WitherstoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        profile.lifetime = GameHours(0.25)  // Placement: short-lived, intense, unmistakable.
        profile.stoneChunk.verticalSpan = 0..1
        profile.stoneChunk.emissionsPerGameHour = 260

        profile.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        profile.influenceArea.emissionsPerGameHour = 420
        profile.influenceArea.verticalSpan = 0..1
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.30)
        profile.influenceArea.dominanceMode = EffectProfile.DominanceMode.COLOR_BY_DOMINANT
        profile.influenceArea.system = endRodSystem(4, 0.20, 0.08, 0.20, 0.01, 1.0)
        profile.influenceArea.dominantLoreSystem = happyVillagerSystem(4, 0.20, 0.08, 0.20, 0.01, 1.0)
        profile.influenceArea.dominantWitherSystem = soulFireFlameSystem(4, 0.20, 0.08, 0.20, 0.01, 1.0)

        profile.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone, thicknessBlocks = 4)
        profile.influenceOutline.emissionsPerGameHour = 240
        profile.influenceOutline.verticalSpan = 0..1
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.45)
        profile.influenceOutline.dominanceMode = EffectProfile.DominanceMode.COLOR_BY_DOMINANT
        profile.influenceOutline.system = witchSystem(4, 0.20, 0.06, 0.20, 0.01, 1.0)
        profile.influenceOutline.dominantLoreSystem = happyVillagerSystem(4, 0.20, 0.06, 0.20, 0.01, 1.0)
        profile.influenceOutline.dominantWitherSystem = soulFireFlameSystem(4, 0.20, 0.06, 0.20, 0.01, 1.0)
    }
}
