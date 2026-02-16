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
 * - Mechanics (ticking, one-time sample binding, dominance-aware dispatch, emission) live here.
 *
 * IMPORTANT (locked emission model):
 * - lifetime (when configured) only gates *when to stop*, never plan semantics.
 * - No totals, no "finite-vs-infinite" branching, no remaining-emissions math.
 * - Per-lane pacing behavior is owned by [EffectPlan].
 *
 * IMPORTANT (locked plan model):
 * - Every lane is executed by one composable [EffectPlan] instance.
 * - Runtime plan instances are recreated on sample rebind.
 * - [RateEffectPlan] emits density-based scatter over bound samples.
 * - [PulsatingEffectPlan] emits density-based bursts on fixed game-time intervals.
 * - All pacing semantics are game-time delta driven from [GameClock].
 *
 * IMPORTANT (locked lane model):
 * - Four fixed lanes: anchor point, anchor chunk, influence area, influence outline.
 * - Lane samples are rebound when requested, and runtime plan instances are recreated per rebind.
 * - Samplers are geometry-only; dominance is interpreted only in this base class.
 */
abstract class EffectBase(
    protected val stone: StoneView
) {

    protected enum class LaneId {
        ANCHOR_POINT,
        ANCHOR_CHUNK,
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
    )

    private var alive: Boolean = true
    private var elapsedGameHours: GameHours = GameHours(0.0)
    private var samplesDirty: Boolean = true

    private var anchorPointRuntimePlan: EffectPlan? = null
    private var anchorChunkRuntimePlan: EffectPlan? = null
    private var influenceAreaRuntimePlan: EffectPlan? = null
    private var influenceOutlineRuntimePlan: EffectPlan? = null

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

        runLaneEffect(world, deltaGameHours, LaneId.ANCHOR_POINT)
        runLaneEffect(world, deltaGameHours, LaneId.ANCHOR_CHUNK)
        runLaneEffect(world, deltaGameHours, LaneId.INFLUENCE_AREA)
        runLaneEffect(world, deltaGameHours, LaneId.INFLUENCE_OUTLINE)

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

        // Anchor point lane defaults
        p.anchorPoint.verticalSpan = 0.0..10.0
        p.anchorPoint.sampler = AnchorPointSampler(stone)
        p.anchorPoint.planFactory = { RateEffectPlan(selectionDensityPerGameHour = 500.0) }
        p.anchorPoint.dominantLoreSystem = anchorParticles()
        p.anchorPoint.dominantWitherSystem = anchorParticles()

        // Anchor chunk lane defaults
        p.anchorChunk.verticalSpan = 1.5..4.0
        p.anchorChunk.sampler = AnchorChunkSampler(stone)
        p.anchorChunk.planFactory = { PulsatingEffectPlan(pulseEveryGameHours = 0.03, selectionDensityPerPulse = 0.1) }
        p.anchorChunk.dominantLoreSystem = lorestoneParticles()
        p.anchorChunk.dominantWitherSystem = witherstoneParticles()

        // Influence area lane defaults
        p.influenceArea.verticalSpan = 0.0..1.0
        p.influenceArea.sampler = InfluenceAreaSurfaceSampler(stone)
        p.influenceArea.planFactory = { PulsatingEffectPlan(pulseEveryGameHours = 0.03, selectionDensityPerPulse = 0.06) }
        p.influenceArea.dominantLoreSystem = lorestoneParticles()
        p.influenceArea.dominantWitherSystem = witherstoneParticles()

        // Influence outline lane defaults
        p.influenceOutline.verticalSpan = 0.0..0.3
        p.influenceOutline.sampler = InfluenceOutlineSurfaceSampler(stone)
        p.influenceOutline.planFactory = { RateEffectPlan(selectionDensityPerGameHour = 50.0) }
        p.influenceOutline.dominantLoreSystem = lorestoneParticles()
        p.influenceOutline.dominantWitherSystem = witherstoneParticles()
    }

    private fun materializeLane(
        world: ServerWorld,
        lane: EffectProfile.LaneProfile,
    ): List<BlockPos> {
        val sampler = lane.sampler ?: return emptyList()
        return sampler.candidates(world)
    }

    /* ---------- Plans ---------- */

    private fun runLaneEffect(
        world: ServerWorld,
        deltaGameHours: GameHours,
        laneId: LaneId,
    ) {
        val runtimePlan = runtimePlanFor(laneId) ?: return
        runtimePlan.tick(
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
        val runtimePlan = lane.planFactory()
        val raw = materializeLane(world, lane)
        if (raw.isEmpty()) {
            runtimePlan.updateSamples(EffectPlan.SampleUpdateContext(samples = emptyList(), random = rng))
            setRuntimePlan(laneId, runtimePlan)
            return
        }

        val bound = ArrayList<EffectPlan.BoundSample>(raw.size)
        for (pos in raw) {
            val particlePrototype = resolveParticlePrototypeForSample(pos, lane) ?: continue
            bound.add(
                EffectPlan.BoundSample(
                    pos = pos,
                    emissionToken = BoundEmissionToken(
                        particlePrototype = particlePrototype,
                        verticalSpread = lane.verticalSpan,
                    ),
                )
            )
        }

        runtimePlan.updateSamples(
            EffectPlan.SampleUpdateContext(
                samples = bound,
                random = rng,
            )
        )
        setRuntimePlan(laneId, runtimePlan)
    }

    private fun rebindAllLaneSamples(world: ServerWorld) {
        rebindLaneSamples(world, LaneId.ANCHOR_POINT, profile.anchorPoint)
        rebindLaneSamples(world, LaneId.ANCHOR_CHUNK, profile.anchorChunk)
        rebindLaneSamples(world, LaneId.INFLUENCE_AREA, profile.influenceArea)
        rebindLaneSamples(world, LaneId.INFLUENCE_OUTLINE, profile.influenceOutline)
        samplesDirty = false
    }

    private fun resolveParticlePrototypeForSample(
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

    private fun runtimePlanFor(laneId: LaneId): EffectPlan? = when (laneId) {
        LaneId.ANCHOR_POINT -> anchorPointRuntimePlan
        LaneId.ANCHOR_CHUNK -> anchorChunkRuntimePlan
        LaneId.INFLUENCE_AREA -> influenceAreaRuntimePlan
        LaneId.INFLUENCE_OUTLINE -> influenceOutlineRuntimePlan
    }

    private fun setRuntimePlan(laneId: LaneId, plan: EffectPlan) {
        when (laneId) {
            LaneId.ANCHOR_POINT -> anchorPointRuntimePlan = plan
            LaneId.ANCHOR_CHUNK -> anchorChunkRuntimePlan = plan
            LaneId.INFLUENCE_AREA -> influenceAreaRuntimePlan = plan
            LaneId.INFLUENCE_OUTLINE -> influenceOutlineRuntimePlan = plan
        }
    }

    private data class BoundEmissionToken(
        val particlePrototype: ParticleSystemPrototype,
        val verticalSpread: ClosedFloatingPointRange<Double>,
    )

    private inner class LaneExecutionSurface(
        private val world: ServerWorld,
    ) : EffectPlan.ExecutionSurface {
        override fun emit(sample: EffectPlan.BoundSample) {
            val token = sample.emissionToken as? BoundEmissionToken ?: return
            emitParticleAt(
                world = world,
                pos = sample.pos,
                particlePrototype = token.particlePrototype,
                yOffsetSpread = token.verticalSpread,
            )
        }
    }

    protected fun anchorParticles(): ParticleSystemPrototype = ParticleSystemPrototype(
        particle = net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER,
        count = 8,
        spreadX = 0.5,
        spreadY = 0.2,
        spreadZ = 0.5,
        speed = 0.01,
    )

    protected fun lorestoneParticles(): ParticleSystemPrototype = ParticleSystemPrototype(
        particle = net.minecraft.particle.ParticleTypes.GLOW,
        count = 4,
        spreadX = 0.15,
        spreadY = 0.15,
        spreadZ = 0.15,
        speed = 0.02,
    )

    protected fun witherstoneParticles(): ParticleSystemPrototype = ParticleSystemPrototype(
        particle = net.minecraft.particle.ParticleTypes.END_ROD,
        count = 4,
        spreadX = 0.15,
        spreadY = 0.15,
        spreadZ = 0.15,
        speed = 0.02,
    )

    protected fun emitParticleAt(
        world: ServerWorld,
        pos: BlockPos,
        particlePrototype: ParticleSystemPrototype,
        yOffsetSpread: ClosedFloatingPointRange<Double>,
    ) {
        val spread = when {
            yOffsetSpread.start == yOffsetSpread.endInclusive -> yOffsetSpread.start
            else -> Random.nextDouble(yOffsetSpread.start, yOffsetSpread.endInclusive)
        }

        world.spawnParticles(
            particlePrototype.particle,
            pos.x + 0.5,
            pos.y + 0.5 + spread,
            pos.z + 0.5,
            particlePrototype.count,
            particlePrototype.spreadX,
            particlePrototype.spreadY,
            particlePrototype.spreadZ,
            particlePrototype.speed
        )
    }
}
