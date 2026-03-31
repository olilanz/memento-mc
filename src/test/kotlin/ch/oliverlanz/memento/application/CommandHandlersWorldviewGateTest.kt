package ch.oliverlanz.memento.application

import ch.oliverlanz.memento.domain.harness.DomainTestHarness
import ch.oliverlanz.memento.domain.harness.fixtures.WorldFixtureBuilder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandHandlersWorldviewGateTest {

    @AfterTest
    fun teardown() {
        CommandHandlers.detachRenewalProjection()
        CommandHandlers.detachWorldScanner()
    }

    @Test
    fun t4_worldviewAvailability_line_isUnavailable_untilProjectionComplete() {
        val harness = DomainTestHarness()
        val world = WorldFixtureBuilder.overworld()

        val model = WorldFixtureBuilder()
            .linearRegions(
                world = world,
                startRegionX = -400,
                count = 16,
                regionZ = 0,
                inhabitedTimeTicks = 0L,
                scanTickStart = 1L,
            )
            .build()

        CommandHandlers.attachRenewalProjection(harness.projection())

        val beforeProjection = CommandHandlers.testOnlyExplainWorldLines()
        assertEquals(
            true,
            beforeProjection.any { it == "Worldview availability: unavailable (requires scan complete + projection complete)" },
            "explain-world must report worldview unavailable while projection completeness is false",
        )
        assertEquals(
            "Worldview availability: unavailable (requires scan complete + projection complete)",
            CommandHandlers.testOnlyRenewalWorldviewAvailabilityLine(),
            "explain-renewal must use the same strict worldview gate before projection completeness",
        )

        harness.ingest(model)
        harness.runUntilIdle(maxCycles = 64)

        val afterProjection = CommandHandlers.testOnlyExplainWorldLines()
        assertEquals(
            false,
            afterProjection.any { it == "Worldview availability: unavailable (requires scan complete + projection complete)" },
            "explain-world must stop reporting unavailable once projection completeness is true",
        )
        assertEquals(
            null,
            CommandHandlers.testOnlyRenewalWorldviewAvailabilityLine(),
            "explain-renewal strict worldview gate must clear once projection completeness is true",
        )
    }
}
