package dev.supervend.audit

import kotlinx.serialization.Serializable

/**
 * Weekly audit aggregation (workplan AUD-01, PRD R37). A script folds seven days of metrics into a
 * single [WeeklyReport] the audit agent reads (AUD-02) before proposing bounded amendments. Pure
 * integer arithmetic — the ambiguous-classification rate is basis points, never a [Double].
 */
object WeeklyAggregator {

    /** Aggregate exactly seven daily metric rows (the audit cadence). */
    fun aggregate(days: List<DailyMetrics>): WeeklyReport {
        require(days.size == 7) { "weekly audit aggregates 7 daily summaries, got ${days.size}" }
        val classifications = days.sumOf { it.classifications.toLong() }
        val ambiguous = days.sumOf { it.ambiguous.toLong() }
        return WeeklyReport(
            days = days.map { it.day },
            totalRevenueCents = days.sumOf { it.revenueCents },
            totalUnits = days.sumOf { it.unitsSold },
            gateFires = days.sumOf { it.gateFires },
            incidents = days.sumOf { it.incidents },
            ambiguousRateBps = if (classifications == 0L) 0 else ambiguous * 10_000 / classifications,
        )
    }
}

/** One day's roll-up, produced at CLS-02 close (PRD R37). */
@Serializable
data class DailyMetrics(
    val day: Int,
    val revenueCents: Long,
    val unitsSold: Int,
    val gateFires: Int,
    val incidents: Int,
    val ambiguous: Int,
    val classifications: Int,
)

/** Seven-day aggregate feeding the audit agent (workplan AUD-01). */
@Serializable
data class WeeklyReport(
    val days: List<Int>,
    val totalRevenueCents: Long,
    val totalUnits: Int,
    val gateFires: Int,
    val incidents: Int,
    /** Ambiguous-classification rate in basis points — a health signal for the negotiation layer. */
    val ambiguousRateBps: Long,
)
