package ch.oliverlanz.memento.domain.stones

import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.WitherstoneStateTransition
import ch.oliverlanz.memento.domain.events.WitherstoneTransitionTrigger
import ch.oliverlanz.memento.infrastructure.MementoConstants
import ch.oliverlanz.memento.infrastructure.StoneRegisterPersistence
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

/**
 * New-generation stone register (shadow).
 *
 * Invariants:
 * - Non-authoritative (observational only).
 * - No dependency on legacy stone types.
 * - Lifecycle transitions are explicit and observable via structured events.
 * - Persistence is overwrite-on-change (no dirty state by design).
 */
object StoneRegister {

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

        val loaded = StoneRegisterPersistence.load(server)

        stones.clear()
        for (s in loaded) {
            // Enforce name uniqueness: ignore duplicates on disk (first wins).
            if (!stones.containsKey(s.name)) stones[s.name] = s
        }

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
    }

    fun remove(name: String) {
        requireInitialized()
        stones.remove(name)
        persist()
    }

    /**
     * Called once per day rollover (nightly checkpoint).
     *
     * The register does not own time; this is an explicit poke from the application layer.
     * Mechanical behavior:
     * - Decrement daysToMaturity for MATURING Witherstones
     * - Evaluate maturity transitions
     */

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
        StoneRegisterPersistence.save(s, stones.values.toList())
    }

    private fun requireInitialized() {
        check(initialized) { "StoneRegister not attached. Call StoneRegister.attach(server) on SERVER_STARTED." }
    }
}
