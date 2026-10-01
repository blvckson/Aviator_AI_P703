package com.example.aviatorai

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

data class DataProfile(
    val count: Int,
    val median: Double,
    val mad: Double,
    val q25: Double,
    val q75: Double,
    val meanLog: Double,
    val slope: Double
)

class DataAnalyzer {

    fun profile(data: List<Double>): DataProfile? {
        val clean = data.filter { it.isFinite() && it >= 1.0 && it <= 10000.0 }
        if (clean.size < 5) return null

        val sorted = clean.sorted()
        val median = median(sorted)
        val deviations = sorted.map { abs(it - median) }.sorted()
        val mad = median(deviations).coerceAtLeast(0.0001)
        val q25 = quantile(sorted, 0.25)
        val q75 = quantile(sorted, 0.75)

        val logs = clean.map { ln(it) }
        val meanLog = logs.average()

        val n = logs.size
        val xMean = (0 until n).average()
        val yMean = logs.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dx = i - xMean
            num += dx * (logs[i] - yMean)
            den += dx * dx
        }
        val slope = if (den > 0.0) num / den else 0.0

        return DataProfile(
            count = clean.size,
            median = median,
            mad = mad,
            q25 = q25,
            q75 = q75,
            meanLog = meanLog,
            slope = slope
        )
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val m = values.size / 2
        return if (values.size % 2 == 0) {
            (values[m - 1] + values[m]) / 2.0
        } else values[m]
    }

    private fun quantile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val pos = p.coerceIn(0.0, 1.0) * (values.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, values.lastIndex)
        val f = pos - lo
        return values[lo] + (values[hi] - values[lo]) * f
    }
}
