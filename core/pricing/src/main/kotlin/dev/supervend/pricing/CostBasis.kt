package dev.supervend.pricing

import dev.supervend.model.Cents

/**
 * Cost basis is pure arithmetic from state (PRD R31) — the cheapest sufficient mechanism, so it is
 * a script, never a model. Weighted-average cost over the received lots, truncating to whole cents
 * with a fixed rounding direction so the value is bit-reproducible (CLAUDE.md #2/#4). No [Double]
 * touches the money path.
 */
object CostBasis {

    /** One received purchase lot: [units] at [unitCost] each. */
    data class Lot(val units: Int, val unitCost: Cents)

    /**
     * Weighted-average unit cost across [lots]. Empty history → [Cents.ZERO]. Integer math end to
     * end: total cents ÷ total units, truncated toward zero.
     */
    fun weightedAverage(lots: List<Lot>): Cents {
        val totalUnits = lots.sumOf { it.units.toLong() }
        if (totalUnits == 0L) return Cents.ZERO
        val totalCents = lots.sumOf { it.unitCost.value * it.units }
        return Cents(totalCents / totalUnits)
    }
}
