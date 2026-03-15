package ch.oliverlanz.memento.domain.worldmap

/**
 * Readiness model for world-analysis visibility and completeness semantics.
 *
 * This boundary owns completeness evaluation semantics and keeps them explicit,
 * avoiding implicit inference from map counts alone.
 */
enum class WorldAnalysisReadiness {
    UNINITIALIZED,
    DISCOVERED,
    SCANNED,
    ANALYZED,
}

data class WorldAnalysisReadinessInput(
    val discoveredUniverseCount: Int,
    val knownCount: Int,
    val uncertainCount: Int,
    val scanComplete: Boolean,
    val readiness: WorldAnalysisReadiness,
)

data class WorldAnalysisReadinessView(
    val readiness: WorldAnalysisReadiness,
    val discoveredUniverseCount: Int,
    val knownCount: Int,
    val uncertainCount: Int,
    val scanComplete: Boolean,
    val totalsMatchDiscoveredUniverse: Boolean,
    val complete: Boolean,
)

object WorldAnalysisLifecycleBoundary {
    fun evaluate(input: WorldAnalysisReadinessInput): WorldAnalysisReadinessView {
        val totalsMatch =
            input.knownCount >= 0 &&
                input.uncertainCount >= 0 &&
                (input.knownCount + input.uncertainCount) == input.discoveredUniverseCount

        val readinessAllowsCompleteness =
            input.readiness == WorldAnalysisReadiness.SCANNED ||
                input.readiness == WorldAnalysisReadiness.ANALYZED

        val complete = totalsMatch && input.uncertainCount == 0 && input.scanComplete && readinessAllowsCompleteness

        return WorldAnalysisReadinessView(
            readiness = input.readiness,
            discoveredUniverseCount = input.discoveredUniverseCount,
            knownCount = input.knownCount,
            uncertainCount = input.uncertainCount,
            scanComplete = input.scanComplete,
            totalsMatchDiscoveredUniverse = totalsMatch,
            complete = complete,
        )
    }
}

