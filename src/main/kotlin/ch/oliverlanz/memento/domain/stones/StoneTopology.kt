package ch.oliverlanz.memento.domain.stones

import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.WitherstoneStateTransition
import ch.oliverlanz.memento.domain.events.WitherstoneTransitionTrigger
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
        evaluate(trigger = WitherstoneTransitionTrigger.SERVER_START)

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
    // Mutations (commands + lifecycle triggers)
    // ---------------------------------------------------------------------

    fun addOrReplaceWitherstone(
        name: String,
        dimension: RegistryKey<World>,
        position: BlockPos,
        radius: Int,
        daysToMaturity: Int,
        trigger: WitherstoneTransitionTrigger,
    ) {
        requireInitialized()

        val existing = stones[name] as? Witherstone
        val stone = existing ?: Witherstone(
            name = name,
            dimension = dimension,
            position = position,
            daysToMaturity = MementoConstants.DEFAULT_DAYS_TO_MATURITY,
            state = WitherstoneState.MATURING,
        )

        // Apply updates (shadow-only; explicit, deterministic)
        stone.radius = radius
        stone.daysToMaturity = daysToMaturity

        // Allow commands to replace dimension/position deterministically.
        // (No gameplay impact yet; observational only.)
        val replaced = Witherstone(
            name = stone.name,
            dimension = dimension,
            position = position,
            daysToMaturity = stone.daysToMaturity,
            state = stone.state,
        ).also { it.radius = stone.radius }

        stones[name] = replaced

        // Operator may force maturity by setting daysToMaturity <= 0.
        evaluate(trigger = trigger)

        persist()

        // If the witherstone is already matured, its derived renewal intent must reflect the latest topology
        // (including Lorestone protection). This keeps batch shape deterministic under operator edits.
        val updated = stones[name] as? Witherstone
        if (updated != null && updated.state == WitherstoneState.MATURED) {
            reconcileRenewalIntentFor(updated, reason = "witherstone_upsert")
        }
    }

    fun addOrReplaceLorestone(
        name: String,
        dimension: RegistryKey<World>,
        position: BlockPos,
        radius: Int,
    ) {
        requireInitialized()

        stones[name] = Lorestone(name = name, dimension = dimension, position = position).also { it.radius = radius }
        persist()

        // Lorestone applies immediately: derived renewal intent must reflect the updated topology.
        val lore = stones[name] as? Lorestone ?: return
        reconcileMaturedWitherstonesAffectedByLorestone(lore, reason = "lorestone_upsert")
    }

    fun remove(name: String) {
        requireInitialized()

        val removed = stones.remove(name)
        persist()

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
    fun advanceDays(days: Int, trigger: WitherstoneTransitionTrigger) {
        requireInitialized()
        require(days >= 0) { "days must be >= 0" }
        repeat(days) { ageOnce(trigger) }
    }

    fun ageOnce(trigger: WitherstoneTransitionTrigger) {
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
    fun evaluate(trigger: WitherstoneTransitionTrigger) {
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
            trigger = WitherstoneTransitionTrigger.RENEWAL_COMPLETED,
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

        val out = LinkedHashSet<ChunkPos>()
        for (dx in -r..r) {
            for (dz in -r..r) {
                val candidate = ChunkPos(center.x + dx, center.z + dz)
                val protected = lorestones.any { StoneSpatial.containsChunk(it, candidate) }
                if (!protected) out.add(candidate)
            }
        }
        return out
    }

    // ---------------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------------

    private fun transition(stone: Witherstone, to: WitherstoneState, trigger: WitherstoneTransitionTrigger) {
        val from = stone.state
        if (from == to) return

        stone.state = to

        StoneDomainEvents.publish(
            WitherstoneStateTransition(
                stoneName = stone.name,
                dimension = stone.dimension,
                position = stone.position,
                from = from,
                to = to,
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
