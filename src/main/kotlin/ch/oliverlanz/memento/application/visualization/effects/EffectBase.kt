package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.time.GameClock
import ch.oliverlanz.memento.application.time.GameHours
import ch.oliverlanz.memento.application.time.asGameTicks
import ch.oliverlanz.memento.application.time.toGameHours
import ch.oliverlanz.memento.application.visualization.samplers.StoneSampler
import net.minecraft.particle.ParticleEffect
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import kotlin.math.floor
import kotlin.random.Random

/**
 * Base class for all visualization effects.
 *
 * IMPORTANT (locked):
 * - Effects are driven by GameWatch via GameClock updates (not server ticks).
 * - Subclasses are declarative: override onConfigure(profile) only.
 * - Semantics (lifetime, rates, particles, samplers) live in EffectProfile.
 * - Mechanics (ticking, sampling caches, emission) live here.
 */
abstract class EffectBase {

    data class ParticleSystemPrototype(
        val particle: ParticleEffect,
        val count: Int,
        val spreadX: Double,
        val spreadY: Double,
        val spreadZ: Double,
        val speed: Double,
        /** Base vertical offset above the sampled block (often y+1.0). */
        val baseYOffset: Double,
    )

    private var alive: Boolean = true
    private var elapsedGameHours: GameHours = GameHours(0.0)

    /**
     * Lazily configured profile.
     *
     * Kotlin nuance: this MUST NOT run during base construction, because onConfigure()
     * may depend on subclass constructor properties (e.g. stone views).
     */
    private val profile: EffectProfile by lazy { EffectProfile().also { onConfigure(it) } }

    // Cache full samples per sampler (stable for lifetime of the effect).
    private val sampleCache = mutableMapOf<StoneSampler, Set<BlockPos>>()

    // Cache deterministic subsets per sampler (stable for lifetime of the effect).
    private val subsetCache = mutableMapOf<StoneSampler, Set<BlockPos>>()

    /**
     * FINAL tick entry point. Subclasses must NOT override this.
     */
    final fun tick(world: ServerWorld, clock: GameClock): Boolean {
        if (!alive) return false

        val deltaGameHours = clock.deltaTicks.asGameTicks().toGameHours()
        if (deltaGameHours.value > 0.0) {
            elapsedGameHours = GameHours(elapsedGameHours.value + deltaGameHours.value)
        }

        // Finite lifetime (if configured)
        profile.lifetimeGameHours?.let { limit ->
            if (elapsedGameHours.value >= limit.value) {
                alive = false
                return false
            }
        }

        // Default pipelines (only active when configured)
        runAnchorPlan(world, deltaGameHours)
        runSurfaceDustPlan(world, deltaGameHours)
        runSurfacePresencePlan(world, deltaGameHours)

        // Optional extension hook (not used in this slice)
        onTick(world, clock)

        return alive
    }

    /**
     * Declarative configuration hook.
     */
    protected open fun onConfigure(profile: EffectProfile) = Unit

    /**
     * Optional extension hook.
     *
     * For this slice, effects should not override this.
     */
    protected open fun onTick(world: ServerWorld, clock: GameClock) = Unit

    protected fun terminate() {
        alive = false
    }

    /* ---------- Plans ---------- */

    private fun runAnchorPlan(world: ServerWorld, deltaGameHours: GameHours) {
        val sampler = profile.anchorSampler ?: return
        val system = profile.anchorSystem ?: return
        val occurrences = occurrencesForRate(
            ratePerGameHour = effectiveRate(profile.anchorRatePerGameHour),
            deltaGameHours = deltaGameHours
        )
        if (occurrences <= 0) return

        val base = samplesFor(world, sampler)
        if (base.isEmpty()) return

        repeat(occurrences) {
            emitParticles(
                world = world,
                positions = base,
                system = system,
                perBlockChance = 1.0,
                yOffsetSpread = profile.yOffsetSpread
            )
        }
    }

    private fun runSurfaceDustPlan(world: ServerWorld, deltaGameHours: GameHours) {
        val sampler = profile.surfaceSampler ?: return
        val system = profile.surfaceDustSystem ?: return
        val occurrences = occurrencesForRate(
            ratePerGameHour = effectiveRate(profile.surfaceDustRatePerGameHour),
            deltaGameHours = deltaGameHours
        )
        if (occurrences <= 0) return

        val base = samplesFor(world, sampler)
        if (base.isEmpty()) return

        val selected = fixedSubsetFor(
            sampler = sampler,
            base = base,
            n = profile.surfaceDustSubsetSize,
            seed = profile.surfaceDustSubsetSeed
        )

        repeat(occurrences) {
            emitParticles(
                world = world,
                positions = selected,
                system = system,
                perBlockChance = 1.0,
                yOffsetSpread = 0..0 // exact reproduction of dust plan
            )
        }
    }

    private fun runSurfacePresencePlan(world: ServerWorld, deltaGameHours: GameHours) {
        val sampler = profile.surfaceSampler ?: return
        val system = profile.surfacePresenceSystem ?: return
        val occurrences = occurrencesForRate(
            ratePerGameHour = effectiveRate(profile.surfacePresenceRatePerGameHour),
            deltaGameHours = deltaGameHours
        )
        if (occurrences <= 0) return

        val base = samplesFor(world, sampler)
        if (base.isEmpty()) return

        repeat(occurrences) {
            val pos = base.elementAt(Random.nextInt(base.size))
            emitParticles(
                world = world,
                positions = setOf(pos),
                system = system,
                perBlockChance = 1.0,
                yOffsetSpread = profile.yOffsetSpread
            )
        }
    }

    /* ---------- Rate helpers ---------- */

    private fun effectiveRate(baseRatePerGameHour: Double): Double {
        if (baseRatePerGameHour <= 0.0) return 0.0
        val burstDuration = profile.burstDurationGameHours
        val burstMultiplier = profile.burstMultiplier
        return if (burstDuration.value > 0.0 && elapsedGameHours.value < burstDuration.value && burstMultiplier > 1.0) {
            baseRatePerGameHour * burstMultiplier
        } else {
            baseRatePerGameHour
        }
    }

    /**
     * Convert a rate (occurrences per game hour) into an integer number of occurrences for this update.
     *
     * We use an "integer + fractional Bernoulli" model:
     * - k = floor(expected)
     * - +1 with probability frac(expected)
     *
     * This is deterministic-in-expectation and scales correctly when time is advanced manually.
     */
    private fun occurrencesForRate(ratePerGameHour: Double, deltaGameHours: GameHours): Int {
        if (ratePerGameHour <= 0.0) return 0
        if (deltaGameHours.value <= 0.0) return 0

        val expected = ratePerGameHour * deltaGameHours.value
        if (expected <= 0.0) return 0

        val k = floor(expected).toInt()
        val frac = expected - k
        return k + if (Random.nextDouble() < frac) 1 else 0
    }

    /* ---------- Sampling helpers ---------- */

    private fun samplesFor(world: ServerWorld, sampler: StoneSampler): Set<BlockPos> =
        sampleCache.getOrPut(sampler) { sampler.sample(world) }

    private fun fixedSubsetFor(
        sampler: StoneSampler,
        base: Set<BlockPos>,
        n: Int,
        seed: Long
    ): Set<BlockPos> {
        // Cache per sampler; deterministic subset is stable for the lifetime of this effect.
        return subsetCache.getOrPut(sampler) {
            if (base.size <= n) return@getOrPut base
            val rnd = Random(seed)
            base.shuffled(rnd).take(n).toSet()
        }
    }

    /* ---------- Particle emission ---------- */

    protected fun emitParticles(
        world: ServerWorld,
        positions: Set<BlockPos>,
        system: ParticleSystemPrototype,
        perBlockChance: Double,
        yOffsetSpread: IntRange,
    ) {
        if (positions.isEmpty()) return
        if (perBlockChance <= 0.0) return

        for (pos in positions) {
            if (perBlockChance < 1.0 && Random.nextDouble() >= perBlockChance) continue

            val spread = when {
                yOffsetSpread.first == yOffsetSpread.last -> yOffsetSpread.first
                else -> Random.nextInt(yOffsetSpread.first, yOffsetSpread.last + 1)
            }.toDouble()

            world.spawnParticles(
                system.particle,
                pos.x + 0.5,
                pos.y + system.baseYOffset + spread,
                pos.z + 0.5,
                system.count,
                system.spreadX,
                system.spreadY,
                system.spreadZ,
                system.speed
            )
        }
    }
}
