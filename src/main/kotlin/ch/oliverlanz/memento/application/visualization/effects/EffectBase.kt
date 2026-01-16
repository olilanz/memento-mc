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

    private var anchorEmitted: Int = 0
    private var surfaceEmitted: Int = 0

    /**
     * Lazily configured profile.
     *
     * Kotlin nuance: this MUST NOT run during base construction, because onConfigure()
     * may depend on subclass constructor properties (e.g. stone views).
     */
    private val profile: EffectProfile by lazy { EffectProfile().also { onConfigure(it) } }

    // Cache full samples per sampler (stable for lifetime of the effect).
    private val sampleCache = mutableMapOf<StoneSampler, Set<BlockPos>>()

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
        profile.lifetime?.let { limit ->
            if (elapsedGameHours.value >= limit.value) {
                alive = false
                return false
            }
        }

        // Default pipelines (only active when configured)
        runAnchorPlan(world, deltaGameHours)
        runSurfacePlan(world, deltaGameHours)

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

        val occurrences = when (val lifetime = profile.lifetime) {
            null -> occurrencesForInfinite(
                emissionsPerGameHour = profile.anchorEmissionsPerGameHour,
                deltaGameHours = deltaGameHours
            )
            else -> occurrencesForFiniteTotal(
                total = profile.anchorTotalEmissions,
                alreadyEmitted = anchorEmitted,
                deltaGameHours = deltaGameHours,
                lifetime = lifetime
            )
        }
        if (occurrences <= 0) return

        val base = samplesFor(world, sampler)
        if (base.isEmpty()) return

        repeat(occurrences) {
            emitParticles(
                world = world,
                positions = base,
                system = system,
                perBlockChance = 1.0,
                yOffsetSpread = profile.anchorVerticalSpan
            )
        }

        anchorEmitted += occurrences
    }

    private fun runSurfacePlan(world: ServerWorld, deltaGameHours: GameHours) {
        val sampler = profile.surfaceSampler ?: return
        val system = profile.surfaceSystem ?: return

        val occurrences = when (val lifetime = profile.lifetime) {
            null -> {
                var occ = occurrencesForInfinite(
                    emissionsPerGameHour = profile.surfaceEmissionsPerGameHour,
                    deltaGameHours = deltaGameHours
                )
                // Surface emission safeguard for infinite effects:
                // ensure spatial presence even at very low temporal rates.
                if (occ <= 0 && profile.surfaceEmissionsPerGameHour > 0) occ = 1
                occ
            }
            else -> occurrencesForFiniteTotal(
                total = profile.surfaceTotalEmissions,
                alreadyEmitted = surfaceEmitted,
                deltaGameHours = deltaGameHours,
                lifetime = lifetime
            )
        }
        if (occurrences <= 0) return

        val base = samplesFor(world, sampler)
        if (base.isEmpty()) return

        repeat(occurrences) {
            emitParticles(
                world = world,
                positions = base,
                system = system,
                perBlockChance = 1.0,
                yOffsetSpread = profile.surfaceVerticalSpan
            )
        }

        surfaceEmitted += occurrences
    }

    private fun occurrencesForFiniteTotal(
        total: Int,
        alreadyEmitted: Int,
        deltaGameHours: GameHours,
        lifetime: GameHours,
    ): Int {
        if (total <= 0) return 0
        val remaining = total - alreadyEmitted
        if (remaining <= 0) return 0
        if (deltaGameHours.value <= 0.0) return 0
        if (lifetime.value <= 0.0) return 0

        // Spread remaining emissions uniformly across the remaining lifetime.
        val expected = remaining.toDouble() * (deltaGameHours.value / lifetime.value)
        if (expected <= 0.0) return 0

        val k = floor(expected).toInt()
        val frac = expected - k
        val occurrences = k + if (Random.nextDouble() < frac) 1 else 0
        return occurrences.coerceAtMost(remaining)
    }

    private fun occurrencesForInfinite(
        emissionsPerGameHour: Int,
        deltaGameHours: GameHours,
    ): Int {
        if (emissionsPerGameHour <= 0) return 0
        if (deltaGameHours.value <= 0.0) return 0

        // For infinite effects, emit a steady trickle derived from game time.
        val expected = emissionsPerGameHour.toDouble() * deltaGameHours.value
        if (expected <= 0.0) return 0

        val k = floor(expected).toInt()
        val frac = expected - k
        return k + if (Random.nextDouble() < frac) 1 else 0
    }

    /* ---------- Sampling helpers ---------- */

    private fun samplesFor(world: ServerWorld, sampler: StoneSampler): Set<BlockPos> =
        sampleCache.getOrPut(sampler) { sampler.sample(world) }

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
