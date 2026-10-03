package com.example.aviatorai

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
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
 * Multi-model probabilistic engine.
 *
 * The engine does not assume that a mathematical pattern must exist. It
 * evaluates several different structures against held-out historical rounds:
 * robust Bayesian location, exponentially weighted location, robust trend,
 * and a regularised harmonic (sin/cos) model. Each model receives weight only
 * when its out-of-sample log-error is competitive.
 *
 * This is deliberately an uncertainty estimator, not a guarantee of the next
 * independently generated game result.
 */
class PredictionModel {

    private enum class ModelType {
        ROBUST_BAYES,
        EWMA,
        ROBUST_TREND,
        HARMONIC
    }

    fun estimate(data: List<Double>): PredictionEstimate? {
        val clean = data
            .filter { it.isFinite() && it >= 1.0 && it <= Double.MAX_VALUE }
            .takeLast(2000)

        if (clean.size < 10) return null

        val logs = clean.map(::ln)
        val candidateModels = ModelType.values()

        val weights = candidateModels.associateWith { model ->
            backtestWeight(logs, model)
        }

        val totalWeight = weights.values.sum().coerceAtLeast(1e-9)
        val predictions = candidateModels.map { model ->
            predictCandidate(logs, model)
        }

        var ensembleLog = 0.0
        for (i in candidateModels.indices) {
            ensembleLog += predictions[i] *
                (weights[candidateModels[i]] ?: 0.0) / totalWeight
        }

        val medianLog = median(logs.sorted())
        val recent = logs.takeLast(min(48, logs.size))
        val recentMedian = median(recent.sorted())

        // Bayesian shrinkage prevents a short recent window from completely
        // overriding the longer robust history.
        val recentWeight = recent.size.toDouble() / (recent.size + 16.0)
        ensembleLog =
            ensembleLog * 0.72 +
            (medianLog * (1.0 - recentWeight) + recentMedian * recentWeight) * 0.28

        val changePoint = detectChangePoint(logs)
        if (changePoint) {
            // A detected regime change increases responsiveness, but only
            // modestly so one unusual run cannot dominate the estimate.
            ensembleLog = ensembleLog * 0.82 + recentMedian * 0.18
        }

        val point = exp(ensembleLog).coerceIn(1.0, Double.MAX_VALUE)

        val residualScale = robustResidualScale(logs, ensembleLog)
        val disagreement = robustDisagreement(predictions, weights)
        val tailScale = extremeTailScale(clean)

        // Monte-Carlo uncertainty combines historical residual uncertainty,
        // model disagreement, and a bounded heavy-tail component.
        val sigma = max(
            0.035,
            residualScale * 1.4826 + disagreement * 0.35
        )

        val rng = Random(0x51A71 + clean.size)
        val simulations = ArrayList<Double>(1200)

        repeat(1200) {
            val z = gaussian(rng)
            val heavyTail = if (rng.nextDouble() < 0.04) {
                abs(gaussian(rng)) * tailScale
            } else {
                0.0
            }
            val simulatedLog = ensembleLog + z * sigma + heavyTail
            simulations.add(
                exp(simulatedLog).coerceIn(1.0, Double.MAX_VALUE)
            )
        }

        simulations.sort()
        val lower = quantile(simulations, 0.10)
        val upper = quantile(simulations, 0.90)
        val simulatedMedian = quantile(simulations, 0.50)

        val p2 = bayesianSurvival(clean, 2.0)
        val p5 = bayesianSurvival(clean, 5.0)

        val stability = 1.0 / (1.0 + sigma * 2.2)
        val sampleFactor =
            (ln(clean.size.toDouble() + 1.0) / ln(101.0)).coerceIn(0.0, 1.0)
        val cpPenalty = if (changePoint) 0.82 else 1.0

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

    private fun backtestWeight(logs: List<Double>, model: ModelType): Double {
        if (logs.size < 18) return 1.0

        val start = max(12, logs.size - 16)
        val errors = ArrayList<Double>()

        for (t in start until logs.size) {
            val train = logs.subList(0, t)
            if (train.size < 10) continue
            val prediction = predictCandidate(train, model)
            errors.add(abs(prediction - logs[t]))
        }

        if (errors.isEmpty()) return 1.0

        val error = median(errors.sorted())
        // Exponential score: lower held-out error gets more weight.
        // The floor prevents any one model from completely taking over.
        return 0.08 + exp(-error / 0.22)
    }

    private fun predictCandidate(logs: List<Double>, model: ModelType): Double {
        return when (model) {
            ModelType.ROBUST_BAYES -> robustBayes(logs)
            ModelType.EWMA -> ewma(logs)
            ModelType.ROBUST_TREND -> robustTrend(logs)
            ModelType.HARMONIC -> harmonicRegression(logs)
        }.coerceIn(
            (logs.minOrNull() ?: 0.0) - 2.0,
            (logs.maxOrNull() ?: 0.0) + 2.0
        )
    }

    private fun robustBayes(logs: List<Double>): Double {
        val historical = median(logs.sorted())
        val recent = logs.takeLast(min(40, logs.size))
        val recentMedian = median(recent.sorted())
        val w = recent.size.toDouble() / (recent.size + 14.0)
        return historical * (1.0 - w) + recentMedian * w
    }

    private fun ewma(logs: List<Double>): Double {
        var value = logs.first()
        val alpha = 0.18
        for (i in 1 until logs.size) {
            value = alpha * logs[i] + (1.0 - alpha) * value
        }
        return value
    }

    private fun robustTrend(logs: List<Double>): Double {
        val recent = logs.takeLast(min(50, logs.size))
        if (recent.size < 8) return median(recent.sorted())

        val slopes = ArrayList<Double>()
        val stride = max(1, recent.size / 12)

        for (i in 0 until recent.size - stride) {
            val j = i + stride
            slopes.add((recent[j] - recent[i]) / stride.toDouble())
        }

        val slope = median(slopes.sorted()).coerceIn(-0.08, 0.08)
        return recent.last() + slope * min(3, stride).toDouble()
    }

    /*
     * Regularised trigonometric basis:
     * 1, time, sin/cos(period 4), sin/cos(period 7), sin/cos(period 12).
     *
     * It is intentionally only one candidate in the ensemble. Back-testing
     * decides whether it deserves meaningful weight; it cannot manufacture a
     * periodic pattern when the data does not support one.
     */
    private fun harmonicRegression(logs: List<Double>): Double {
        val n = min(80, logs.size)
        val start = logs.size - n
        val dimension = 7
        val normal = Array(dimension) { DoubleArray(dimension) }
        val rhs = DoubleArray(dimension)

        for (r in 0 until n) {
            val x = start + r
            val t = r.toDouble() / max(1, n - 1).toDouble()
            val row = doubleArrayOf(
                1.0,
                t,
                sin(2.0 * PI * r / 4.0),
                cos(2.0 * PI * r / 4.0),
                sin(2.0 * PI * r / 7.0),
                cos(2.0 * PI * r / 7.0),
                sin(2.0 * PI * r / 12.0)
            )
            val y = logs[x]

            for (i in 0 until dimension) {
                rhs[i] += row[i] * y
                for (j in 0 until dimension) {
                    normal[i][j] += row[i] * row[j]
                }
            }
        }

        // Ridge regularisation makes the trigonometric fit much less prone to
        // fitting noise in a short historical sequence.
        for (i in 0 until dimension) {
            normal[i][i] += 0.35
        }

        val beta = solveLinearSystem(normal, rhs) ?: return robustBayes(logs)

        val next = n.toDouble() / max(1, n - 1).toDouble()
        val row = doubleArrayOf(
            1.0,
            next,
            sin(2.0 * PI * n / 4.0),
            cos(2.0 * PI * n / 4.0),
            sin(2.0 * PI * n / 7.0),
            cos(2.0 * PI * n / 7.0),
            sin(2.0 * PI * n / 12.0)
        )

        var prediction = 0.0
        for (i in 0 until dimension) prediction += beta[i] * row[i]
        return prediction
    }

    private fun solveLinearSystem(
        input: Array<DoubleArray>,
        bInput: DoubleArray
    ): DoubleArray? {
        val n = bInput.size
        val a = Array(n) { i ->
            DoubleArray(n + 1).also { row ->
                for (j in 0 until n) row[j] = input[i][j]
                row[n] = bInput[i]
            }
        }

        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) {
                if (abs(a[row][col]) > abs(a[pivot][col])) pivot = row
            }
            if (abs(a[pivot][col]) < 1e-10) return null

            val temp = a[col]
            a[col] = a[pivot]
            a[pivot] = temp

            val divisor = a[col][col]
            for (j in col until n + 1) a[col][j] /= divisor

            for (row in 0 until n) {
                if (row == col) continue
                val factor = a[row][col]
                if (abs(factor) < 1e-12) continue
                for (j in col until n + 1) {
                    a[row][j] -= factor * a[col][j]
                }
            }
        }

        return DoubleArray(n) { a[it][n] }
    }

    private fun robustResidualScale(logs: List<Double>, center: Double): Double {
        val recent = logs.takeLast(min(60, logs.size))
        val deviations = recent.map { abs(it - center) }.sorted()
        return median(deviations).coerceAtLeast(0.02)
    }

    private fun robustDisagreement(
        predictions: List<Double>,
        weights: Map<ModelType, Double>
    ): Double {
        val values = predictions.sorted()
        val med = median(values)
        return median(values.map { abs(it - med) }.sorted()).coerceAtLeast(0.0)
    }

    private fun extremeTailScale(data: List<Double>): Double {
        if (data.size < 30) return 0.03
        val sorted = data.sorted()
        val k = max(5, min(sorted.size / 10, 50))
        val threshold = sorted[sorted.size - k - 1]
        if (threshold <= 1.0) return 0.03

        var sum = 0.0
        for (i in sorted.size - k until sorted.size) {
            sum += ln(sorted[i] / threshold)
        }

        val hill = if (sum > 1e-9) k.toDouble() / sum else 1.0
        return (0.02 + 0.04 / max(hill, 0.08)).coerceIn(0.02, 0.18)
    }

    private fun bayesianSurvival(data: List<Double>, threshold: Double): Double {
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

        val pooled = before + after
        val pooledMedian = median(pooled.sorted())
        val pooledMad = median(
            pooled.map { abs(it - pooledMedian) }.sorted()
        ).coerceAtLeast(0.01)

        return abs(afterMedian - beforeMedian) >
            max(0.18, pooledMad * 2.8)
    }

    private fun gaussian(rng: Random): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
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
