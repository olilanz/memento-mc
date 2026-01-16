package ch.oliverlanz.memento.domain.stones

import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.StoneLifecycleState
import ch.oliverlanz.memento.domain.events.StoneLifecycleTransition
import ch.oliverlanz.memento.domain.events.StoneLifecycleTrigger
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.RenewalTrigger
import ch.oliverlanz.memento.infrastructure.MementoConstants
import ch.oliverlanz.memento.infrastructure.StoneTopologyPersistence
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

/**
 * New-generation stone register (shadow).
 *
 * Authority:
 * - StoneTopology is the sole authority for deriving renewal intent (chunk sets) for matured Witherstones.
 * - The application layer wires events and triggers reconciliation, but never computes spatial eligibility.
 *
 * Invariants:
 * - Non-authoritative (observational only).
 * - No dependency on legacy stone types.
 * - Lifecycle transitions are explicit and observable via structured events.
 * - Persistence is overwrite-on-change (no dirty state by design).
 */
object StoneTopology {

    private val log = org.slf4j.LoggerFactory.getLogger("memento")

    private val stones = linkedMapOf<String, Stone>()

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

        val loaded = StoneTopologyPersistence.load(server)

        log.info("[STONE] persistence loaded count={}", loaded.size)

        stones.clear()
        for (s in loaded) {
            // Enforce name uniqueness: ignore duplicates on disk (first wins).
            if (!stones.containsKey(s.name)) stones[s.name] = s
        }

        log.info("[STONE] register after load count={}", stones.size)

        // Startup reconciliation: persisted state may already indicate maturity.
        evaluate(trigger = StoneLifecycleTrigger.SERVER_START)

        persist()
    }

    fun detach() {
        server = null
        initialized = false
        stones.clear()
    }

    // ---------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------

    fun list(): List<Stone> =
        stones.values.toList()

    fun get(name: String): Stone? =
        stones[name]

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
        stone.radius = radius

        // Radius changes can affect derived renewal intent.
        when (stone) {
            is Lorestone -> {
                // Protection area changed; reconcile all matured witherstones under current topology.
                reconcileAllMaturedWitherstones(reason = "alter_radius_lorestone")
            }
            is Witherstone -> {
                // Influence area changed; reconcile this stone if it is already matured.
                reconcileRenewalIntentForMaturedWitherstone(stone.name, reason = "alter_radius_witherstone")
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

        // Lifecycle: stones enter existence as PLACED.
        StoneDomainEvents.publish(
            StoneLifecycleTransition(
                stone = l,
                from = null,
                to = StoneLifecycleState.PLACED,
                trigger = StoneLifecycleTrigger.OP_COMMAND,
            )
        )

        // Lorestone applies immediately: derived renewal intent must reflect the updated topology.
        reconcileMaturedWitherstonesAffectedByLorestone(l, reason = "lorestone_created")

        persist()
    }

    // Mutations (commands + lifecycle triggers)
    // ---------------------------------------------------------------------

    fun remove(name: String) {
        requireInitialized()

        val removed = stones.remove(name)
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
                // Lorestone applies immediately: derived renewal intent must reflect the updated topology.
                reconcileMaturedWitherstonesAffectedByLorestone(removed, reason = "lorestone_removed")
            }

            is Witherstone -> {
                // Best-effort cleanup: do not leave an orphaned batch behind.
                RenewalTracker.removeBatch(removed.name, trigger = RenewalTrigger.MANUAL)
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
     * - server startup reconciliation
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

        // Derived renewal intent is a function of topology + matured witherstones.
        // Keep the RenewalTracker up to date after every evaluation, regardless of trigger source.
        reconcileAllMaturedWitherstones(reason = "evaluate_${trigger}")
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
        persist()
    }

    // ---------------------------------------------------------------------
    // Renewal intent reconciliation (authoritative)
    // ---------------------------------------------------------------------

    /**
     * Reconcile derived renewal intent for a specific stone, if (and only if) it is a matured Witherstone.
     *
     * Returns true if reconciliation was applied.
     */
    fun reconcileRenewalIntentForMaturedWitherstone(stoneName: String, reason: String): Boolean {
        requireInitialized()
        val w = stones[stoneName] as? Witherstone ?: return false
        if (w.state != WitherstoneState.MATURED) return false
        reconcileRenewalIntentFor(w, reason = reason)
        return true
    }

    /**
     * Reconcile derived renewal intent for all matured Witherstones under the current topology.
     *
     * Returns the number of Witherstones reconciled.
     */
    fun reconcileAllMaturedWitherstones(reason: String): Int {
        requireInitialized()

        val matured = stones.values
            .asSequence()
            .mapNotNull { it as? Witherstone }
            .filter { it.state == WitherstoneState.MATURED }
            .toList()

        for (w in matured) {
            reconcileRenewalIntentFor(w, reason = reason)
        }

        return matured.size
    }

    /**
     * Compute and apply the eligible chunk set for a matured witherstone under the current topology.
     *
     * Lorestone acts as a protection filter: protected chunks are excluded from the derived renewal intent,
     * but non-protected chunks remain eligible even when influences overlap partially.
     */
    private fun reconcileRenewalIntentFor(witherstone: Witherstone, reason: String) {
        val chunks = deriveEligibleChunksFor(witherstone)

        // Replace intent deterministically. Tracker does not care whether this is new or rebuilt.
        RenewalTracker.upsertBatchDefinition(
            name = witherstone.name,
            dimension = witherstone.dimension,
            chunks = chunks,
            trigger = RenewalTrigger.SYSTEM,
        )

        log.info(
            "[STONE] renewal intent reconciled witherstone='{}' reason={} chunks={}",
            witherstone.name,
            reason,
            chunks.size,
        )
    }

    private fun reconcileMaturedWitherstonesAffectedByLorestone(lorestone: Lorestone, reason: String) {
        // Find matured witherstones whose *influence area* overlaps the lorestone (same dimension).
        // Chunk-level protection is applied when computing eligible chunks.
        val affected = stones.values
            .asSequence()
            .mapNotNull { it as? Witherstone }
            .filter { it.dimension == lorestone.dimension }
            .filter { it.state == WitherstoneState.MATURED }
            .filter { StoneSpatial.overlaps(it, lorestone) }
            .toList()

        if (affected.isEmpty()) {
            log.debug(
                "[STONE] lorestone topology change reason={} lorestone='{}' affectedWitherstones=0",
                reason,
                lorestone.name,
            )
            return
        }

        log.info(
            "[STONE] lorestone topology change reason={} lorestone='{}' affectedWitherstones={}",
            reason,
            lorestone.name,
            affected.size,
        )

        for (w in affected) {
            reconcileRenewalIntentFor(w, reason = "lorestone_${reason}")
        }
    }

    private fun deriveEligibleChunksFor(witherstone: Witherstone): Set<ChunkPos> {
        val center = ChunkPos(witherstone.position.x shr 4, witherstone.position.z shr 4)
        val r = witherstone.radius

        val lorestones = stones.values
            .asSequence()
            .mapNotNull { it as? Lorestone }
            .filter { it.dimension == witherstone.dimension }
            .toList()

        // We iterate a bounding square and then apply the exact circle eligibility check in StoneSpatial.
        //
        // Important: because StoneSpatial adds a +12 block margin (to guarantee own-chunk inclusion),
        // the influence circle can slightly exceed `r` chunks in one axis when the stone is near a
        // chunk edge. Extending the bounding square by 1 chunk ensures we never miss eligible chunks.
        val search = r + 1

        val out = LinkedHashSet<ChunkPos>()
        for (dx in -search..search) {
            for (dz in -search..search) {
                val candidate = ChunkPos(center.x + dx, center.z + dz)

                // Exact eligibility: circle in world space against the candidate chunk center.
                if (!StoneSpatial.containsChunk(witherstone, candidate)) continue

                // Protection: lorestones exclude chunks from the renewal batch.
                val protected = lorestones.any { StoneSpatial.containsChunk(it, candidate) }
                if (!protected) out.add(candidate)
            }
        }
        return out
    }

    // ---------------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------------

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

    private fun persist() {
        val s = server ?: return
        StoneTopologyPersistence.save(s, stones.values.toList())
    }

    private fun requireInitialized() {
        check(initialized) { "StoneTopology not attached. Call StoneTopology.attach(server) on SERVER_STARTED." }
    }
}
