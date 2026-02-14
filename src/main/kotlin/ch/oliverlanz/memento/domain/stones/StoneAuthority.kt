package ch.oliverlanz.memento.domain.stones

import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.StoneLifecycleState
import ch.oliverlanz.memento.domain.events.StoneLifecycleTransition
import ch.oliverlanz.memento.domain.events.StoneLifecycleTrigger
import ch.oliverlanz.memento.domain.renewal.StoneRenewalDerivation
import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.infrastructure.StoneAuthorityPersistence
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

    /**
     * New-generation stone register (shadow).
 *
     * Authority:
 * - StoneAuthority owns stone lifecycle state (register, placement, maturity, persistence).
 * - Renewal derivation consumes this lifecycle state through renewal-side orchestration.
 *
     * Invariants:
 * - Does not own world fact mutation.
 * - No dependency on legacy stone types.
 * - Lifecycle transitions are explicit and observable via structured events.
 * - Persistence is overwrite-on-change (no dirty state by design).
 */
object StoneAuthority {

    private val log = ch.oliverlanz.memento.infrastructure.observability.MementoLog

    private val stones = linkedMapOf<String, Stone>()

    /**
     * Derived influence snapshot.
     *
     * This is rebuilt atomically whenever the stone set changes.
     */
    private var influenceTree: StoneInfluenceTree = StoneInfluenceTree.EMPTY

    private var server: MinecraftServer? = null
    private var initialized = false

    /**
     * Initialize once per server start.
     * Loads persisted stones (fault-tolerant: corrupt => empty) and makes the register ready.
     */
    fun attach(server: MinecraftServer) {
        if (initialized) return
        this.server = server
        initialized = true

        val loaded = StoneAuthorityPersistence.load(server)

        log.info(ch.oliverlanz.memento.infrastructure.observability.MementoConcept.STONE, "persistence loaded count={}", loaded.size)

        stones.clear()
        for (s in loaded) {
            // Enforce name uniqueness: ignore duplicates on disk (first wins).
            if (!stones.containsKey(s.name)) stones[s.name] = s
        }

        log.info(ch.oliverlanz.memento.infrastructure.observability.MementoConcept.STONE, "register after load count={}", stones.size)

        // Build derived influence snapshot for the loaded register.
        rebuildInfluenceTree()

        // Note: lifecycle evaluation is orchestrated externally (e.g., server start hook).
        persist()
    }

    fun detach() {
        server = null
        initialized = false
        stones.clear()
        influenceTree = StoneInfluenceTree.EMPTY
    }

    // ---------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------

    fun list(): List<Stone> =
        stones.values.toList()

    fun get(name: String): Stone? =
        stones[name]

    /**
     * Read-only snapshot of current stone influence at chunk granularity.
     *
     * This view is deterministic and updated whenever the stone set changes.
     * Consumers must treat it as immutable.
     */
    fun influenceSnapshot(): StoneInfluenceTree = influenceTree


    // ---------------------------------------------------------------------
    // Mutations (admin / operator alterations)
    // ---------------------------------------------------------------------

    /**
     * Alter a stone's radius in-place.
     *
     * This preserves the stone's identity (name) and does not recreate entities.
     */
    fun alterRadius(name: String, radius: Int, trigger: StoneLifecycleTrigger): Boolean {
        requireInitialized()
        require(radius >= 0) { "radius must be >= 0." }

        val stone = stones[name] ?: return false
        if (stone.radius == radius) return true
        stone.radius = radius
        // Any radius change affects the derived influence snapshot.
        rebuildInfluenceTree()

        // Radius changes can affect derived renewal intent.
        when (stone) {
            is Lorestone -> {
                // Protection area changed; ensure derived intent for all matured witherstones under current topology.
                StoneRenewalDerivation.ensureForAllMaturedWitherstones(reason = "alter_radius_lorestone")
            }
            is Witherstone -> {
                // Influence area changed; ensure derived intent for this stone if it is already matured.
                StoneRenewalDerivation.ensureForMaturedWitherstone(stone.name, reason = "alter_radius_witherstone")
            }
        }

        persist()
        return true
    }

    /**
     * Alter daysToMaturity for a witherstone.
     *
     * If the resulting value reaches 0 (or below), maturity is evaluated immediately.
     */
    fun alterDaysToMaturity(name: String, daysToMaturity: Int, trigger: StoneLifecycleTrigger): AlterDaysResult {
        requireInitialized()
        require(daysToMaturity >= 0) { "daysToMaturity must be >= 0." }

        val stone = stones[name] ?: return AlterDaysResult.NOT_FOUND
        val w = stone as? Witherstone ?: return AlterDaysResult.NOT_SUPPORTED

        if (w.state == WitherstoneState.CONSUMED) return AlterDaysResult.ALREADY_CONSUMED

        w.daysToMaturity = daysToMaturity

        // Ensure immediate lifecycle progression (including daysToMaturity == 0).
        evaluate(trigger = trigger)

        persist()
        return AlterDaysResult.OK
    }

    enum class AlterDaysResult {
        OK,
        NOT_FOUND,
        NOT_SUPPORTED,
        ALREADY_CONSUMED,
    }
    // ---------------------------------------------------------------------
    fun addWitherstone(
        name: String,
        dimension: RegistryKey<World>,
        position: BlockPos,
        radius: Int,
        daysToMaturity: Int,
        trigger: StoneLifecycleTrigger,
    ) {
        requireInitialized()
        require(name.isNotBlank()) { "Stone name must not be blank." }
        require(radius >= 0) { "radius must be >= 0." }
        require(daysToMaturity >= 0) { "daysToMaturity must be >= 0." }
        require(!stones.containsKey(name)) { "A stone named '$name' already exists." }

        val w = Witherstone(
            name = name,
            dimension = dimension,
            position = position,
            daysToMaturity = daysToMaturity,
            state = WitherstoneState.PLACED,
        )
        w.radius = radius
        stones[name] = w

        // Stone set changed: rebuild derived influence snapshot.
        rebuildInfluenceTree()

        // Lifecycle: stones enter existence as PLACED.
        StoneDomainEvents.publish(
            StoneLifecycleTransition(
                stone = w,
                from = null,
                to = StoneLifecycleState.PLACED,
                trigger = trigger,
            )
        )

        // Ensure immediate lifecycle progression (including daysToMaturity == 0).
        evaluate(trigger = trigger)

        persist()
    }

    fun addLorestone(
        name: String,
        dimension: RegistryKey<World>,
        position: BlockPos,
        radius: Int,
    ) {
        requireInitialized()
        require(name.isNotBlank()) { "Stone name must not be blank." }
        require(radius >= 0) { "radius must be >= 0." }
        require(!stones.containsKey(name)) { "A stone named '$name' already exists." }

        val l = Lorestone(
            name = name,
            dimension = dimension,
            position = position,
        )
        l.radius = radius
        stones[name] = l

        // Stone set changed: rebuild derived influence snapshot.
        rebuildInfluenceTree()

        // Lifecycle: stones enter existence as PLACED.
        StoneDomainEvents.publish(
            StoneLifecycleTransition(
                stone = l,
                from = null,
                to = StoneLifecycleState.PLACED,
                trigger = StoneLifecycleTrigger.OP_COMMAND,
            )
        )

        // Lorestone applies immediately: renewal derivation must reflect the updated projection.
        StoneRenewalDerivation.ensureForMaturedWitherstonesAffectedByLorestone(l, reason = "lorestone_created")

        persist()
    }

    // Mutations (commands + lifecycle triggers)
    // ---------------------------------------------------------------------

    fun remove(name: String) {
        requireInitialized()

        val removed = stones.remove(name)

        // Rebuild snapshot eagerly: removal affects dominance for potentially many chunks.
        if (removed != null) rebuildInfluenceTree()
        persist()

        // Unified terminal lifecycle: removal is expressed as CONSUMED.
        when (removed) {
            is Witherstone -> {
                val from = StoneLifecycleState.valueOf(removed.state.name)
                StoneDomainEvents.publish(
                    StoneLifecycleTransition(
                        stone = removed,
                        from = from,
                        to = StoneLifecycleState.CONSUMED,
                        trigger = StoneLifecycleTrigger.MANUAL_REMOVE,
                    )
                )
            }

            is Lorestone -> {
                StoneDomainEvents.publish(
                    StoneLifecycleTransition(
                        stone = removed,
                        from = StoneLifecycleState.PLACED,
                        to = StoneLifecycleState.CONSUMED,
                        trigger = StoneLifecycleTrigger.MANUAL_REMOVE,
                    )
                )
            }

            null -> Unit
        }

        when (removed) {
            is Lorestone -> {
                // Lorestone applies immediately: renewal derivation must reflect the updated projection.
                StoneRenewalDerivation.ensureForMaturedWitherstonesAffectedByLorestone(removed, reason = "lorestone_removed")
            }

            is Witherstone -> {
                // Best-effort cleanup: do not leave an orphaned batch behind.
                StoneRenewalDerivation.onWitherstoneRemoved(removed.name)
            }

            null -> Unit
        }
    }

    /**
     * Advance the register by N days (used for time jumps / catch-up).
     *
     * Emits transitions as stones cross lifecycle boundaries.
     */
    fun advanceDays(days: Int, trigger: StoneLifecycleTrigger) {
        requireInitialized()
        require(days >= 0) { "days must be >= 0" }
        repeat(days) { ageOnce(trigger) }
    }

    fun ageOnce(trigger: StoneLifecycleTrigger) {
        requireInitialized()

        for (s in stones.values) {
            val w = s as? Witherstone ?: continue
            if (w.state == WitherstoneState.MATURING) {
                w.daysToMaturity -= 1
            }
        }

        evaluate(trigger)
        persist()
    }

    /**
     * Explicit evaluation (no time decrement).
     *
     * Used by:
     * - operator edits (daysToMaturity <= 0)
     * - server startup lifecycle evaluation
     * - administrative time adjustments
     */
    fun evaluate(trigger: StoneLifecycleTrigger) {
        requireInitialized()

        val snapshot = stones.values.toList()
        for (s in snapshot) {
            val w = s as? Witherstone ?: continue

            if (w.state == WitherstoneState.MATURED || w.state == WitherstoneState.CONSUMED) continue

            if (w.daysToMaturity <= 0) {
                transition(w, WitherstoneState.MATURED, trigger)
            } else if (w.state == WitherstoneState.PLACED) {
                // Future-proof: if a persisted file contains PLACED, normalize into MATURING.
                transition(w, WitherstoneState.MATURING, trigger)
            }
        }

        // Recompute renewal derivation after evaluation, regardless of trigger source.
        StoneRenewalDerivation.ensureForAllMaturedWitherstones(reason = "evaluate_${trigger}")
    }

    /**
     * Called when RenewalTracker reports a completed batch.
     *
     * Consumption is terminal: the stone is removed from the register.
     */
    fun consume(stoneName: String) {
        requireInitialized()

        val s = stones[stoneName] as? Witherstone ?: return
        if (s.state == WitherstoneState.CONSUMED) return

        transition(
            stone = s,
            to = WitherstoneState.CONSUMED,
            trigger = StoneLifecycleTrigger.RENEWAL_COMPLETED,
        )

        stones.remove(stoneName)

        log.info(ch.oliverlanz.memento.infrastructure.observability.MementoConcept.STONE, 
            "consumed witherstone='{}' trigger={}",
            stoneName,
            StoneLifecycleTrigger.RENEWAL_COMPLETED,
        )

        rebuildInfluenceTree()
        persist()
    }

    // ---------------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------------

    /**
     * Rebuild the immutable derived influence snapshot.
     *
     * This is a mechanical, deterministic computation based on the current stone register.
     * It does not change domain semantics; it makes the existing topology easier to query.
     */
    private fun rebuildInfluenceTree() {
        // Group stones by dimension while preserving register order.
        val byDimension = LinkedHashMap<RegistryKey<World>, MutableList<Stone>>()
        for (s in stones.values) {
            byDimension.getOrPut(s.dimension) { mutableListOf() }.add(s)
        }

        val dimensionsOut = LinkedHashMap<RegistryKey<World>, DimensionInfluence>()

        for ((dimension, dimStones) in byDimension) {
            // Build: stone -> chunks (deterministic chunk order per stone).
            val byStone = LinkedHashMap<String, Set<ChunkPos>>()
            for (s in dimStones) {
                byStone[s.name] = computeInfluenceChunks(s)
            }

            // Build: chunk -> dominant stone kind (Lorestone wins, then Witherstone).
            val dominantByChunk = LinkedHashMap<ChunkPos, kotlin.reflect.KClass<out Stone>>()
            for (s in dimStones) {
                val kind = s::class
                for (chunk in byStone[s.name].orEmpty()) {
                    val existing = dominantByChunk[chunk]
                    if (existing == null) {
                        dominantByChunk[chunk] = kind
                        continue
                    }

                    // Dominance rules are owned here: Lorestone always wins.
                    // Note: we store only the dominant *kind*.
                    if (existing == Lorestone::class) continue
                    if (kind == Lorestone::class) {
                        dominantByChunk[chunk] = kind
                    }
                }
            }

            dimensionsOut[dimension] = DimensionInfluence(
                byStone = byStone,
                dominantByChunk = dominantByChunk,
            )
        }

        influenceTree = StoneInfluenceTree(dimensions = dimensionsOut)
    }

    /**
     * Compute the set of chunks within [stone]'s spatial radius.
     *
     * This uses [StoneSpatial.containsChunk] to stay consistent with existing topology semantics.
     */
    private fun computeInfluenceChunks(stone: Stone): Set<ChunkPos> {
        val center = StoneSpatial.centerChunk(stone)
        val r = stone.radius

        // See deriveEligibleChunksFor: extend by 1 chunk to account for StoneSpatial's margin.
        val search = r + 1

        val out = LinkedHashSet<ChunkPos>()
        for (dx in -search..search) {
            for (dz in -search..search) {
                val candidate = ChunkPos(center.x + dx, center.z + dz)
                if (StoneSpatial.containsChunk(stone, candidate)) out.add(candidate)
            }
        }
        return out
    }

    private fun transition(stone: Witherstone, to: WitherstoneState, trigger: StoneLifecycleTrigger) {
        val from = stone.state
        if (from == to) return

        stone.state = to

        StoneDomainEvents.publish(
            StoneLifecycleTransition(
                stone = stone,
                from = StoneLifecycleState.valueOf(from.name),
                to = StoneLifecycleState.valueOf(to.name),
                trigger = trigger,
            )
        )
    }


    /**
     * Returns the immutable set of chunks influenced by the given stone
     * in its own dimension.
     */
    internal fun getInfluencedChunkSet(stone: Stone): Set<ChunkPos> {
        val dim = stone.dimension
        return influenceTree
            .dimensions[dim]
            ?.byStone
            ?.get(stone.name)
            ?: emptySet()
    }

    /**
     * Returns the immutable map of all influenced chunks and their dominant
     * stone kind for the given dimension.
     */
    internal fun getInfluencedChunkSet(
        dimension: RegistryKey<World>
    ): Map<ChunkPos, kotlin.reflect.KClass<out Stone>> {
        return influenceTree
            .dimensions[dimension]
            ?.dominantByChunk
            ?: emptyMap()
    }
    private fun persist() {
        val s = server ?: return
        StoneAuthorityPersistence.save(s, stones.values.toList())
    }

    private fun requireInitialized() {
        check(initialized) { "StoneAuthority not attached. Call StoneAuthority.attach(server) on SERVER_STARTED." }
    }
}
