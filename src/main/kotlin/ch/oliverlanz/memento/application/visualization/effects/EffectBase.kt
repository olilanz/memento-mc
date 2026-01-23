package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.time.GameClock
import ch.oliverlanz.memento.application.time.GameHours
import ch.oliverlanz.memento.application.time.asGameTicks
import ch.oliverlanz.memento.application.time.toGameHours
import ch.oliverlanz.memento.application.visualization.samplers.StoneBlockSampler
import ch.oliverlanz.memento.domain.stones.StoneView
import net.minecraft.particle.ParticleEffect
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import java.util.Random as JavaRandom
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
 *
 * IMPORTANT (locked emission model):
 * - Effects are ALWAYS rate-based (emissionsPerGameHour).
 * - lifetime (when configured) only gates *when to stop*, never emission logic.
 * - No totals, no "finite-vs-infinite" branching, no remaining-emissions math.
 *
 * IMPORTANT (locked anchor model):
 * - Anchor configuration is universal and owned by the base class.
 * - Subclasses do NOT configure anchor sampler or anchor emission rate.
 */
abstract class EffectBase(
    protected val stone: StoneView
) {

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
     * may depend on subclass constructor properties.
     */
    private val profile: EffectProfile by lazy {
        defaultProfile().also { onConfigure(it) }
    }

    private val rng = JavaRandom()

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
     *
     * Subclasses should only override effect-specific deltas (most commonly surface sampler,
     * lifetime and surface pacing, and occasionally particle prototype tweaks).
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

    /**
     * Default, fully configured profile.
     *
     * Subclasses should only override the few properties that are specific to the
     * effect instance (most commonly surface sampler, lifetime and surface emission rate).
     */
    private fun defaultProfile(): EffectProfile = EffectProfile().also { p ->
        // Anchor defaults (shared across all effects)
        p.anchorVerticalSpan = 0..16
        p.anchorSampler = StoneBlockSampler(stone)
        p.anchorEmissionsPerGameHour = 80
        p.anchorSystem = ParticleSystemPrototype(
            particle = net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER,
            count = 10,
            spreadX = 0.30,
            spreadY = 0.60,
            spreadZ = 0.30,
            speed = 0.02,
            baseYOffset = 1.2,
        )

        // Surface defaults (shared baseline; subclasses may override particle/system details)
        p.surfaceVerticalSpan = 0..0
        p.surfaceSampler = null                     // Surface sampler intentionally left null by default.
        p.surfaceEmissionsPerGameHour = 200         // Rate defaults live in the base (not 0).
        p.surfaceSystem = ParticleSystemPrototype(
            particle = net.minecraft.particle.ParticleTypes.END_ROD,
            count = 6,
            spreadX = 0.30,
            spreadY = 0.10,
            spreadZ = 0.30,
            speed = 0.01,
            baseYOffset = 1.0,
        )
    }

    /* ---------- Plans ---------- */

    private fun runAnchorPlan(world: ServerWorld, deltaGameHours: GameHours) {
        val sampler = profile.anchorSampler ?: return
        val system = profile.anchorSystem ?: return

        var occurrences = occurrencesForRate(
            emissionsPerGameHour = profile.anchorEmissionsPerGameHour,
            deltaGameHours = deltaGameHours
        )

        // Anchor safeguard:
        // ensure visible spatial presence even at very low temporal rates.
        if (occurrences <= 0 && profile.anchorEmissionsPerGameHour > 0) occurrences = 1
        if (occurrences <= 0) return

        repeat(occurrences) {
            val base = sampler.randomCandidate(world, rng) ?: return
            emitParticleAt(
                world = world,
                pos = base,
                system = system,
                yOffsetSpread = profile.anchorVerticalSpan
            )
        }
    }

    private fun runSurfacePlan(world: ServerWorld, deltaGameHours: GameHours) {
        val sampler = profile.surfaceSampler ?: return
        val system = profile.surfaceSystem ?: return

        var occurrences = occurrencesForRate(
            emissionsPerGameHour = profile.surfaceEmissionsPerGameHour,
            deltaGameHours = deltaGameHours
        )

        // Surface safeguard:
        // ensure spatial presence even at very low temporal rates.
        if (occurrences <= 0 && profile.surfaceEmissionsPerGameHour > 0) occurrences = 1
        if (occurrences <= 0) return

        repeat(occurrences) {
            val base = sampler.randomCandidate(world, rng) ?: return
            emitParticleAt(
                world = world,
                pos = base,
                system = system,
                yOffsetSpread = profile.surfaceVerticalSpan
            )
        }
    }

    private fun occurrencesForRate(
        emissionsPerGameHour: Int,
        deltaGameHours: GameHours,
    ): Int {
        if (emissionsPerGameHour <= 0) return 0
        if (deltaGameHours.value <= 0.0) return 0

        // Emit a steady trickle derived from game time.
        val expected = emissionsPerGameHour.toDouble() * deltaGameHours.value
        if (expected <= 0.0) return 0

        val k = floor(expected).toInt()
        val frac = expected - k
        return k + if (Random.nextDouble() < frac) 1 else 0
    }

    /* ---------- Particle emission ---------- */

    protected fun emitParticleAt(
        world: ServerWorld,
        pos: BlockPos,
        system: ParticleSystemPrototype,
        yOffsetSpread: IntRange,
    ) {
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
