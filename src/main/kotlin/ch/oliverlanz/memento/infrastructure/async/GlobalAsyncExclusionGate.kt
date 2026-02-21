package ch.oliverlanz.memento.infrastructure.async

import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Global exclusion gate for background jobs across scanner/projection domains.
 *
 * Doctrine lock:
 * - one background job at a time globally,
 * - reject while busy,
 * - no fairness/priority/queue semantics.
 */
object GlobalAsyncExclusionGate {
    sealed interface SubmitResult<out T> {
        data class Accepted<T>(
            val future: Future<T>,
            val owner: String,
        ) : SubmitResult<T>

        data class Busy(
            val activeOwner: String,
        ) : SubmitResult<Nothing>
    }

    private data class ActiveJob(
        val owner: String,
        val future: Future<*>,
    )

    private var executor: ExecutorService? = null
    private var activeJob: ActiveJob? = null

    @Synchronized
    fun attach() {
        if (executor != null) return
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "memento-global-async-gate").apply { isDaemon = true }
        }
        MementoLog.info(MementoConcept.WORLD, "global async gate attached")
    }

    @Synchronized
    fun detach() {
        executor?.shutdownNow()
        executor = null
        activeJob = null
        MementoLog.info(MementoConcept.WORLD, "global async gate detached")
    }

    @Synchronized
    fun <T> submitIfIdle(
        concept: MementoConcept,
        owner: String,
        taskFactory: () -> Callable<T>,
    ): SubmitResult<T> {
        val active = activeJob
        if (active != null && !active.future.isDone) {
            MementoLog.debug(concept, "global async gate busy owner={} activeOwner={}", owner, active.owner)
            return SubmitResult.Busy(active.owner)
        }
        if (active != null && active.future.isDone) {
            activeJob = null
        }

        val exec = executor ?: return SubmitResult.Busy("detached")
        val callable = taskFactory()
        val future = exec.submit(callable)
        activeJob = ActiveJob(owner = owner, future = future)
        MementoLog.debug(concept, "global async gate accepted owner={}", owner)
        return SubmitResult.Accepted(future = future, owner = owner)
    }
}

