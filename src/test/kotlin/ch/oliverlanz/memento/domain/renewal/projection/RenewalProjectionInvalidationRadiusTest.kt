package ch.oliverlanz.memento.domain.renewal.projection

import kotlin.test.Test
import kotlin.test.assertEquals

class RenewalProjectionInvalidationRadiusTest {

    @Test
    fun dependency_radius_is_sum_of_signal_and_halo_radii() {
        assertEquals(4, RenewalProjection.dependencyRadiusChunksForInvalidationForTesting())
    }

    @Test
    fun affected_region_ring_is_ceiling_of_dependency_radius_over_region_size() {
        assertEquals(1, RenewalProjection.affectedExpansionRingRegionsForTesting())
    }

    @Test
    fun ring_mapping_uses_ceiling_behavior() {
        assertEquals(2, RenewalProjection.regionRingForDependencyRadiusChunksForTesting(33))
    }
}

