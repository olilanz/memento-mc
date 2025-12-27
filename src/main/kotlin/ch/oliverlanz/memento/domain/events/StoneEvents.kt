package ch.oliverlanz.memento.domain.events

/**
 * Event triggered when a stone matures.
 * @param stoneName The name of the matured stone.
 */
data class StoneMatured(val stoneName: String)

/**
 * Event triggered when a stone is removed.
 * @param stoneName The name of the removed stone.
 */
data class StoneRemoved(val stoneName: String)

/**
 * Event triggered when a renewal batch is completed.
 * @param stoneName The name of the stone associated with the completed batch.
 */
data class RenewalBatchCompleted(val stoneName: String)