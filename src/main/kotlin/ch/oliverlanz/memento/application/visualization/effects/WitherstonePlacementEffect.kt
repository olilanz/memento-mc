package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.infrastructure.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceAreaSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceOutlineSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.domain.stones.WitherstoneView

class WitherstonePlacementEffect(stone: WitherstoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        profile.lifetime = GameHours(0.25)  // Placement: short-lived, intense, unmistakable.
        profile.stoneChunk.verticalSpan = 0..2
        profile.stoneChunk.emissionsPerGameHour = 2080
        profile.stoneChunk.system = endRodSystem(12, 0.45, 0.15, 0.45, 0.01, 1.0)

        profile.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        profile.influenceArea.emissionsPerGameHour = 3360
        profile.influenceArea.verticalSpan = 0..2
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.30)
        profile.influenceArea.dominanceMode = EffectProfile.DominanceMode.COLOR_BY_DOMINANT
        profile.influenceArea.system = endRodSystem(8, 0.30, 0.12, 0.30, 0.01, 1.0)
        profile.influenceArea.dominantLoreSystem = happyVillagerSystem(8, 0.30, 0.12, 0.30, 0.01, 1.0)
        profile.influenceArea.dominantWitherSystem = soulFireFlameSystem(8, 0.30, 0.12, 0.30, 0.01, 1.0)

        profile.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone, thicknessBlocks = 4)
        profile.influenceOutline.emissionsPerGameHour = 1920
        profile.influenceOutline.verticalSpan = 0..2
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.45)
        profile.influenceOutline.dominanceMode = EffectProfile.DominanceMode.COLOR_BY_DOMINANT
        profile.influenceOutline.system = witchSystem(8, 0.30, 0.09, 0.30, 0.01, 1.0)
        profile.influenceOutline.dominantLoreSystem = happyVillagerSystem(8, 0.30, 0.09, 0.30, 0.01, 1.0)
        profile.influenceOutline.dominantWitherSystem = soulFireFlameSystem(8, 0.30, 0.09, 0.30, 0.01, 1.0)
    }
}
