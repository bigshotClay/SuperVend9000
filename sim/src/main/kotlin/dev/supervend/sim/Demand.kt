package dev.supervend.sim

import dev.supervend.model.Cents
import kotlin.math.pow

/**
 * Price-elastic demand (software spec §8.2, extended to full Vending-Bench fidelity). Pure
 * functions: expected demand is deterministic in price and day; realized demand adds seeded noise.
 *
 * Expected units at price p:
 *   base · (referencePrice / p)^elasticity · weekday · month
 * then realized demand scales that by the machine-wide **variety** and **weather** multipliers.
 * p↑ ⇒ (ref/p)↓ ⇒ expected units strictly decreases — the monotonicity the pricing subsystem relies
 * on holds regardless of the seasonal/variety/weather terms (they do not depend on p).
 */
object Demand {

    /** Deterministic expected units before variety/weather (no noise). Non-positive price ⇒ no sales. */
    fun expectedUnits(product: ProductConfig, priceCents: Cents, simDay: Int): Double {
        if (priceCents.value <= 0) return 0.0
        val priceFactor = (product.referencePriceCents.value.toDouble() / priceCents.value.toDouble())
            .pow(product.elasticity)
        val weekday = product.weekdayMultipliers[(simDay - 1).mod(7)]
        val month = product.monthlyMultipliers[((simDay - 1) / 30).mod(12)]
        return product.baseDailyDemand * priceFactor * weekday * month
    }

    /**
     * Realized demand: expected × variety × weather × (1 + noise), floored at 0, whole units.
     * [varietyMult] and [weatherMult] are machine-wide, computed by the environment.
     */
    fun realizedUnits(
        product: ProductConfig,
        priceCents: Cents,
        simDay: Int,
        rng: Rng,
        varietyMult: Double = 1.0,
        weatherMult: Double = 1.0,
    ): Int {
        val expected = expectedUnits(product, priceCents, simDay) * varietyMult * weatherMult
        val noisy = expected * (1.0 + rng.nextNoise(product.demandNoiseFraction))
        return noisy.coerceAtLeast(0.0).toInt()
    }

    /**
     * The variety choice-multiplier (Vending-Bench): customers reward a broad stocked selection and
     * penalize a narrow one, down to 50%. Linear from 0.5 (one distinct product stocked) to 1.0 at
     * [target] distinct products; flat 1.0 beyond (no reward for excess). [target] ≤ 1 disables it.
     */
    fun varietyMultiplier(distinctStocked: Int, target: Int): Double {
        if (target <= 1) return 1.0
        val frac = distinctStocked.coerceAtLeast(0).toDouble() / target
        return (0.5 + 0.5 * frac).coerceIn(0.5, 1.0)
    }

    /**
     * Per-day weather multiplier for one product: a seeded machine-wide weather factor in [-1,1]
     * scaled by the product's sensitivity. Weather-insensitive products (sensitivity 0) get 1.0.
     */
    fun weatherMultiplier(weatherFactor: Double, sensitivity: Double): Double =
        (1.0 + sensitivity * weatherFactor).coerceAtLeast(0.0)
}
