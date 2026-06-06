package org.openpelo.beltstatusdump

import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceAccumulatorTest {
    @Test
    fun integratesAverageSpeedAcrossSamples() {
        val accumulator = DistanceAccumulator()
        accumulator.update(MetricsSnapshot(0L, 2.0, 0.0))
        accumulator.update(MetricsSnapshot(1_000_000_000L, 4.0, 0.0))

        assertEquals(3.0, accumulator.distanceMeters, 0.0001)
    }

    @Test
    fun ignoresLongEventGaps() {
        val accumulator = DistanceAccumulator(maximumGapSeconds = 5.0)
        accumulator.update(MetricsSnapshot(0L, 3.0, 0.0))
        accumulator.update(MetricsSnapshot(6_000_000_000L, 3.0, 0.0))

        assertEquals(0.0, accumulator.distanceMeters, 0.0001)
    }

    @Test
    fun breakContinuityPreventsPausedDistance() {
        val accumulator = DistanceAccumulator()
        accumulator.update(MetricsSnapshot(0L, 3.0, 0.0))
        accumulator.breakContinuity()
        accumulator.update(MetricsSnapshot(3_000_000_000L, 3.0, 0.0))

        assertEquals(0.0, accumulator.distanceMeters, 0.0001)
    }
}
