package ch.oliverlanz.memento.domain.harness.fixtures

import ch.oliverlanz.memento.domain.worldmap.WorldAnalysisLifecycleBoundary
import ch.oliverlanz.memento.domain.worldmap.WorldAnalysisReadiness
import ch.oliverlanz.memento.domain.worldmap.WorldAnalysisReadinessInput

/**
 * Test-only input fixture for L1 visibility semantics.
 *
 * This fixture intentionally models explain-world input shape only.
 * It is not a lifecycle boundary implementation.
 */
data class WorldAnalysisVisibilityFixture(
    val discoveredUniverseCount: Int,
    val knownCount: Int,
    val uncertainCount: Int,
    val scanComplete: Boolean,
    val readiness: WorldAnalysisReadiness,
) {
    init {
        if (discoveredUniverseCount < 0) throw IllegalArgumentException("discoveredUniverseCount must be >= 0")
        if (knownCount < 0) throw IllegalArgumentException("knownCount must be >= 0")
        if (uncertainCount < 0) throw IllegalArgumentException("uncertainCount must be >= 0")
    }

    /**
     * Defect-prone legacy inference used by the current failing anti-invariant test:
     * completeness inferred from counts alone.
     */
    fun legacyCountsOnlyComplete(): Boolean {
        return knownCount == discoveredUniverseCount && uncertainCount == 0
    }

    /**
     * Correct semantic rule for later slices: readiness must participate in completeness.
     */
    fun readinessAwareComplete(): Boolean {
        return legacyCountsOnlyComplete() && scanComplete
    }

    /**
     * L2 lifecycle-boundary completeness evaluation.
     */
    fun boundaryComplete(): Boolean {
        return WorldAnalysisLifecycleBoundary.evaluate(
            WorldAnalysisReadinessInput(
                discoveredUniverseCount = discoveredUniverseCount,
                knownCount = knownCount,
                uncertainCount = uncertainCount,
                scanComplete = scanComplete,
                readiness = readiness,
            )
        ).complete
    }
}
