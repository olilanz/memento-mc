package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.infrastructure.async.GlobalAsyncExclusionGate
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import java.util.concurrent.Callable
import java.util.concurrent.Future

/**
 * Execution seam for projection worker scheduling.
 *
 * Runtime default delegates to the global async exclusion gate.
 * Tests may inject deterministic strategies while preserving projection policy logic.
 */
interface ProjectionExecutionStrategy {
    sealed interface SubmitResult<T> {
        data class Accepted<T>(val future: Future<T>) : SubmitResult<T>

        data class Busy<T>(val activeOwner: String) : SubmitResult<T>
    }

    fun <T> submit(
        concept: MementoConcept,
        owner: String,
        task: Callable<T>,
    ): SubmitResult<T>
}

object GlobalGateProjectionExecutionStrategy : ProjectionExecutionStrategy {
    override fun <T> submit(
        concept: MementoConcept,
        owner: String,
        task: Callable<T>,
    ): ProjectionExecutionStrategy.SubmitResult<T> {
        return when (val result = GlobalAsyncExclusionGate.submitIfIdle(concept = concept, owner = owner) { task }) {
            is GlobalAsyncExclusionGate.SubmitResult.Accepted -> ProjectionExecutionStrategy.SubmitResult.Accepted(result.future)
            is GlobalAsyncExclusionGate.SubmitResult.Busy -> ProjectionExecutionStrategy.SubmitResult.Busy(result.activeOwner)
        }
    }
}

