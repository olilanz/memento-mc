package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.samplers.StoneBlockSampler
import ch.oliverlanz.memento.application.visualization.samplers.SingleChunkSurfaceSampler
import ch.oliverlanz.memento.domain.stones.WitherstoneView

class WitherstoneWaitingEffect(private val stone: WitherstoneView) : EffectBase() {

    override fun onConfigure(profile: EffectProfile) {
        // Waiting: no end time (per session), low intensity, tall surface projection.
        profile.lifetime = null
        profile.surfaceVerticalSpan = 0..7 // 8 blocks high (y+1..y+8)

        profile.anchorSampler = StoneBlockSampler(stone)
        profile.anchorEmissionsPerGameHour = 80

        profile.surfaceSampler = SingleChunkSurfaceSampler(stone)
        profile.surfaceEmissionsPerGameHour = 40
    }
}
