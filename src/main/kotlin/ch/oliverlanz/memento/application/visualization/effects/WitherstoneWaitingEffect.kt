package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.StoneBlockSampler
import ch.oliverlanz.memento.application.visualization.samplers.SingleChunkSurfaceSampler
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.particle.ParticleTypes

class WitherstoneWaitingEffect(private val stone: WitherstoneView) : EffectBase() {

    override fun onConfigure(profile: EffectProfile) {
        // Waiting: no end time (per session), low intensity, tall surface projection.
        profile.lifetime = null
        profile.anchorVerticalSpan = 0..0
        profile.surfaceVerticalSpan = 0..7 // 8 blocks high (y+1..y+8)

        profile.anchorSampler = StoneBlockSampler(stone)
        // Anchors are always green and sparkly, so stone location is unambiguous.
        profile.anchorSystem = EffectBase.ParticleSystemPrototype(
            particle = ParticleTypes.HAPPY_VILLAGER,
            count = 14,
            spreadX = 0.18,
            spreadY = 0.18,
            spreadZ = 0.18,
            speed = 0.01,
            baseYOffset = 1.2,
        )
        profile.anchorEmissionsPerGameHour = 80

        profile.surfaceSampler = SingleChunkSurfaceSampler(stone, subsetSize = 32)
        profile.surfaceSystem = EffectBase.ParticleSystemPrototype(
            particle = ParticleTypes.ASH,
            count = 6,
            spreadX = 0.30,
            spreadY = 0.10,
            spreadZ = 0.30,
            speed = 0.01,
            baseYOffset = 1.0,
        )
        profile.surfaceEmissionsPerGameHour = 40
    }
}
