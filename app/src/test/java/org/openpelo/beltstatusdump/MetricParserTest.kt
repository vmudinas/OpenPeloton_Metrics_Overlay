package org.openpelo.beltstatusdump

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricParserTest {
    @Test
    fun parsesSpeedAndInclineCandidates() {
        val parsed = MetricParser.parse(
            mapOf(
                "current_speed" to 6.0,
                "target_incline" to 3.5f,
            ),
            SpeedUnit.MPH,
        )

        assertEquals(2.68224, parsed.speedMetersPerSecond ?: 0.0, 0.00001)
        assertEquals(3.5, parsed.inclinePercent ?: 0.0, 0.00001)
    }

    @Test
    fun rejectsImpossibleSpeed() {
        val parsed = MetricParser.parse(mapOf("speed" to 1000.0), SpeedUnit.MPH)

        assertNull(parsed.speedMetersPerSecond)
    }

    @Test
    fun parsesMetricsEmbeddedInObjectText() {
        val parsed = MetricParser.parse(
            mapOf("belt_status" to "BeltState(speed=5.5, incline=2.0)"),
            SpeedUnit.MPH,
        )

        assertEquals(2.45872, parsed.speedMetersPerSecond ?: 0.0, 0.00001)
        assertEquals(2.0, parsed.inclinePercent ?: 0.0, 0.00001)
    }
}
