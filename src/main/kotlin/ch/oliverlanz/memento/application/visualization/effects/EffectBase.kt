package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.visualization.effectplans.*
import ch.oliverlanz.memento.infrastructure.time.GameClock
import ch.oliverlanz.memento.infrastructure.time.GameHours
import ch.oliverlanz.memento.infrastructure.time.asGameTicks
import ch.oliverlanz.memento.infrastructure.time.toGameHours
import ch.oliverlanz.memento.application.visualization.samplers.*
import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.domain.stones.StoneMapService
import ch.oliverlanz.memento.domain.stones.Lorestone
import ch.oliverlanz.memento.domain.stones.Witherstone
import net.minecraft.particle.ParticleEffect
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import java.util.Random as JavaRandom
import kotlin.random.Random

/**
 * Base class for all visualization effects.
 *
 * IMPORTANT (locked):
 * - Effects are driven by GameWatch via GameClock updates (not server ticks).
 * - This class is a poor-man DSL runtime: subclasses declare lane policy, base executes it.
 * - Subclasses are declarative: override onConfigure(profile) only.
 * - Semantics (lifetime, rates, particles, samplers) live in EffectProfile.
 * - Mechanics (ticking, one-time materialization, dominance-aware dispatch, emission) live here.
 *
 * IMPORTANT (locked emission model):
 * - lifetime (when configured) only gates *when to stop*, never plan semantics.
 * - No totals, no "finite-vs-infinite" branching, no remaining-emissions math.
 * - Per-lane pacing behavior is owned by [EffectPlan].
 *
 * IMPORTANT (locked plan model):
 * - Every lane is executed by one composable [EffectPlan].
 * - [RateEffectPlan] emits a stochastic trickle by expected time throughput.
 * - [PulsatingEffectPlan] emits bursts on fixed game-time intervals.
 * - [RunningEffectPlan] advances a single wrapped cursor and emits trail points every N blocks.
 * - All pacing semantics are game-time delta driven from [GameClock].
 *
 * IMPORTANT (locked lane model):
 * - Four fixed lanes: stone block, stone chunk, influence area, influence outline.
 * - Lane samples are materialized once per effect instance and remain frozen for lifetime.
 * - Samplers are geometry-only; dominance is interpreted only in this base class.
 */
abstract class EffectBase(
    protected val stone: StoneView
) {

    protected enum class LaneId {
        STONE_BLOCK,
        STONE_CHUNK,
        INFLUENCE_AREA,
        INFLUENCE_OUTLINE,
    }

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
    private var samplesDirty: Boolean = true

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

        if (samplesDirty) {
            rebindAllLaneSamples(world)
        }

        runLaneEffect(world, deltaGameHours, LaneId.STONE_BLOCK, profile.stoneBlock)
        runLaneEffect(world, deltaGameHours, LaneId.STONE_CHUNK, profile.stoneChunk)
        runLaneEffect(world, deltaGameHours, LaneId.INFLUENCE_AREA, profile.influenceArea)
        runLaneEffect(world, deltaGameHours, LaneId.INFLUENCE_OUTLINE, profile.influenceOutline)

        // Optional extension hook (not used in this slice)
        onTick(world, clock)

        return alive
    }

    /**
     * Declarative configuration hook.
     *
     * Subclasses should only override effect-specific lane policy, lifetime, and tuning.
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

    /** Mark this effect's bound sample/system set dirty for next tick refresh. */
    fun markSamplesDirty() {
        samplesDirty = true
    }

    /** Force immediate sample/system refresh when a world context is available. */
    fun refreshSamples(world: ServerWorld) {
        rebindAllLaneSamples(world)
    }

    /**
     * Default, fully configured profile.
     *
     * Subclasses should only override the few properties that are specific to the
     * effect instance (most commonly surface sampler, lifetime and surface emission rate).
     */
    private fun defaultProfile(): EffectProfile = EffectProfile().also { p ->
        // Global defaults
        p.lifetime = null

        // Stone block lane defaults (identity/anchor lane)
        p.stoneBlock.verticalSpan = 0..16
        p.stoneBlock.sampler = StoneBlockSampler(stone)
        p.stoneBlock.materialization = SamplerMaterializationConfig()
        p.stoneBlock.plan = RateEffectPlan(emissionsPerGameHour = 200)
        p.stoneBlock.dominantLoreSystem = anchorParticles()
        p.stoneBlock.dominantWitherSystem = anchorParticles()

        // Stone chunk lane defaults
        p.stoneChunk.verticalSpan = 2..3
        p.stoneChunk.sampler = SingleChunkSurfaceSampler(stone)
        p.stoneChunk.plan = PulsatingEffectPlan(pulseEveryGameHours = 0.04, emissionsPerPulse = 143)
        p.stoneChunk.materialization = SamplerMaterializationConfig()
        p.stoneChunk.dominantLoreSystem = lorestoneParticles()
        p.stoneChunk.dominantWitherSystem = witherstoneParticles()

        // Influence area lane defaults
        p.influenceArea.verticalSpan = 1..1
        p.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        p.influenceArea.plan = PulsatingEffectPlan(pulseEveryGameHours = 0.04, emissionsPerPulse = 143)
        p.influenceArea.materialization = SamplerMaterializationConfig()
        p.influenceArea.dominantLoreSystem = lorestoneParticles()
        p.influenceArea.dominantWitherSystem = witherstoneParticles()

        // Influence outline lane defaults
        p.influenceOutline.verticalSpan = 1..1
        p.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone, thicknessBlocks = 4)
        p.influenceOutline.plan = RunningEffectPlan(speedChunksPerGameHour = 96.0, maxCursorSpacingBlocks = 6)
        p.influenceOutline.materialization = SamplerMaterializationConfig()
        p.influenceOutline.dominantLoreSystem = lorestoneParticles()
        p.influenceOutline.dominantWitherSystem = witherstoneParticles()
    }

    private fun materializeLane(
        world: ServerWorld,
        lane: EffectProfile.LaneProfile,
    ): List<BlockPos> {
        val sampler = lane.sampler ?: return emptyList()
        return sampler.materialize(world, rng, lane.materialization)
    }

    /* ---------- Plans ---------- */

    private fun runLaneEffect(
        world: ServerWorld,
        deltaGameHours: GameHours,
        laneId: LaneId,
        lane: EffectProfile.LaneProfile,
    ) {
        lane.plan.tick(
            EffectPlan.TickContext(
                deltaGameHours = deltaGameHours,
                random = rng,
                executionSurface = LaneExecutionSurface(world),
            )
        )
    }

    private fun rebindLaneSamples(
        world: ServerWorld,
        laneId: LaneId,
        lane: EffectProfile.LaneProfile,
    ) {
        val raw = materializeLane(world, lane)
        if (raw.isEmpty()) {
            lane.plan.updateSamples(EffectPlan.SampleUpdateContext(samples = emptyList(), random = rng))
            return
        }

        val bound = ArrayList<EffectPlan.BoundSample>(raw.size)
        for (pos in raw) {
            val system = chooseSystemFor(pos, lane) ?: continue
            bound.add(
                EffectPlan.BoundSample(
                    pos = pos,
                    emissionToken = BoundEmissionToken(
                        system = system,
                        verticalSpread = lane.verticalSpan,
                    ),
                )
            )
        }

        lane.plan.updateSamples(
            EffectPlan.SampleUpdateContext(
                samples = bound,
                random = rng,
            )
        )
    }

    private fun rebindAllLaneSamples(world: ServerWorld) {
        rebindLaneSamples(world, LaneId.STONE_BLOCK, profile.stoneBlock)
        rebindLaneSamples(world, LaneId.STONE_CHUNK, profile.stoneChunk)
        rebindLaneSamples(world, LaneId.INFLUENCE_AREA, profile.influenceArea)
        rebindLaneSamples(world, LaneId.INFLUENCE_OUTLINE, profile.influenceOutline)
        samplesDirty = false
    }

    private fun chooseSystemFor(
        pos: BlockPos,
        lane: EffectProfile.LaneProfile,
    ): ParticleSystemPrototype? {
        return when (dominantKindAt(pos)) {
            Lorestone::class -> lane.dominantLoreSystem
            Witherstone::class -> lane.dominantWitherSystem
            else -> null
        }
    }

    private fun dominantKindAt(pos: BlockPos) =
        StoneMapService.dominantByChunk(stone.dimension)[ChunkPos(pos)]

    private data class BoundEmissionToken(
        val system: ParticleSystemPrototype,
        val verticalSpread: IntRange,
    )

    private inner class LaneExecutionSurface(
        private val world: ServerWorld,
    ) : EffectPlan.ExecutionSurface {
        override fun emit(sample: EffectPlan.BoundSample) {
            val token = sample.emissionToken as? BoundEmissionToken ?: return
            emitParticleAt(
                world = world,
                pos = sample.pos,
                system = token.system,
                yOffsetSpread = token.verticalSpread,
            )
        }
    }

    protected fun anchorParticles(): ParticleSystemPrototype = ParticleSystemPrototype(
        particle = net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER,
        count = 12,
        spreadX = 0.45,
        spreadY = 0.15,
        spreadZ = 0.45,
        speed = 0.01,
        baseYOffset = 1.0,
    )

    protected fun lorestoneParticles(): ParticleSystemPrototype = ParticleSystemPrototype(
        particle = net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME,
        count = 10,
        spreadX = 0.375,
        spreadY = 0.12,
        spreadZ = 0.375,
        speed = 0.01,
        baseYOffset = 1.0,
    )

    protected fun witherstoneParticles(): ParticleSystemPrototype = ParticleSystemPrototype(
        particle = net.minecraft.particle.ParticleTypes.END_ROD,
        count = 10,
        spreadX = 0.375,
        spreadY = 0.12,
        spreadZ = 0.375,
        speed = 0.01,
        baseYOffset = 1.0,
    )

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
