package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.domain.stones.StoneView
import net.minecraft.particle.ParticleTypes

class StoneInspectionEffect(
    stone: StoneView
) : EffectBase(
    stone = stone,
    profile = EffectBase.DefaultAreaProfile(
        lifetimeTicks = 1000,
        includeAnchor = true,
        anchorPerTickChance = 0.2,
        surfaceDustPerTickChance = 0.2,
        surfaceDustSubsetSize = 32,
        surfacePresencePerTickChance = 0.08,
        surfacePresenceParticle = ParticleTypes.ELECTRIC_SPARK,
        yOffsetSpread = 0..0
    )
)
