package ch.oliverlanz.memento.infrastructure.worldscan

import ch.oliverlanz.memento.application.worldscan.WorldDiscoveryPlan

/**
 * Infrastructure-owned file metadata provider lifecycle.
 *
 * Chunk C contract:
 * - input is discovery work from [WorldDiscoveryPlan]
 * - provider runs off-thread
 * - provider owns exactly two passes (immediate + one delayed transient reconciliation)
 * - completion is terminal (no further retries)
 */
interface FileMetadataProvider : AutoCloseable {
    /**
     * Starts a new provider run if idle/complete.
     *
     * Returns false if a run is already active.
     */
    fun start(plan: WorldDiscoveryPlan, scanTick: Long): Boolean

    /** Lifecycle/status snapshot suitable for orchestration. */
    fun status(): FileMetadataProviderStatus

    /** True once the provider reached terminal completion for its current run. */
    fun isComplete(): Boolean
}

enum class FileMetadataProviderLifecycle {
    IDLE,
    RUNNING,
    COMPLETE,
}

data class FileMetadataProviderStatus(
    val lifecycle: FileMetadataProviderLifecycle = FileMetadataProviderLifecycle.IDLE,
    val firstPassTotal: Int = 0,
    val firstPassProcessed: Int = 0,
    val secondPassTotal: Int = 0,
    val secondPassProcessed: Int = 0,
    val emittedFacts: Int = 0,
)

