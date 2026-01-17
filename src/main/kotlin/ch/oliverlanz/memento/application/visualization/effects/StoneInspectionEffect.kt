package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.SingleChunkSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.StoneBlockSampler
import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.particle.ParticleTypes

class StoneInspectionEffect(stone: StoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        profile.lifetime = GameHours(0.15)  // Placement: short-lived, intense, unmistakable.

        profile.surfaceSampler = SingleChunkSurfaceSampler(stone)
        profile.surfaceEmissionsPerGameHour = 900
    }
}