package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.time.GameClock
import ch.oliverlanz.memento.application.visualization.samplers.StoneSampler
import ch.oliverlanz.memento.domain.stones.LorestoneView
import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.particle.ParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import kotlin.math.max
import kotlin.random.Random

/**
 * Base type for long-lived visualization projections.
 *
 * Effects in Memento are:
 * - time-bounded (optional lifetime)
 * - based on deterministic spatial sampling (samplers)
 * - projected into probabilistic particle emission (particle "systems")
 *
 * This base class owns the common pipeline and emission mechanics.
 *
 * Important:
 * - No implicit emission happens here.
 * - Concrete effects explicitly declare their emission plans.
 */
abstract class EffectBase(
    val stone: StoneView,
) {

    data class ParticleSystemPrototype(
        val particle: ParticleEffect,
        val count: Int,
        val spreadX: Double,
        val spreadY: Double,
        val spreadZ: Double,
        val speed: Double,
        /**
         * Base vertical offset above the sampled block position.
         *
         * This matches existing visuals (often y+1.0 for surface, y+1.2 for anchors).
         */
        val baseYOffset: Double,
    )

    sealed class SamplingMode {
        /** Use all sampled positions. */
        data object All : SamplingMode()

        /** Use exactly one random position from the sampled set (uniform). */
        data object OneRandom : SamplingMode()

        /** Use a deterministic subset of size N (seeded, stable across ticks). */
        data class FixedSubset(val n: Int, val seed: Long) : SamplingMode()
    }

    data class EmissionPlan(
        val sampler: StoneSampler,
        val system: ParticleSystemPrototype,
        /**
         * Gate evaluated once per tick. If this fails, the plan emits nothing this tick.
         */
        val perTickChance: Double,
        /**
         * Probability applied per position (after sampling mode selection).
         *
         * This supports "emit with probability per block" when mode is All.
         * For OneRandom/FixedSubset, this usually stays 1.0.
         */
        val perBlockChance: Double,
        /**
         * Inclusive vertical spread in whole blocks applied on top of baseYOffset.
         * Example: 1..4 means +1, +2, +3 or +4 blocks above sampled base position.
         */
        val yOffsetSpread: IntRange = 0..0,
        val mode: SamplingMode = SamplingMode.All,
    )

    private var remainingTicks: Long? = null

    private val sampleCache = HashMap<StoneSampler, Set<BlockPos>>()
    private val subsetCache = HashMap<StoneSampler, Set<BlockPos>>() // for FixedSubset only

    /**
     * Configure a finite lifetime for this effect.
     *
     * If not set, the effect is infinite and must be terminated externally.
     */
    protected fun withLifetime(ticks: Long) {
        remainingTicks = ticks
    }

    protected fun advanceLifetime(deltaTicks: Long): Boolean {
        val rt = remainingTicks ?: return true
        remainingTicks = rt - deltaTicks
        return remainingTicks!! > 0
    }

    /**
     * Declare the emission plans of this effect.
     *
     * This is intentionally declarative:
     * - plans define where (sampler), what (system), and when (probabilities)
     * - the base executes the pipeline and manages sampling caches
     */
    protected abstract fun emissionPlans(): List<EmissionPlan>

    /**
     * Perform one clock-driven visual update.
     *
     * @return true to keep the effect alive, false to terminate and remove it
     */
    open fun tick(world: ServerWorld, clock: GameClock): Boolean {
        if (!advanceLifetime(clock.deltaTicks)) return false

        for (plan in emissionPlans()) {
            if (Random.nextDouble() >= plan.perTickChance) continue

            val base = samplesFor(world, plan.sampler)
            if (base.isEmpty()) continue

            val selected = when (val m = plan.mode) {
                SamplingMode.All -> base
                SamplingMode.OneRandom -> {
                    val idx = Random.nextInt(base.size)
                    setOf(base.elementAt(idx))
                }
                is SamplingMode.FixedSubset -> fixedSubsetFor(plan.sampler, base, m.n, m.seed)
            }

            emitParticles(world, selected, plan.system, plan.perBlockChance, plan.yOffsetSpread)
        }

        return true
    }

    private fun samplesFor(world: ServerWorld, sampler: StoneSampler): Set<BlockPos> =
        sampleCache.getOrPut(sampler) { sampler.sample(world) }

    private fun fixedSubsetFor(
        sampler: StoneSampler,
        base: Set<BlockPos>,
        n: Int,
        seed: Long
    ): Set<BlockPos> {
        // Cache per sampler, deterministic subset is stable for the lifetime of this effect.
        return subsetCache.getOrPut(sampler) {
            if (base.size <= n) base
            else {
                val rng = Random(seed)
                base.toList().shuffled(rng).take(max(0, n)).toSet()
            }
        }
    }

    /**
     * Emit a particle system at each position with probability per block.
     *
     * This is the central reusable emission primitive.
     */
    protected fun emitParticles(
        world: ServerWorld,
        positions: Set<BlockPos>,
        system: ParticleSystemPrototype,
        perBlockChance: Double,
        yOffsetSpread: IntRange = 0..0,
    ) {
        if (positions.isEmpty()) return

        val minY = yOffsetSpread.first
        val maxY = yOffsetSpread.last

        for (p in positions) {
            if (Random.nextDouble() >= perBlockChance) continue

            val yOffsetBlocks = if (minY == maxY) minY else Random.nextInt(minY, maxY + 1)
            val y = p.y + system.baseYOffset + yOffsetBlocks.toDouble()

            world.spawnParticles(
                system.particle,
                p.x + 0.5,
                y,
                p.z + 0.5,
                system.count,
                system.spreadX,
                system.spreadY,
                system.spreadZ,
                system.speed
            )
        }
    }

    /**
     * Exact reproduction of the previous "stone anchor" particle system.
     *
     * This is intentionally a helper so effects can explicitly opt in.
     */
    protected fun anchorSystemFor(stone: StoneView): ParticleSystemPrototype =
        when (stone) {
            is WitherstoneView -> ParticleSystemPrototype(
                particle = ParticleTypes.ASH,
                count = 10,
                spreadX = 0.20,
                spreadY = 0.20,
                spreadZ = 0.20,
                speed = 0.001,
                baseYOffset = 1.2
            )
            is LorestoneView -> ParticleSystemPrototype(
                particle = ParticleTypes.HAPPY_VILLAGER,
                count = 8,
                spreadX = 0.15,
                spreadY = 0.15,
                spreadZ = 0.15,
                speed = 0.001,
                baseYOffset = 1.2
            )
            else -> ParticleSystemPrototype(
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
    protected fun surfaceDustSystemFor(stone: StoneView): ParticleSystemPrototype =
        when (stone) {
            is WitherstoneView -> ParticleSystemPrototype(
                particle = ParticleTypes.CAMPFIRE_COSY_SMOKE,
                count = 6,
                spreadX = 0.15,
                spreadY = 0.20,
                spreadZ = 0.15,
                speed = 0.01,
                baseYOffset = 1.0
            )
            is LorestoneView -> ParticleSystemPrototype(
                particle = ParticleTypes.END_ROD,
                count = 6,
                spreadX = 0.15,
                spreadY = 0.20,
                spreadZ = 0.15,
                speed = 0.01,
                baseYOffset = 1.0
            )
            else -> ParticleSystemPrototype(
                particle = ParticleTypes.CAMPFIRE_COSY_SMOKE,
                count = 6,
                spreadX = 0.15,
                spreadY = 0.20,
                spreadZ = 0.15,
                speed = 0.01,
                baseYOffset = 1.0
            )
        }
}
