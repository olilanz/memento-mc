package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.SingleChunkSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.StoneBlockSampler
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.particle.ParticleTypes

class WitherstonePlacementEffect(stone: WitherstoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        profile.lifetime = GameHours(0.25)  // Placement: short-lived, intense, unmistakable.
        profile.surfaceVerticalSpan = 0..1 // 2 blocks high

        profile.surfaceSampler = SingleChunkSurfaceSampler(stone)
        profile.surfaceEmissionsPerGameHour = 500
    }
}