package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.particle.ParticleTypes

class WitherstonePlacementEffect(
    stone: WitherstoneView
) : EffectBase(
    stone = stone,
    profile = EffectBase.DefaultAreaProfile(
        lifetimeTicks = 1000,
        includeAnchor = true,
        anchorPerTickChance = 0.15,
        surfaceDustPerTickChance = 0.15,
        surfaceDustSubsetSize = 32,
        surfacePresencePerTickChance = 0.05,
        surfacePresenceParticle = ParticleTypes.ASH,
        yOffsetSpread = 0..3
    )
)
