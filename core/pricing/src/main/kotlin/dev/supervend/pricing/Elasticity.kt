package dev.supervend.pricing

import dev.supervend.model.Cents
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Elasticity estimation by log-log OLS regression of quantity on price (PRD R33) — a script over the
 * probe history, not a model. `ln(q) = a − e·ln(p)`; the slope's negation is the price elasticity of
 * demand. The estimate carries a confidence half-width so the caller (weekly audit) can gate on
 * convergence (≥3 levels and a tight interval) before acting on it.
 *
 * Money enters only as [Cents] inputs; the regression works in dimensionless log space and the
 * elasticity coefficient is not a monetary value, so [Double] here never touches a price/ledger
 * (CLAUDE.md #2). Inputs are exact and there is no randomness — the estimate is reproducible.
 */
object Elasticity {

    /** One probe observation: [unitsSold] at [price] over a dwell period. */
    data class Observation(val price: Cents, val unitsSold: Int)

    /**
     * Estimate elasticity from [observations]. Requires ≥2 points with variation in price and
     * positive quantities (logs are undefined otherwise). The relative CI half-width uses the
     * slope's standard error scaled by an approximate 95% factor.
     */
    fun estimate(observations: List<Observation>): ElasticityEstimate {
        val usable = observations.filter { it.unitsSold > 0 && it.price.value > 0 }
        val levels = usable.map { it.price.value }.distinct().size
        require(usable.size >= 2 && levels >= 2) { "need ≥2 observations at ≥2 price levels" }

        val xs = usable.map { ln(it.price.value.toDouble()) }
        val ys = usable.map { ln(it.unitsSold.toDouble()) }
        val n = usable.size
        val xBar = xs.average()
        val yBar = ys.average()
        val sxx = xs.sumOf { (it - xBar) * (it - xBar) }
        val sxy = xs.indices.sumOf { (xs[it] - xBar) * (ys[it] - yBar) }
        val slope = sxy / sxx
        val elasticity = -slope

        // Residual standard error → standard error of the slope → ~95% half-width.
        val intercept = yBar - slope * xBar
        val ssRes = xs.indices.sumOf { val pred = intercept + slope * xs[it]; (ys[it] - pred) * (ys[it] - pred) }
        val slopeSe = if (n > 2) sqrt(ssRes / (n - 2) / sxx) else 0.0
        val ciHalfWidth = T95 * slopeSe

        return ElasticityEstimate(elasticity = elasticity, ciHalfWidth = ciHalfWidth, levels = levels)
    }

    /** Coarse two-sided 95% t-multiplier; exact enough for a convergence gate, not inference. */
    private const val T95 = 2.0
}

/**
 * The estimate plus its convergence verdict. [converged] requires ≥3 distinct probe levels and a
 * relative CI half-width under 25% (OPERATOR-INPUT #3), the bar the workplan sets before an
 * elasticity may drive a price change.
 */
data class ElasticityEstimate(val elasticity: Double, val ciHalfWidth: Double, val levels: Int) {
    val converged: Boolean
        get() = levels >= 3 && elasticity != 0.0 && ciHalfWidth / abs(elasticity) < 0.25
}
