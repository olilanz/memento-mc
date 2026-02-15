package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.infrastructure.time.GameClock
import ch.oliverlanz.memento.infrastructure.time.GameHours
import ch.oliverlanz.memento.infrastructure.time.asGameTicks
import ch.oliverlanz.memento.infrastructure.time.toGameHours
import ch.oliverlanz.memento.application.visualization.effects.EffectProfile.LanePlan
import ch.oliverlanz.memento.application.visualization.samplers.SamplerMaterializationConfig
import ch.oliverlanz.memento.application.visualization.samplers.StoneBlockSampler
import ch.oliverlanz.memento.application.visualization.samplers.SingleChunkSurfaceSampler
import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.domain.stones.StoneMapService
import ch.oliverlanz.memento.domain.stones.Lorestone
import ch.oliverlanz.memento.domain.stones.Witherstone
import net.minecraft.particle.ParticleEffect
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import java.util.Random as JavaRandom
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
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
 * - Per-lane pacing behavior is owned by [LanePlan].
 *
 * IMPORTANT (locked plan model):
 * - Every lane is executed by a composable [LanePlan].
 * - [LanePlan.Rate] emits a stochastic trickle by expected time throughput.
 * - [LanePlan.Pulsating] emits bursts on fixed game-time intervals.
 * - [LanePlan.Running] advances a single wrapped cursor and emits trail points every N blocks.
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
    private val materializedByLane = linkedMapOf<LaneId, List<BlockPos>>()
    private val runningPathByLane = linkedMapOf<LaneId, RunningPath>()
    private var materialized: Boolean = false

    private val pulsatingElapsedByLane = linkedMapOf<LaneId, GameHours>()
    private val runningDistanceByLane = linkedMapOf<LaneId, Double>()
    private val runningNextEmissionDistanceByLane = linkedMapOf<LaneId, Double>()

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
        ensureMaterialized(world)

        runLanePlan(world, deltaGameHours, LaneId.STONE_BLOCK, profile.stoneBlock)
        runLanePlan(world, deltaGameHours, LaneId.STONE_CHUNK, profile.stoneChunk)
        runLanePlan(world, deltaGameHours, LaneId.INFLUENCE_AREA, profile.influenceArea)
        runLanePlan(world, deltaGameHours, LaneId.INFLUENCE_OUTLINE, profile.influenceOutline)

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
        p.stoneBlock.plan = LanePlan.Rate(emissionsPerGameHour = 80)
        p.stoneBlock.dominantLoreSystem = lorestoneParticles()
        p.stoneBlock.dominantWitherSystem = witherstoneParticles()

        // Stone chunk lane defaults
        p.stoneChunk.verticalSpan = 0..0
        p.stoneChunk.sampler = SingleChunkSurfaceSampler(stone)
        p.stoneChunk.materialization = SamplerMaterializationConfig()
        p.stoneChunk.plan = LanePlan.Rate(emissionsPerGameHour = 200)
        p.stoneChunk.dominantLoreSystem = anchorParticles()
        p.stoneChunk.dominantWitherSystem = anchorParticles()

        // Influence area lane defaults
        p.influenceArea.sampler = null
        p.influenceArea.verticalSpan = 0..0
        p.influenceArea.plan = LanePlan.Rate(emissionsPerGameHour = 0)
        p.influenceArea.materialization = SamplerMaterializationConfig()
        p.influenceArea.dominantLoreSystem = lorestoneParticles()
        p.influenceArea.dominantWitherSystem = witherstoneParticles()

        // Influence outline lane defaults
        p.influenceOutline.sampler = null
        p.influenceOutline.verticalSpan = 0..0
        p.influenceOutline.plan = LanePlan.Rate(emissionsPerGameHour = 0)
        p.influenceOutline.materialization = SamplerMaterializationConfig()
        p.influenceOutline.dominantLoreSystem = lorestoneParticles()
        p.influenceOutline.dominantWitherSystem = witherstoneParticles()
    }

    /* ---------- Lane materialization ---------- */

    private fun ensureMaterialized(world: ServerWorld) {
        if (materialized) return
        materializedByLane.clear()
        runningPathByLane.clear()

        materializedByLane[LaneId.STONE_BLOCK] = materializeLane(world, profile.stoneBlock)
        materializedByLane[LaneId.STONE_CHUNK] = materializeLane(world, profile.stoneChunk)
        materializedByLane[LaneId.INFLUENCE_AREA] = materializeLane(world, profile.influenceArea)
        materializedByLane[LaneId.INFLUENCE_OUTLINE] = materializeLane(world, profile.influenceOutline)

        runningPathByLane[LaneId.STONE_BLOCK] = RunningPath.from(materializedByLane[LaneId.STONE_BLOCK].orEmpty())
        runningPathByLane[LaneId.STONE_CHUNK] = RunningPath.from(materializedByLane[LaneId.STONE_CHUNK].orEmpty())
        runningPathByLane[LaneId.INFLUENCE_AREA] = RunningPath.from(materializedByLane[LaneId.INFLUENCE_AREA].orEmpty())
        runningPathByLane[LaneId.INFLUENCE_OUTLINE] = RunningPath.from(materializedByLane[LaneId.INFLUENCE_OUTLINE].orEmpty())

        materialized = true
    }

    private fun materializeLane(
        world: ServerWorld,
        lane: EffectProfile.LaneProfile,
    ): List<BlockPos> {
        val sampler = lane.sampler ?: return emptyList()
        return sampler.materialize(world, rng, lane.materialization)
    }

    /* ---------- Plans ---------- */

    private fun runLanePlan(
        world: ServerWorld,
        deltaGameHours: GameHours,
        laneId: LaneId,
        lane: EffectProfile.LaneProfile,
    ) {
        val candidates = materializedByLane[laneId].orEmpty()
        if (candidates.isEmpty()) return

        when (val plan = lane.plan) {
            is LanePlan.Rate -> {
                val occurrences = occurrencesForRate(
                    emissionsPerGameHour = plan.emissionsPerGameHour,
                    deltaGameHours = deltaGameHours
                )
                if (occurrences <= 0) return

                repeat(occurrences) {
                    val base = candidates[rng.nextInt(candidates.size)]
                    val system = chooseSystemFor(base, lane) ?: return@repeat
                    emitParticleAt(
                        world = world,
                        pos = base,
                        system = system,
                        yOffsetSpread = lane.verticalSpan
                    )
                }
            }

            is LanePlan.Pulsating -> {
                val intervalHours = plan.pulseEveryGameHours
                if (intervalHours <= 0.0 || plan.emissionsPerPulse <= 0) return
                if (deltaGameHours.value <= 0.0) return

                val elapsed = pulsatingElapsedByLane.getOrPut(laneId) { GameHours(0.0) }
                val updated = GameHours(elapsed.value + deltaGameHours.value)
                val pulses = floor(updated.value / intervalHours).toInt()
                pulsatingElapsedByLane[laneId] = GameHours(updated.value - (pulses * intervalHours))
                if (pulses <= 0) return

                repeat(pulses * plan.emissionsPerPulse) {
                    val base = candidates[rng.nextInt(candidates.size)]
                    val system = chooseSystemFor(base, lane) ?: return@repeat
                    emitParticleAt(
                        world = world,
                        pos = base,
                        system = system,
                        yOffsetSpread = lane.verticalSpan
                    )
                }
            }

            is LanePlan.Running -> {
                val path = runningPathByLane[laneId] ?: return
                if (path.totalLengthBlocks <= 0.0) return
                if (deltaGameHours.value <= 0.0) return

                val spacing = plan.maxCursorSpacingBlocks.coerceAtLeast(1).toDouble()
                val speedBlocksPerHour = (plan.speedChunksPerGameHour.coerceAtLeast(0.0) * 16.0)
                val travelDistance = speedBlocksPerHour * deltaGameHours.value
                if (travelDistance <= 0.0) return

                val prevAbsolute = runningDistanceByLane.getOrPut(laneId) { 0.0 }
                val currentAbsolute = prevAbsolute + travelDistance
                runningDistanceByLane[laneId] = currentAbsolute

                var nextEmission = runningNextEmissionDistanceByLane.getOrPut(laneId) { prevAbsolute }
                while (nextEmission <= currentAbsolute) {
                    val pos = path.positionAtWrappedDistance(nextEmission)
                    val system = chooseSystemFor(pos, lane)
                    if (system != null) {
                        emitParticleAt(
                            world = world,
                            pos = pos,
                            system = system,
                            yOffsetSpread = lane.verticalSpan
                        )
                    }
                    nextEmission += spacing
                }
                runningNextEmissionDistanceByLane[laneId] = nextEmission
            }
        }
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

    protected fun anchorParticles(): ParticleSystemPrototype = ParticleSystemPrototype(
        particle = net.minecraft.particle.ParticleTypes.END_ROD,
        count = 12,
        spreadX = 0.45,
        spreadY = 0.15,
        spreadZ = 0.45,
        speed = 0.01,
        baseYOffset = 1.0,
    )

    protected fun lorestoneParticles(): ParticleSystemPrototype = ParticleSystemPrototype(
        particle = net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER,
        count = 10,
        spreadX = 0.375,
        spreadY = 0.12,
        spreadZ = 0.375,
        speed = 0.01,
        baseYOffset = 1.0,
    )

    protected fun witherstoneParticles(): ParticleSystemPrototype = ParticleSystemPrototype(
        particle = net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME,
        count = 10,
        spreadX = 0.375,
        spreadY = 0.12,
        spreadZ = 0.375,
        speed = 0.01,
        baseYOffset = 1.0,
    )

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

    private data class RunningPath(
        val points: List<BlockPos>,
        val cumulativeSegmentLength: List<Double>,
        val totalLengthBlocks: Double,
    ) {
        companion object {
            fun from(points: List<BlockPos>): RunningPath {
                if (points.isEmpty()) return RunningPath(emptyList(), emptyList(), 0.0)
                if (points.size == 1) return RunningPath(points, listOf(0.0), 0.0)

                val cumulative = ArrayList<Double>(points.size)
                var acc = 0.0
                cumulative.add(acc)

                for (i in 0 until points.size - 1) {
                    acc += segmentLength(points[i], points[i + 1])
                    cumulative.add(acc)
                }
                acc += segmentLength(points.last(), points.first())

                return RunningPath(
                    points = points,
                    cumulativeSegmentLength = cumulative,
                    totalLengthBlocks = acc,
                )
            }

            private fun segmentLength(a: BlockPos, b: BlockPos): Double {
                val dx = (b.x - a.x).toDouble()
                val dz = (b.z - a.z).toDouble()
                return hypot(dx, dz)
            }
        }

        fun positionAtWrappedDistance(distance: Double): BlockPos {
            if (points.isEmpty()) return BlockPos(0, 0, 0)
            if (points.size == 1 || totalLengthBlocks <= 0.0) return points.first()

            val wrapped = ((distance % totalLengthBlocks) + totalLengthBlocks) % totalLengthBlocks
            var index = cumulativeSegmentLength.binarySearch(wrapped)
            if (index < 0) index = (-index - 2).coerceAtLeast(0)
            if (index >= points.size) index = points.lastIndex

            val start = points[index]
            val end = points[(index + 1) % points.size]
            val segStart = cumulativeSegmentLength[index]
            val segLen = segmentLength(start, end)
            if (segLen <= 0.0) return start

            val t = ((wrapped - segStart) / segLen).coerceIn(0.0, 1.0)
            val x = start.x + (end.x - start.x) * t
            val z = start.z + (end.z - start.z) * t

            return BlockPos(x.roundToInt(), start.y, z.roundToInt())
        }
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
