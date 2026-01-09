package ch.oliverlanz.memento.application.stone

import ch.oliverlanz.memento.domain.events.GameDayAdvanced
import ch.oliverlanz.memento.domain.events.GameTimeDomainEvents
import ch.oliverlanz.memento.domain.stones.StoneTopologyHooks

/**
 * Application bridge: drives stone maturity from semantic time events.
 *
 * This keeps server ticks and world-time access out of domain logic.
 */
object StoneMaturityTimeBridge {

    fun attach() {
        GameTimeDomainEvents.subscribeToDayAdvanced(::onDayAdvanced)
    }

    fun detach() {
        GameTimeDomainEvents.unsubscribeFromDayAdvanced(::onDayAdvanced)
    }

    private fun onDayAdvanced(event: GameDayAdvanced) {
        // Drive maturity progression using the same checkpoint semantics as before.
        StoneTopologyHooks.onNightlyCheckpoint(event.deltaDays)
    }
}
