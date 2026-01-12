package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.time.GameClock
import ch.oliverlanz.memento.application.visualization.samplers.SingleChunkSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.StoneBlockSampler
import ch.oliverlanz.memento.application.visualization.samplers.StoneSampler
import ch.oliverlanz.memento.domain.stones.LorestoneView
import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.particle.ParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import kotlin.random.Random

/**
 * Base type for long-lived visualization projections.
 *
 * IMPORTANT:
 * - Effects are driven exclusively by GameClock events (via EffectsHost), not server ticks.
 * - The time dimension (delta ticks) comes from GameClock.
 * - Spatial eligibility is provided by samplers (StoneView -> Set<BlockPos>).
 *
 * This base class defines a canonical ticking pipeline and provides a *default* emission pipeline
 * for the common "chunk area projection" case. Concrete effects may keep custom logic by
 * overriding [onTick].
 */
abstract class EffectBase(
    val stone: StoneView,
    private val profile: DefaultAreaProfile? = null,
) {

    /**
     * Declarative configuration for the default area projection pipeline.
     *
     * This is intentionally small: it captures the "what" and "how often" of an effect,
     * while the base class owns the emission mechanics and caching.
     */
    data class DefaultAreaProfile(
        /** Optional finite lifetime. If null, the effect is infinite and must be terminated externally. */
        val lifetimeTicks: Long? = null,

        /** Whether to include the stone anchor plan (stone block projected upward). */
        val includeAnchor: Boolean = true,

        /** Gate evaluated once per tick for anchor emission. */
        val anchorPerTickChance: Double = 0.0,

        /** Gate evaluated once per tick for the deterministic surface subset dust plan. */
        val surfaceDustPerTickChance: Double = 0.0,

        /** Size of the deterministic surface subset. (Matches current visuals: 32) */
        val surfaceDustSubsetSize: Int = 32,

        /** Gate evaluated once per tick for the local surface presence plan. */
        val surfacePresencePerTickChance: Double = 0.0,

        /** Particle to use for the local surface presence plan. */
        val surfacePresenceParticle: ParticleEffect,

        /**
         * Inclusive vertical spread in whole blocks applied on top of baseYOffset.
         * Example: 1..4 means +1, +2, +3 or +4 blocks above sampled base position.
         */
        val yOffsetSpread: IntRange = 0..0,
    )

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

    // --- Lifetime management (ticks come from GameClock) ---

    private var remainingTicks: Long? = profile?.lifetimeTicks

    private fun advanceLifetime(deltaTicks: Long): Boolean {
        val rt = remainingTicks ?: return true
        remainingTicks = rt - deltaTicks
        return remainingTicks!! > 0
    }

    // --- Sampling caches (samplers are deterministic; effects are free to cache) ---

    private val sampleCache = HashMap<StoneSampler, Set<BlockPos>>()
    private val subsetCache = HashMap<StoneSampler, Set<BlockPos>>() // for deterministic subset only

    // --- Common samplers used by the default pipeline ---

    private val anchorSampler: StoneSampler = StoneBlockSampler(stone)
    private val surfaceSampler: StoneSampler = SingleChunkSurfaceSampler(stone)

    /**
     * FINAL tick entrypoint.
     *
     * Called by EffectsHost in response to GameClock ticks.
     *
     * @return true to keep the effect alive, false to terminate and remove it.
     */
    final fun tick(world: ServerWorld, clock: GameClock): Boolean {
        if (!advanceLifetime(clock.deltaTicks)) return false

        // Default pipeline (inactive unless a profile is provided)
        profile?.let { emitDefaultAreaPlans(world, it) }

        // Hook for custom / supplemental behavior (anchor effects can live here if desired)
        onTick(world, clock)

        return true
    }

    /**
     * Hook for subclasses.
     *
     * Override to implement custom behavior without bypassing the base pipeline.
     */
    protected open fun onTick(world: ServerWorld, clock: GameClock) {
        // default: no-op
    }

    // --- Default area projection pipeline ---

    private fun emitDefaultAreaPlans(world: ServerWorld, p: DefaultAreaProfile) {
        // Anchor presence (explicit; profile-controlled)
        if (p.includeAnchor && p.anchorPerTickChance > 0.0 && Random.nextDouble() < p.anchorPerTickChance) {
            val base = samplesFor(world, anchorSampler)
            emitParticles(
                world = world,
                positions = base,
                system = anchorSystemFor(stone),
                perBlockChance = 1.0,
                yOffsetSpread = 0..0
            )
        }

        // Loud surface dust for validation:
        // deterministic subset of N positions on the stone's chunk surface.
        if (p.surfaceDustPerTickChance > 0.0 && Random.nextDouble() < p.surfaceDustPerTickChance) {
            val base = samplesFor(world, surfaceSampler)
            val selected = fixedSubsetFor(
                sampler = surfaceSampler,
                base = base,
                n = p.surfaceDustSubsetSize,
                seed = stone.position.asLong()
            )

            emitParticles(
                world = world,
                positions = selected,
                system = surfaceDustSystemFor(stone),
                perBlockChance = 1.0,
                yOffsetSpread = 0..0
            )
        }

        // Local surface presence (single random surface position per tick when gated).
        if (p.surfacePresencePerTickChance > 0.0 && Random.nextDouble() < p.surfacePresencePerTickChance) {
            val base = samplesFor(world, surfaceSampler)
            if (base.isNotEmpty()) {
                val pos = base.elementAt(Random.nextInt(base.size))
                emitParticles(
                    world = world,
                    positions = setOf(pos),
                    system = surfacePresenceSystemFor(p.surfacePresenceParticle),
                    perBlockChance = 1.0,
                    yOffsetSpread = p.yOffsetSpread
                )
            }
        }
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
            else base.toList().shuffled(Random(seed)).take(n.coerceAtLeast(0)).toSet()
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

    private fun surfacePresenceSystemFor(particle: ParticleEffect): ParticleSystemPrototype =
        ParticleSystemPrototype(
            particle = particle,
            count = 6,
            spreadX = 0.15,
            spreadY = 0.20,
            spreadZ = 0.15,
            speed = 0.01,
            baseYOffset = 1.0
        )
}
