package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.effects.EffectProfile.LanePlan
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceAreaSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.InfluenceOutlineSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.domain.stones.WitherstoneView

/**
 * Witherstone waiting effect: persistent, operational signal while maturity is pending.
 *
 * Lane intent:
 * - Stone block/chunk: steady rate signal to keep anchor present during long waits.
 * - Influence area: pulsating field to indicate active influence pressure.
 * - Influence outline: running wrapped perimeter cursor for directional perimeter motion.
 *
 * Domain semantic: waiting phase is wither-only across influence lanes.
 */
class WitherstoneWaitingEffect(stone: WitherstoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        // Global
        profile.lifetime = null             // Waiting: infinite until externally terminated.

        // Stone block lane
        // no override; base defaults

        // Stone chunk lane
        profile.stoneChunk.verticalSpan = 0..5
        profile.stoneChunk.plan = LanePlan.Rate(emissionsPerGameHour = 1920)

        // Influence area lane
        profile.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        profile.influenceArea.verticalSpan = 0..6
        profile.influenceArea.plan = LanePlan.Pulsating(
            pulseEveryGameHours = 0.03,
            emissionsPerPulse = 101,
        )
        profile.influenceArea.materialization = SamplerMaterializationConfig(detail = 0.28)
        profile.influenceArea.dominantLoreSystem = null

        // Influence outline lane
        profile.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone, thicknessBlocks = 6)
        profile.influenceOutline.verticalSpan = 0..4
        profile.influenceOutline.plan = LanePlan.Running(
            speedChunksPerGameHour = 72.0,
            maxCursorSpacingBlocks = 10,
        )
        profile.influenceOutline.materialization = SamplerMaterializationConfig(detail = 0.35)
        profile.influenceOutline.dominantLoreSystem = null
    }
}
