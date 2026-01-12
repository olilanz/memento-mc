package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.domain.stones.LorestoneView
import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.particle.ParticleEffect
import net.minecraft.particle.ParticleTypes

/**
 * Canonical particle system prototypes used by current effects.
 *
 * This isolates "what it looks like" from EffectBase mechanics.
 */
object StoneParticleSystems {

    fun anchorFor(stone: StoneView): EffectBase.ParticleSystemPrototype =
        when (stone) {
            is WitherstoneView -> EffectBase.ParticleSystemPrototype(
                particle = ParticleTypes.ASH,
                count = 10,
                spreadX = 0.20,
                spreadY = 0.20,
                spreadZ = 0.20,
                speed = 0.001,
                baseYOffset = 1.2
            )

            is LorestoneView -> EffectBase.ParticleSystemPrototype(
                particle = ParticleTypes.HAPPY_VILLAGER,
                count = 8,
                spreadX = 0.15,
                spreadY = 0.15,
                spreadZ = 0.15,
                speed = 0.001,
                baseYOffset = 1.2
            )

            else -> EffectBase.ParticleSystemPrototype(
                particle = ParticleTypes.END_ROD,
                count = 6,
                spreadX = 0.15,
                spreadY = 0.15,
                spreadZ = 0.15,
                speed = 0.001,
                baseYOffset = 1.2
            )
        }

    /**
     * Exact reproduction of the previous "chunk surface dust" particle system.
     */
    fun surfaceDustFor(stone: StoneView): EffectBase.ParticleSystemPrototype =
        when (stone) {
            is WitherstoneView -> EffectBase.ParticleSystemPrototype(
                particle = ParticleTypes.ASH,
                count = 8,
                spreadX = 0.25,
                spreadY = 0.10,
                spreadZ = 0.25,
                speed = 0.01,
                baseYOffset = 1.0
            )

            is LorestoneView -> EffectBase.ParticleSystemPrototype(
                particle = ParticleTypes.HAPPY_VILLAGER,
                count = 6,
                spreadX = 0.22,
                spreadY = 0.10,
                spreadZ = 0.22,
                speed = 0.01,
                baseYOffset = 1.0
            )

            else -> EffectBase.ParticleSystemPrototype(
                particle = ParticleTypes.END_ROD,
                count = 6,
                spreadX = 0.22,
                spreadY = 0.10,
                spreadZ = 0.22,
                speed = 0.01,
                baseYOffset = 1.0
            )
        }

    /**
     * "Local surface presence" system: a small shimmer at one sampled surface position.
     */
    fun surfacePresenceFor(particle: ParticleEffect): EffectBase.ParticleSystemPrototype =
        EffectBase.ParticleSystemPrototype(
            particle = particle,
            count = 6,
            spreadX = 0.15,
            spreadY = 0.20,
            spreadZ = 0.15,
            speed = 0.01,
            baseYOffset = 1.0
        )
}
