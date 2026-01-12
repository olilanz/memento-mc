package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.domain.stones.LorestoneView
import net.minecraft.particle.ParticleTypes

class LorestonePlacementEffect(
    stone: LorestoneView
) : EffectBase(
    stone = stone,
    profile = EffectBase.DefaultAreaProfile(
        lifetimeTicks = 1000,
        includeAnchor = true,
        anchorPerTickChance = 0.15,
        surfaceDustPerTickChance = 0.15,
        surfaceDustSubsetSize = 32,
        surfacePresencePerTickChance = 0.05,
        surfacePresenceParticle = ParticleTypes.ENCHANT,
        yOffsetSpread = 0..3
    )
)
