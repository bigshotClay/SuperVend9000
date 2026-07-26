package dev.supervend.pricing

import dev.supervend.model.BasisPoints
import dev.supervend.model.Cents
import kotlinx.serialization.Serializable

/**
 * The deliberate price-probe schedule (PRD R33): small, planned price moves at fixed dwell intervals
 * so demand response can be regressed. Defined entirely in the workplan / config — the schedule is a
 * script, and it changes only via weekly audit, never by model whim. Defaults: ±5% steps, ≥3 levels,
 * 7-day dwell (OPERATOR-INPUT #3 known values).
 */
@Serializable
data class ProbeSchedule(
    val stepBps: BasisPoints = BasisPoints.percent(5),
    val dwellDays: Int = 7,
    val levels: Int = 3,
) {
    init {
        require(levels >= 3) { "elasticity needs ≥3 probe levels, was $levels" }
        require(dwellDays >= 1) { "dwell must be ≥1 day, was $dwellDays" }
    }

    /**
     * The probe points around [basePrice]: [levels] symmetric price steps, each held for [dwellDays].
     * For 3 levels this is {base·(1−step), base, base·(1+step)} at day offsets {0, dwell, 2·dwell}.
     */
    fun points(basePrice: Cents): List<ProbePoint> {
        val half = levels / 2
        return (0 until levels).map { i ->
            val offset = i - half // …,-1,0,+1,…
            val factorBps = BasisPoints(BasisPoints.ONE_HUNDRED_PERCENT + offset * stepBps.value)
            ProbePoint(dayOffset = i * dwellDays, price = basePrice.scaleBps(factorBps))
        }
    }
}

/** One scheduled probe: hold [price] starting [dayOffset] days into the probe window. */
@Serializable
data class ProbePoint(val dayOffset: Int, val price: Cents)
