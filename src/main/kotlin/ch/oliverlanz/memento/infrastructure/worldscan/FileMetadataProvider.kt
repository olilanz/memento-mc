package ch.oliverlanz.memento.infrastructure.worldscan

import ch.oliverlanz.memento.infrastructure.worldscan.WorldDiscoveryPlan

/**
 * Infrastructure-owned file metadata provider lifecycle.
 *
 * Contract:
 * - input is discovery work from [WorldDiscoveryPlan]
 * - provider runs off-thread
 * - provider owns exactly one pass over discovered work units
 * - completion is terminal (operator may rerun scan explicitly)
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
    CANCELLED,
    COMPLETE,
}

data class FileMetadataProviderStatus(
    val lifecycle: FileMetadataProviderLifecycle = FileMetadataProviderLifecycle.IDLE,
    val totalWorkUnits: Int = 0,
    val processedWorkUnits: Int = 0,
    val emittedFacts: Int = 0,
)
