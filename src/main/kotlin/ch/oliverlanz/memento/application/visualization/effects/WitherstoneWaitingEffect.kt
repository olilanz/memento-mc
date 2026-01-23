package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.samplers.StoneBlockSampler
import ch.oliverlanz.memento.application.visualization.samplers.SingleChunkSurfaceSampler
import ch.oliverlanz.memento.domain.stones.WitherstoneView

class WitherstoneWaitingEffect(stone: WitherstoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        profile.lifetime = null             // Waiting: infinite until externally terminated.
        profile.surfaceVerticalSpan = 0..9  // 10 blocks high

        profile.surfaceSampler = SingleChunkSurfaceSampler(stone)
        profile.surfaceEmissionsPerGameHour = 500

        profile.surfaceSystem = ParticleSystemPrototype(
            particle = net.minecraft.particle.ParticleTypes.END_ROD,
            count = 7,
            spreadX = 1.50,
            spreadY = 1.50,
            spreadZ = 1.50,
            speed = 0.02,
            baseYOffset = 1.0
        )
    }
}