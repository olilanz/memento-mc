package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.samplers.SingleChunkSurfaceSampler
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.particle.ParticleTypes

class WitherstoneWaitingEffect(private val stone: WitherstoneView) : EffectBase() {

    override fun onConfigure(profile: EffectProfile) {
        // Domain-managed: persists until removed externally.
        profile.lifetimeGameHours = null

        profile.surfaceSampler = SingleChunkSurfaceSampler(stone)

        // Waiting is a subtle presence (ambient).
        profile.surfacePresenceSystem = StoneParticleSystems.surfacePresenceFor(ParticleTypes.ASH)
        profile.surfacePresenceRatePerGameHour = 100.0 // 0.10 per server tick

        profile.yOffsetSpread = 0..0
    }
}
