package ch.oliverlanz.memento.domain.renewal.projection

/**
 * Projection execution-boundary seam for deterministic test orchestration.
 *
 * This interface has no policy authority. It only observes cycle boundaries.
 */
interface ProjectionCycleDriver {
    fun onDispatchStart(boundary: ProjectionDispatchStart)

    fun onCommitCompleted(boundary: ProjectionCommitCompleted)
}

object NoOpProjectionCycleDriver : ProjectionCycleDriver {
    override fun onDispatchStart(boundary: ProjectionDispatchStart) = Unit

    override fun onCommitCompleted(boundary: ProjectionCommitCompleted) = Unit
}

