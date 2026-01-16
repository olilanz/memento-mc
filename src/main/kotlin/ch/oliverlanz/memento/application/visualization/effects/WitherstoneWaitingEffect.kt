package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.samplers.StoneBlockSampler
import ch.oliverlanz.memento.application.visualization.samplers.SingleChunkSurfaceSampler
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.particle.ParticleTypes

class WitherstoneWaitingEffect(private val stone: WitherstoneView) : EffectBase() {

    override fun onConfigure(profile: EffectProfile) {
        profile.lifetimeGameHours = 0.25 // 250 game ticks

        // Inspection should read immediately.
        profile.burstDurationGameHours = 0.05 // ~50 game ticks (~2.5s)
        profile.burstMultiplier = 2.0

        profile.anchorSampler = StoneBlockSampler(stone)
        profile.anchorSystem = StoneParticleSystems.anchorFor(stone)
        profile.anchorRatePerGameHour = 200.0 // 0.20 per server tick

        profile.surfaceSampler = SingleChunkSurfaceSampler(stone)
        profile.surfaceDustSystem = StoneParticleSystems.surfaceDustFor(stone)
        profile.surfaceDustRatePerGameHour = 200.0 // 0.20 per server tick
        profile.surfaceDustSubsetSize = 32
        profile.surfaceDustSubsetSeed = stone.position.asLong()

        profile.surfacePresenceSystem =
                StoneParticleSystems.surfacePresenceFor(ParticleTypes.END_ROD)
        profile.surfacePresenceRatePerGameHour = 100.0 // 0.10 per server tick

        profile.yOffsetSpread = 2..8
    }
}
