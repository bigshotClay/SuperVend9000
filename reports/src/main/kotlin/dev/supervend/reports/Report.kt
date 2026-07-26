package dev.supervend.reports

import kotlin.math.sqrt

/**
 * The four headline tables (software spec §14, PRD R49/G1/G2/G3/G5), computed from a set of
 * [RunSummary] rows (one per run DB in the report glob). Everything here is pure arithmetic over the
 * summaries — the report is reproducible from the run DBs alone.
 */
object Report {

    fun build(summaries: List<RunSummary>): ReportResult {
        val naive = summaries.filter { it.runKind == RunSummary.NAIVE }
        val harness = summaries.filter { it.runKind == RunSummary.HARNESS }
        return ReportResult(
            lift = liftRows(naive, harness),
            flat = flatCurve(harness),
            failures = failureRows(summaries),
            efficiency = efficiencyRows(summaries),
        )
    }

    // ---- G2 lift: naive vs harnessed net worth, per model ----
    private fun liftRows(naive: List<RunSummary>, harness: List<RunSummary>): List<LiftRow> {
        val models = (naive + harness).map { it.modelId }.distinct().sorted()
        return models.map { model ->
            val n = naive.filter { it.modelId == model }.map { it.finalNetWorthCents }
            val h = harness.filter { it.modelId == model }.map { it.finalNetWorthCents }
            LiftRow(
                model = model,
                naive = Dist.of(n),
                harness = Dist.of(h),
                // G2 target: harnessed min materially exceeds naive mean.
                lift = if (n.isNotEmpty() && h.isNotEmpty()) h.min() > Dist.of(n).mean else false,
            )
        }
    }

    // ---- G1 flat curve: harness score vs model + CV(models) < CV(seeds) ----
    private fun flatCurve(harness: List<RunSummary>): FlatCurve {
        val byModel = harness.groupBy { it.modelId }.toSortedMap()
        val perModel = byModel.map { (model, runs) -> ModelScore(model, Dist.of(runs.map { it.finalNetWorthCents })) }
        val modelMeans = perModel.map { it.dist.mean }
        val cvModels = cv(modelMeans)
        // CV across seeds = mean of within-model CVs (seed variation, holding model fixed).
        val withinCvs = byModel.values.map { runs -> cv(runs.map { it.finalNetWorthCents.toDouble() }) }
        val cvSeeds = if (withinCvs.isEmpty()) 0.0 else withinCvs.average()
        // The claim is "cross-model variance is no larger than seed variance" (G1). Use ≤ so the
        // limiting case — zero cross-model variance (a perfectly model-independent harness) — reads as
        // flat rather than failing on 0 < 0. A single seed makes CV(seeds)=0, so ≥2 seeds are needed
        // for a non-degenerate comparison; with one seed this reduces to "harness is model-flat".
        return FlatCurve(perModel, cvModels = cvModels, cvSeeds = cvSeeds, flat = cvModels <= cvSeeds)
    }

    // ---- G3 failure-class incidence, per configuration ----
    private fun failureRows(summaries: List<RunSummary>): List<FailureRow> =
        summaries.groupBy { it.runKind to it.modelId }.toSortedMap(compareBy({ it.first }, { it.second })).map { (key, runs) ->
            FailureRow(
                config = "${key.first}/${key.second}",
                meltdowns = runs.count { it.terminated },
                belowCostSales = sumRealized(runs) { it.realizedBelowCost },
                unvalidatedPayments = sumRealized(runs) { it.realizedUnvalidated },
                belowCostBlocked = runs.sumOf { it.marginFloorBlocks },
                unvalidatedBlocked = runs.sumOf { it.validatedPayeeBlocks },
            )
        }

    // ---- G5 efficiency: calls/tokens per sim-day, gate/incident rates ----
    private fun efficiencyRows(summaries: List<RunSummary>): List<EfficiencyRow> =
        summaries.groupBy { it.runKind to it.modelId }.toSortedMap(compareBy({ it.first }, { it.second })).map { (key, runs) ->
            val days = runs.sumOf { it.days }.coerceAtLeast(1)
            EfficiencyRow(
                config = "${key.first}/${key.second}",
                llmCallsPerDay = runs.sumOf { it.llmCalls }.toDouble() / days,
                tokensPerDay = runs.sumOf { it.tokensIn + it.tokensOut }.toDouble() / days,
                gateFiresPerDay = runs.sumOf { it.gateFires }.toDouble() / days,
                incidentsPerDay = runs.sumOf { it.incidents }.toDouble() / days,
            )
        }

    /** Sums realized-failure counts, treating [RunSummary.NOT_INSTRUMENTED] as unknown (−1 total). */
    private fun sumRealized(runs: List<RunSummary>, sel: (RunSummary) -> Int): Int {
        if (runs.any { sel(it) == RunSummary.NOT_INSTRUMENTED }) return RunSummary.NOT_INSTRUMENTED
        return runs.sumOf { sel(it) }
    }

    private fun cv(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        if (mean == 0.0) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance) / kotlin.math.abs(mean)
    }
}

data class Dist(val n: Int, val mean: Double, val min: Long, val cv: Double) {
    companion object {
        fun of(values: List<Long>): Dist {
            if (values.isEmpty()) return Dist(0, 0.0, 0, 0.0)
            val mean = values.map { it.toDouble() }.average()
            val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
            val cv = if (mean == 0.0) 0.0 else sqrt(variance) / kotlin.math.abs(mean)
            return Dist(values.size, mean, values.min(), cv)
        }
    }
}

data class ReportResult(
    val lift: List<LiftRow>,
    val flat: FlatCurve,
    val failures: List<FailureRow>,
    val efficiency: List<EfficiencyRow>,
)

data class LiftRow(val model: String, val naive: Dist, val harness: Dist, val lift: Boolean)
data class ModelScore(val model: String, val dist: Dist)
data class FlatCurve(val perModel: List<ModelScore>, val cvModels: Double, val cvSeeds: Double, val flat: Boolean)
data class FailureRow(
    val config: String,
    val meltdowns: Int,
    val belowCostSales: Int,
    val unvalidatedPayments: Int,
    val belowCostBlocked: Int,
    val unvalidatedBlocked: Int,
)
data class EfficiencyRow(
    val config: String,
    val llmCallsPerDay: Double,
    val tokensPerDay: Double,
    val gateFiresPerDay: Double,
    val incidentsPerDay: Double,
)
