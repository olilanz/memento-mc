package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.domain.stones.StoneView
import net.minecraft.particle.ParticleTypes

class WitherstoneWaitingEffect(
    stone: StoneView
) : EffectBase(
    stone = stone,
    profile = EffectBase.DefaultAreaProfile(
        lifetimeTicks = 2000,
        includeAnchor = true,
        anchorPerTickChance = 0.2,
        surfaceDustPerTickChance = 0.2,
        surfaceDustSubsetSize = 32,
        surfacePresencePerTickChance = 0.08,
        surfacePresenceParticle = ParticleTypes.ANGRY_VILLAGER,
        yOffsetSpread = 3..8
    )
)
