package ch.oliverlanz.memento.domain.harness

import ch.oliverlanz.memento.domain.renewal.projection.ProjectionCommitCompleted
import ch.oliverlanz.memento.domain.renewal.projection.ProjectionCycleDriver
import ch.oliverlanz.memento.domain.renewal.projection.ProjectionDispatchStart
import ch.oliverlanz.memento.domain.renewal.projection.ProjectionExecutionStrategy
import ch.oliverlanz.memento.domain.renewal.projection.RenewalProjection
import ch.oliverlanz.memento.domain.renewal.projection.RenewalPublishedView
import ch.oliverlanz.memento.domain.worldmap.ChunkMetadataFact
import ch.oliverlanz.memento.domain.worldmap.WorldMapService
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture

class DomainTestHarness {
    private val worldMapService = WorldMapService()
    private val projection = RenewalProjection()
    private val recordingDriver = RecordingProjectionCycleDriver()

    init {
        projection.attach(worldMapService)
        projection.setProjectionExecutionStrategy(SynchronousProjectionExecutionStrategy)
        projection.setProjectionCycleDriver(recordingDriver)
    }

    fun ingest(model: TestWorldModel) {
        model.chunks.forEach { fact ->
            projection.observeFactApplied(
                ChunkMetadataFact(
                    key = fact.key,
                    source = fact.source,
                    unresolvedReason = fact.unresolvedReason,
                    signals = fact.signals,
                    dominantStone = fact.dominantStone,
                    dominantStoneEffect = fact.dominantStoneEffect,
                    scanTick = fact.scanTick,
                )
            )
        }
    }

    fun runMediumCycle() {
        projection.onMediumPulse()
        projection.tick()
    }

    fun runUntilIdle(maxCycles: Int = 16) {
        for (cycle in 0 until maxCycles) {
            runMediumCycle()
            if (!projection.hasPendingChanges()) return
        }
    }

    fun committedView(): RenewalPublishedView? = projection.publishedViewOrNull()

    fun projection(): RenewalProjection = projection

    fun commits(): List<ProjectionCommitCompleted> = recordingDriver.commits.toList()

    fun dispatches(): List<ProjectionDispatchStart> = recordingDriver.dispatches.toList()

    private object SynchronousProjectionExecutionStrategy : ProjectionExecutionStrategy {
        override fun <T> submit(
            concept: MementoConcept,
            owner: String,
            task: Callable<T>,
        ): ProjectionExecutionStrategy.SubmitResult<T> {
            return try {
                val out = task.call()
                ProjectionExecutionStrategy.SubmitResult.Accepted(CompletableFuture.completedFuture(out))
            } catch (t: Throwable) {
                val failed = CompletableFuture<T>()
                failed.completeExceptionally(t)
                ProjectionExecutionStrategy.SubmitResult.Accepted(failed)
            }
        }
    }

    private class RecordingProjectionCycleDriver : ProjectionCycleDriver {
        val dispatches = mutableListOf<ProjectionDispatchStart>()
        val commits = mutableListOf<ProjectionCommitCompleted>()

        override fun onDispatchStart(boundary: ProjectionDispatchStart) {
            dispatches += boundary
        }

        override fun onCommitCompleted(boundary: ProjectionCommitCompleted) {
            commits += boundary
        }
    }
}
