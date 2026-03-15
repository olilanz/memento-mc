package ch.oliverlanz.memento.domain.world

import ch.oliverlanz.memento.domain.harness.fixtures.WorldAnalysisVisibilityFixture
import ch.oliverlanz.memento.domain.worldmap.WorldAnalysisReadiness
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldAnalysisLifecycleVisibilityTest {

    private companion object {
        const val DISCOVERED_UNIVERSE = 100
    }

    @Test
    fun completeness_is_allowed_when_totals_match_and_scanIsComplete() {
        val fixture = WorldAnalysisVisibilityFixture(
            discoveredUniverseCount = DISCOVERED_UNIVERSE,
            knownCount = DISCOVERED_UNIVERSE,
            uncertainCount = 0,
            scanComplete = true,
            readiness = WorldAnalysisReadiness.SCANNED,
        )

        assertTrue(
            fixture.boundaryComplete(),
            "completeness should be allowed when totals match and scan readiness is true",
        )
    }

    @Test
    fun completeness_is_false_when_totals_doNotMatch_discoveredUniverse() {
        val fixture = WorldAnalysisVisibilityFixture(
            discoveredUniverseCount = DISCOVERED_UNIVERSE,
            knownCount = 80,
            uncertainCount = 20,
            scanComplete = false,
            readiness = WorldAnalysisReadiness.DISCOVERED,
        )

        assertFalse(
            fixture.legacyCountsOnlyComplete(),
            "counts-only inference must be false when totals do not match discovered universe",
        )

        assertFalse(
            fixture.boundaryComplete(),
            "completeness must be false when totals do not match discovered universe",
        )
    }

    @Test
    fun antiInvariant_countsOnlyCanLookComplete_whileScanReadinessIsFalse() {
        val fixture = WorldAnalysisVisibilityFixture(
            discoveredUniverseCount = DISCOVERED_UNIVERSE,
            knownCount = DISCOVERED_UNIVERSE,
            uncertainCount = 0,
            scanComplete = false,
            readiness = WorldAnalysisReadiness.DISCOVERED,
        )

        // Intentionally captures the defect-prone old inference for L1 evidence.
        assertTrue(
            fixture.legacyCountsOnlyComplete(),
            "anti-invariant setup requires totals-only inference to appear complete",
        )

        // Locked expectation: completeness is invalid until readiness confirms scan completion.
        assertFalse(
            fixture.boundaryComplete(),
            "known==discovered and uncertain==0 must not imply complete when scan readiness is false",
        )
    }

    @Test
    fun failing_regression_countsOnlyInference_mustNotBeAcceptedAsCompleteness() {
        val fixture = WorldAnalysisVisibilityFixture(
            discoveredUniverseCount = DISCOVERED_UNIVERSE,
            knownCount = DISCOVERED_UNIVERSE,
            uncertainCount = 0,
            scanComplete = false,
            readiness = WorldAnalysisReadiness.DISCOVERED,
        )

        // L1 intentional failing test: captures current defect signature.
        assertFalse(
            fixture.boundaryComplete(),
            "regression: counts-only completeness inference is invalid without readiness",
        )
    }
}
