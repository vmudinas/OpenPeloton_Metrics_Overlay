package org.openpelo.beltstatusdump

import android.os.Bundle
import kotlin.math.max

enum class SpeedUnit {
    MPH,
    KPH,
    METERS_PER_SECOND;

    fun toMetersPerSecond(value: Double): Double = when (this) {
        MPH -> value * 0.44704
        KPH -> value / 3.6
        METERS_PER_SECOND -> value
    }

    companion object {
        fun fromOrdinal(value: Int): SpeedUnit = entries.getOrElse(value) { MPH }
    }
}

data class ParsedMetrics(
    val speedMetersPerSecond: Double?,
    val inclinePercent: Double?,
    val rawDump: String,
)

data class MetricsSnapshot(
    val elapsedRealtimeNanos: Long,
    val speedMetersPerSecond: Double,
    val inclinePercent: Double,
)

object MetricParser {
    fun parse(extras: Bundle?, speedUnit: SpeedUnit): ParsedMetrics {
        if (extras == null) {
            return ParsedMetrics(null, null, "<no extras>")
        }

        val values = mutableMapOf<String, Any?>()
        flattenBundle(extras, "", values)
        return parse(values, speedUnit)
    }

    fun parse(values: Map<String, Any?>, speedUnit: SpeedUnit): ParsedMetrics {
        var speed: Double? = null
        var incline: Double? = null
        val lines = mutableListOf<String>()

        for (key in values.keys.sorted()) {
            val value = values[key]
            val numericValue = value.asDoubleOrNull()
            val lowerKey = key.lowercase()
            lines += "$key = $value (${value?.javaClass?.simpleName ?: "null"})"

            if (speed == null && lowerKey.contains("speed") && numericValue != null) {
                speed = speedUnit.toMetersPerSecond(numericValue)
            }
            if (
                incline == null &&
                (lowerKey.contains("incline") || lowerKey.contains("grade")) &&
                numericValue != null
            ) {
                incline = numericValue
            }
        }

        val rawDump = lines.ifEmpty { listOf("<no extras>") }.joinToString("\n")
        if (speed == null) {
            speed = extractNamedNumber(rawDump, listOf("speed", "velocity"))
                ?.let(speedUnit::toMetersPerSecond)
        }
        if (incline == null) {
            incline = extractNamedNumber(rawDump, listOf("incline", "grade"))
        }

        return ParsedMetrics(
            speedMetersPerSecond = speed?.takeIf { it.isFinite() && it in 0.0..30.0 },
            inclinePercent = incline?.takeIf { it.isFinite() && it in -20.0..50.0 },
            rawDump = rawDump,
        )
    }

    private fun flattenBundle(
        bundle: Bundle,
        prefix: String,
        output: MutableMap<String, Any?>,
    ) {
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION")
            val value = bundle.get(key)
            val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
            if (value is Bundle) {
                flattenBundle(value, fullKey, output)
            } else {
                output[fullKey] = value
            }
        }
    }

    private fun extractNamedNumber(text: String, names: List<String>): Double? {
        for (name in names) {
            val match = Regex(
                """\b$name\b\s*[:=]\s*(-?\d+(?:\.\d+)?)""",
                RegexOption.IGNORE_CASE,
            ).find(text)
            if (match != null) {
                return match.groupValues[1].toDoubleOrNull()
            }
        }
        return null
    }

    private fun Any?.asDoubleOrNull(): Double? = when (this) {
        is Number -> toDouble()
        is String -> trim().toDoubleOrNull()
        else -> null
    }
}

class DistanceAccumulator(
    private val maximumGapSeconds: Double = 5.0,
) {
    private var previous: MetricsSnapshot? = null

    var distanceMeters: Double = 0.0
        private set

    fun reset(distanceMeters: Double = 0.0) {
        this.distanceMeters = max(0.0, distanceMeters)
        previous = null
    }

    fun breakContinuity() {
        previous = null
    }

    fun update(snapshot: MetricsSnapshot): Double {
        val last = previous
        previous = snapshot
        if (last == null) return distanceMeters

        val deltaSeconds =
            (snapshot.elapsedRealtimeNanos - last.elapsedRealtimeNanos) / 1_000_000_000.0
        if (deltaSeconds <= 0.0 || deltaSeconds > maximumGapSeconds) {
            return distanceMeters
        }

        val averageSpeed =
            (last.speedMetersPerSecond + snapshot.speedMetersPerSecond) / 2.0
        distanceMeters += max(0.0, averageSpeed) * deltaSeconds
        return distanceMeters
    }
}
