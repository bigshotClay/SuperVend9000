package dev.supervend.model

import kotlinx.serialization.Serializable

/**
 * Money is integer cents. Hard constraint (CLAUDE.md #2): no [Double] ever touches a
 * ledger, price, or quote. All monetary arithmetic is exact integer arithmetic.
 *
 * Percentage-scaled math (margin floors, price bands) is expressed in [BasisPoints] so
 * that even proportional operations stay in integer space.
 */
@JvmInline
@Serializable
value class Cents(val value: Long) : Comparable<Cents> {

    operator fun plus(other: Cents): Cents = Cents(value + other.value)

    operator fun minus(other: Cents): Cents = Cents(value - other.value)

    operator fun times(units: Int): Cents = Cents(value * units)

    operator fun times(units: Long): Cents = Cents(value * units)

    operator fun unaryMinus(): Cents = Cents(-value)

    override fun compareTo(other: Cents): Int = value.compareTo(other.value)

    val isPositive: Boolean get() = value > 0
    val isNegative: Boolean get() = value < 0
    val isZero: Boolean get() = value == 0L

    /**
     * Scale by a proportion given in basis points, rounding toward zero (truncating).
     * e.g. `Cents(1000).scaleBps(BasisPoints(12000))` = a 20% markup = Cents(1200).
     * Rounding direction is fixed and documented so gate math is bit-reproducible.
     */
    fun scaleBps(bps: BasisPoints): Cents = Cents(value * bps.value / BasisPoints.ONE_HUNDRED_PERCENT)

    companion object {
        val ZERO = Cents(0)
        fun sum(items: Iterable<Cents>): Cents = Cents(items.sumOf { it.value })
    }
}

/**
 * A proportion in basis points (1 bp = 0.01%). 10_000 bp = 100%. Used for margin floors,
 * price-change caps, and MAD multipliers so percentage math never needs a [Double].
 */
@JvmInline
@Serializable
value class BasisPoints(val value: Long) {
    companion object {
        const val ONE_HUNDRED_PERCENT: Long = 10_000
        fun percent(p: Int): BasisPoints = BasisPoints(p.toLong() * 100)
    }
}
