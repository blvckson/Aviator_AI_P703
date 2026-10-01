package com.example.aviatorai

import org.apache.commons.math3.stat.regression.SimpleRegression
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class PredictionEstimate(
    val value: Double,
    val lower: Double,
    val upper: Double,
    val confidence: Double,
    val sampleSize: Int
)

class PredictionModel {

    private val analyzer = DataAnalyzer()

    fun estimate(data: List<Double>): PredictionEstimate? {
        val clean = data.filter { it.isFinite() && it >= 1.0 && it <= 10000.0 }
        if (clean.size < 10) return null

        val profile = analyzer.profile(clean) ?: return null

        val regression = SimpleRegression(true)
        val start = max(0, clean.size - 60)
        for (i in start until clean.size) {
            regression.addData((i - start).toDouble(), ln(clean[i]))
        }

        val nextIndex = (clean.size - start).toDouble()
        val regressionLog = if (regression.n >= 3 && regression.slope.isFinite()) {
            regression.predict(nextIndex)
        } else {
            profile.meanLog
        }

        val robustLog = ln(profile.median)
        val trendWeight = min(0.35, max(0.0, regression.r * regression.r))
        val combinedLog = robustLog * (1.0 - trendWeight) + regressionLog * trendWeight

        val point = exp(combinedLog).coerceIn(1.01, 10000.0)

        val spread = max(
            profile.mad * 1.4826,
            (profile.q75 - profile.q25) / 1.349
        ).coerceAtLeast(0.05)

        val lower = max(1.0, point - 1.28 * spread)
        val upper = min(10000.0, point + 1.28 * spread)

        val stability = 1.0 / (1.0 + spread / max(profile.median, 0.01))
        val sampleFactor = min(1.0, clean.size / 100.0)
        val confidence = (100.0 * stability * sampleFactor).coerceIn(0.0, 99.0)

        return PredictionEstimate(
            value = point,
            lower = lower,
            upper = upper,
            confidence = confidence,
            sampleSize = clean.size
        )
    }
}
