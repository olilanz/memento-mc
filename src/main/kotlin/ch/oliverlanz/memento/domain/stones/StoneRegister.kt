package ch.oliverlanz.memento.domain.stones

import ch.oliverlanz.memento.domain.events.StoneMatured
import ch.oliverlanz.memento.domain.events.StoneRemoved
import ch.oliverlanz.memento.domain.events.RenewalBatchCompleted

/**
 * StoneRegister is the authoritative registry for all stones.
 *
 * Responsibilities:
 * - Own stone instances (Stone / Witherstone / Lorestone)
 * - Enforce identity uniqueness (by name)
 * - Own stone lifecycle progression (monotonic)
 * - Emit lifecycle events
 *
 * Notes:
 * - Stones themselves do NOT encode lifecycle state
 * - All mutation is centralized here
 */
object StoneRegister {

    private val stones: MutableMap<String, Stone> = mutableMapOf()

    /**
     * Register a new stone.
     *
     * @throws IllegalArgumentException if a stone with the same name already exists
     */
    fun add(stone: Stone) {
        require(!stones.containsKey(stone.name)) {
            "Stone with name '${stone.name}' is already registered"
        }
        stones[stone.name] = stone
    }

    /**
     * Remove a stone explicitly.
     * Emits a StoneRemoved event if the stone existed.
     */
    fun remove(stoneName: String) {
        val removed = stones.remove(stoneName)
        if (removed != null) {
            emitEvent(StoneRemoved(stoneName))
        }
    }

    /**
     * Advance stone lifecycle based on external time progression.
     *
     * NOTE:
     * - Actual maturity tracking is expected to be added here later
     * - This method currently only demonstrates the event boundary
     */
    fun advanceTime() {
        stones.values.forEach { stone ->
            if (stone is Witherstone) {
                // Lifecycle tracking logic will live here
                // When maturity threshold is reached:
                // emitEvent(StoneMatured(stone.name))
            }
        }
    }

    /**
     * Handle completion of a renewal batch.
     * Consumes the originating stone.
     */
    fun onRenewalBatchCompleted(event: RenewalBatchCompleted) {
        val stone = stones.remove(event.stoneName)
        if (stone != null) {
            emitEvent(StoneRemoved(stone.name))
        }
    }

    /**
     * Expose inspection data for all stones.
     * Used by /memento inspect.
     */
    fun list(): Map<String, Stone> =
        stones.toMap()

    /**
     * Emit a domain event.
     *
     * NOTE:
     * This is intentionally minimal and synchronous.
     * Wiring to the real event system happens elsewhere.
     */
    private fun emitEvent(event: Any) {
        println("[StoneRegister] Event emitted: $event")
        // Forward to existing event bus when wired
    }
}
