package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.SingleChunkSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.StoneBlockSampler
import ch.oliverlanz.memento.domain.stones.LorestoneView
import net.minecraft.particle.ParticleTypes

class LorestonePlacementEffect(private val stone: LorestoneView) : EffectBase() {

    override fun onConfigure(profile: EffectProfile) {
        // Placement: short-lived, intense, unmistakable.
        profile.lifetime = GameHours(1.0)
        profile.anchorVerticalSpan = 0..0
        profile.surfaceVerticalSpan = 0..1 // 2 blocks high (y+1..y+2)

        profile.anchorSampler = StoneBlockSampler(stone)
        // Anchors are always green and sparkly, so stone location is unambiguous.
        profile.anchorSystem = EffectBase.ParticleSystemPrototype(
            particle = ParticleTypes.HAPPY_VILLAGER,
            count = 18,
            spreadX = 0.18,
            spreadY = 0.18,
            spreadZ = 0.18,
            speed = 0.01,
            baseYOffset = 1.2,
        )
        profile.anchorTotalEmissions = 700

        profile.surfaceSampler = SingleChunkSurfaceSampler(stone, subsetSize = 32)
        // Surface: noisy and obvious during placement, distinct from the anchor.
        profile.surfaceSystem = EffectBase.ParticleSystemPrototype(
            particle = ParticleTypes.END_ROD,
            count = 10,
            spreadX = 0.25,
            spreadY = 0.10,
            spreadZ = 0.25,
            speed = 0.02,
            baseYOffset = 1.0,
        )
        profile.surfaceTotalEmissions = 700
    }
}
