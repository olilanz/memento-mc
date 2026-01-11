package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.samplers.SingleChunkSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.StoneBlockSampler
import ch.oliverlanz.memento.application.visualization.samplers.StoneSampler
import ch.oliverlanz.memento.domain.stones.LorestoneView
import net.minecraft.particle.ParticleTypes

class LorestonePlacementEffect(
    stone: LorestoneView
) : EffectBase(stone) {

    private val anchorEmissionChance = 0.15
    private val surfaceEmissionChance = 0.05

    private val anchorSampler: StoneSampler = StoneBlockSampler(stone)
    private val surfaceSampler: StoneSampler = SingleChunkSurfaceSampler(stone)

    init {
        // ~1 in-game hour (1000 ticks).
        withLifetime(1000)
    }

    override fun emissionPlans(): List<EmissionPlan> = listOf(
        // Anchor presence (explicit; no implicit emission in the base).
        EmissionPlan(
            sampler = anchorSampler,
            system = anchorSystemFor(stone),
            perTickChance = anchorEmissionChance,
            perBlockChance = 1.0,
            yOffsetSpread = 0..0,
            mode = SamplingMode.All
        ),

        // Loud surface dust for validation (matches previous StoneParticleEmitters behavior):
        // deterministic subset of 32 positions on the stone's chunk surface.
        EmissionPlan(
            sampler = surfaceSampler,
            system = surfaceDustSystemFor(stone),
            perTickChance = anchorEmissionChance,
            perBlockChance = 1.0,
            yOffsetSpread = 0..0,
            mode = SamplingMode.FixedSubset(32, stone.position.asLong())
        ),

        // Local surface presence (single random surface position per tick when gated).
        EmissionPlan(
            sampler = surfaceSampler,
            system = ParticleSystemPrototype(
                particle = ParticleTypes.ENCHANT,
                count = 6,
                spreadX = 0.15,
                spreadY = 0.20,
                spreadZ = 0.15,
                speed = 0.01,
                baseYOffset = 1.0
            ),
            perTickChance = surfaceEmissionChance,
            perBlockChance = 1.0,
            yOffsetSpread = 0..0,
            mode = SamplingMode.OneRandom
        )
    )
}
