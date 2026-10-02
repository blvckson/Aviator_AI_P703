package com.example.aviatorai

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class PredictionEstimate(
    val value: Double,
    val lower: Double,
    val upper: Double,
    val confidence: Double,
    val sampleSize: Int,
    val probabilityAbove2x: Double,
    val probabilityAbove5x: Double,
    val changePoint: Boolean
)

/*
 * Round-level probabilistic model.
 *
 * It deliberately does not use a simple mean.  The estimate combines:
 * - robust log-space location (median/MAD)
 * - recency weighting
 * - Bayesian shrinkage toward the historical log-median
 * - empirical survival probabilities with a Jeffreys prior
 * - an extreme-value tail adjustment for unusually large rounds
 * - a lightweight change-point/stability signal
 * - Monte-Carlo resampling of the validated round dataset for uncertainty
 *
 * This is an uncertainty model over the observed dataset, not a guarantee
 * about a future independent game round.
 */
class PredictionModel {

    fun estimate(data: List<Double>): PredictionEstimate? {
        val clean = data
            .filter { it.isFinite() && it >= 1.0 && it <= 1.0e12 }
            .takeLast(2000)

        if (clean.size < 10) return null

        val logs = clean.map(::ln)
        val sortedLogs = logs.sorted()
        val medianLog = quantile(sortedLogs, 0.50)
        val madLog = median(sortedLogs.map { abs(it - medianLog) }.sorted())
            .coerceAtLeast(0.02)

        val recentStart = max(0, logs.size - 40)
        val recent = logs.subList(recentStart, logs.size)
        val recentMedian = median(recent.sorted())

        // Bayesian shrinkage: recent location is shrunk toward the robust
        // historical location when the dataset is still small/noisy.
        val priorStrength = 12.0
        val posteriorWeight = recent.size / (recent.size + priorStrength)
        var posteriorLog =
            medianLog * (1.0 - posteriorWeight) +
            recentMedian * posteriorWeight

        val changePoint = detectChangePoint(logs)
        if (changePoint) {
            posteriorLog =
                posteriorLog * 0.75 + recentMedian * 0.25
        }

        // Tail behaviour: Hill-style estimate on the largest 10% of values.
        val tail = extremeTailAdjustment(clean)
        posteriorLog += tail

        val point = exp(posteriorLog).coerceIn(1.01, 1.0e12)

        val rng = Random(0x51A71 + clean.size)
        val simulations = ArrayList<Double>(4000)

        val residuals = recent.map { it - recentMedian }
        val residualScale = max(
            madLog * 1.4826,
            median(residuals.map { abs(it) }.sorted()) * 1.4826
        ).coerceAtLeast(0.035)

        repeat(4000) {
            // Recency-biased bootstrap from recent validated rounds.
            val idx = rng.nextInt(recent.size)
            val bootstrapResidual = residuals[idx]
            val jitter = gaussian(rng) * residualScale * 0.18
            val simulatedLog =
                posteriorLog +
                bootstrapResidual * 0.45 +
                jitter
            simulations.add(exp(simulatedLog).coerceIn(1.0, 1.0e12))
        }

        simulations.sort()
        val lower = quantile(simulations, 0.10)
        val upper = quantile(simulations, 0.90)
        val simulatedMedian = quantile(simulations, 0.50)

        val p2 = bayesianSurvival(clean, 2.0)
        val p5 = bayesianSurvival(clean, 5.0)

        val stability =
            1.0 / (1.0 + residualScale * 1.8)

        val sampleFactor =
            (ln(clean.size.toDouble() + 1.0) / ln(101.0)).coerceIn(0.0, 1.0)

        val cpPenalty = if (changePoint) 0.78 else 1.0
        val confidence =
            (100.0 * stability * sampleFactor * cpPenalty)
                .coerceIn(1.0, 99.0)

        return PredictionEstimate(
            value = simulatedMedian,
            lower = lower,
            upper = upper,
            confidence = confidence,
            sampleSize = clean.size,
            probabilityAbove2x = p2,
            probabilityAbove5x = p5,
            changePoint = changePoint
        )
    }

    private fun bayesianSurvival(
        data: List<Double>,
        threshold: Double
    ): Double {
        val successes = data.count { it >= threshold }.toDouble()
        val failures = data.size - successes
        // Jeffreys prior Beta(1/2, 1/2).
        return ((successes + 0.5) /
            (successes + failures + 1.0) * 100.0)
            .coerceIn(0.0, 100.0)
    }

    private fun detectChangePoint(logs: List<Double>): Boolean {
        if (logs.size < 30) return false
        val window = 15
        val before = logs.subList(logs.size - 2 * window, logs.size - window)
        val after = logs.subList(logs.size - window, logs.size)
        val beforeMedian = median(before.sorted())
        val afterMedian = median(after.sorted())
        val pooledMad = median(
            (before + after)
                .map { abs(it - median((before + after).sorted())) }
                .sorted()
        ).coerceAtLeast(0.01)
        return abs(afterMedian - beforeMedian) > max(0.18, pooledMad * 2.8)
    }

    private fun extremeTailAdjustment(data: List<Double>): Double {
        if (data.size < 20) return 0.0
        val sorted = data.sorted()
        val k = max(5, min(sorted.size / 10, 40))
        val threshold = sorted[sorted.size - k - 1]
        if (threshold <= 1.0) return 0.0

        var sum = 0.0
        for (i in sorted.size - k until sorted.size) {
            sum += ln(sorted[i] / threshold)
        }
        val hill = if (sum > 1e-9) k.toDouble() / sum else 0.0

        // Small bounded correction; the model must not let a few extremes
        // dominate the next-round location estimate.
        return (ln(1.0 + 0.08 / max(hill, 0.08))).coerceIn(-0.02, 0.08)
    }

    private fun gaussian(rng: Random): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return kotlin.math.sqrt(-2.0 * ln(u1)) *
            kotlin.math.cos(2.0 * Math.PI * u2)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val m = values.size / 2
        return if (values.size % 2 == 0) {
            (values[m - 1] + values[m]) / 2.0
        } else {
            values[m]
        }
    }

    private fun quantile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val pos = p.coerceIn(0.0, 1.0) * (values.size - 1)
        val lo = pos.toInt()
        val hi = min(lo + 1, values.lastIndex)
        val f = pos - lo
        return values[lo] + (values[hi] - values[lo]) * f
    }
}
