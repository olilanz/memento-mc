package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.samplers.SingleChunkSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.StoneBlockSampler
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.particle.ParticleTypes

class WitherstonePlacementEffect(private val stone: WitherstoneView) : EffectBase() {

    override fun onConfigure(profile: EffectProfile) {
        profile.lifetimeGameHours = 1.0 // 1000 game ticks

        // Subtle initial burst to make placements perceptible.
        profile.burstDurationGameHours = 0.05 // ~50 game ticks (~2.5s)
        profile.burstMultiplier = 2.0

        profile.anchorSampler = StoneBlockSampler(stone)
        profile.anchorSystem = StoneParticleSystems.anchorFor(stone)
        profile.anchorRatePerGameHour = 150.0 // 0.15 per server tick

        profile.surfaceSampler = SingleChunkSurfaceSampler(stone)
        profile.surfaceDustSystem = StoneParticleSystems.surfaceDustFor(stone)
        profile.surfaceDustRatePerGameHour = 150.0 // 0.15 per server tick
        profile.surfaceDustSubsetSize = 32
        profile.surfaceDustSubsetSeed = stone.position.asLong()

        profile.surfacePresenceSystem = StoneParticleSystems.surfacePresenceFor(ParticleTypes.ASH)
        profile.surfacePresenceRatePerGameHour = 50.0 // 0.05 per server tick

        profile.yOffsetSpread = 0..3
    }
}
