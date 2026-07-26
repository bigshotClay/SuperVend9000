package dev.supervend.reports

/** Renders a [ReportResult] as Markdown — the `harness report` stdout / artifact (software spec §14). */
object ReportRender {

    fun markdown(r: ReportResult): String = buildString {
        appendLine("# Harness Experiment Report")
        appendLine()

        appendLine("## 1. Harness lift (G2) — naive vs. harnessed net worth")
        appendLine()
        appendLine("| Model | naive mean | naive min | harness mean | harness min | lift? |")
        appendLine("|---|--:|--:|--:|--:|:--:|")
        for (row in r.lift) {
            appendLine("| ${row.model} | ${cents(row.naive.mean)} | ${cents(row.naive.min.toDouble())} | ${cents(row.harness.mean)} | ${cents(row.harness.min.toDouble())} | ${yn(row.lift)} |")
        }
        appendLine()

        appendLine("## 2. Flat curve (G1) — harness score across models")
        appendLine()
        appendLine("| Model | mean | min | CV(seeds) |")
        appendLine("|---|--:|--:|--:|")
        for (m in r.flat.perModel) appendLine("| ${m.model} | ${cents(m.dist.mean)} | ${cents(m.dist.min.toDouble())} | ${pct(m.dist.cv)} |")
        appendLine()
        appendLine("**CV(models) = ${pct(r.flat.cvModels)}, CV(seeds) = ${pct(r.flat.cvSeeds)} → ${if (r.flat.flat) "FLAT (CV_models ≤ CV_seeds ✓)" else "NOT FLAT ✗"}**")
        appendLine()

        appendLine("## 3. Failure classes (G3) — realized incidence (target: zeros for harness)")
        appendLine()
        appendLine("| Config | meltdowns | below-cost sales | unvalidated payments | (blocked below-cost) | (blocked unvalidated) |")
        appendLine("|---|--:|--:|--:|--:|--:|")
        for (f in r.failures) {
            appendLine("| ${f.config} | ${f.meltdowns} | ${realized(f.belowCostSales)} | ${realized(f.unvalidatedPayments)} | ${f.belowCostBlocked} | ${f.unvalidatedBlocked} |")
        }
        appendLine()

        appendLine("## 4. Efficiency (G5) — per sim-day")
        appendLine()
        appendLine("| Config | LLM calls/day | tokens/day | gate fires/day | incidents/day |")
        appendLine("|---|--:|--:|--:|--:|")
        for (e in r.efficiency) {
            appendLine("| ${e.config} | ${"%.1f".format(e.llmCallsPerDay)} | ${"%.0f".format(e.tokensPerDay)} | ${"%.1f".format(e.gateFiresPerDay)} | ${"%.2f".format(e.incidentsPerDay)} |")
        }
    }

    private fun cents(v: Double): String = "$%.2f".format(v / 100.0)
    private fun pct(v: Double): String = "%.1f%%".format(v * 100)
    private fun yn(b: Boolean): String = if (b) "✓" else "✗"
    private fun realized(v: Int): String = if (v == RunSummary.NOT_INSTRUMENTED) "—" else v.toString()
}
