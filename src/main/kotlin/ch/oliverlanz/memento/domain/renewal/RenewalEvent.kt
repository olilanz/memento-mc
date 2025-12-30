package ch.oliverlanz.memento.domain.renewal

sealed interface RenewalEvent {
    data class BatchRegistered(val name: String) : RenewalEvent
    data class UnloadAttemptBlocked(val name: String) : RenewalEvent
    data class AllChunksUnloaded(val name: String) : RenewalEvent
    data class RenewalStarted(val name: String) : RenewalEvent
    data class RenewalProgressed(val name: String, val chunksThisTick: Int) : RenewalEvent
    data class RenewalCompleted(val name: String) : RenewalEvent
}
