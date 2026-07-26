package dev.supervend.app

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import dev.supervend.app.baseline.BaselineRunner
import dev.supervend.app.harness.HarnessRunner
import dev.supervend.llm.qualify.Battery
import dev.supervend.reports.Report as ReportBuilder
import dev.supervend.reports.ReportRender
import dev.supervend.reports.RunReader
import dev.supervend.personas.PersonaRegistry
import dev.supervend.personas.Staffing
import dev.supervend.plan.Workplan
import dev.supervend.state.RunDatabase
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `harness` CLI (software spec §12). Stage 3 wires two subcommands: `qualify` (the >90%
 * instruction-following battery) and `baseline` (the naive control arm). Later stages add
 * `run`, `validate`, `revalidate`, and `report`.
 */
class Harness : CliktCommand() {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Weak-model Vending-Bench harness."
    override fun run() = Unit
}

/** `harness qualify --model <id>` — runs the qualification battery and persists the result. */
class Qualify : CliktCommand() {
    private val model by option("--model", help = "Model id (or 'mock'/'mock:<text>' for offline).").required()
    private val out by option("--out", help = "Run DB path to persist results.").default("runs/qualify.db")

    override fun run() = runBlocking {
        val battery = Battery.default
        val port = ModelFactory.create(model)
        val report = battery.run(port, model)
        File(out).parentFile?.mkdirs()
        RunDatabase.open(out).use { db -> QualificationStore.save(db.connection, report) }

        echo("model=$model  battery=${report.batteryHash.hex.take(12)}")
        echo("passed ${report.passed}/${report.total}  score=${"%.1f".format(report.score * 100)}%  threshold=>${(report.threshold * 100).toInt()}%")
        for ((cat, rate) in report.byCategory()) echo("  ${cat.name.padEnd(22)} ${"%.0f".format(rate * 100)}%")
        echo(if (report.qualified) "QUALIFIED" else "NOT QUALIFIED")
    }
}

/** `harness baseline --model <id> --seed <n>` — runs the naive arm end-to-end into a run DB. */
class Baseline : CliktCommand() {
    private val model by option("--model", help = "Model id (or 'mock'/'mock:<json>' for offline).").required()
    private val seed by option("--seed", help = "Sim seed.").long().default(1L)
    private val days by option("--days", help = "Horizon in sim days.").int().default(30)
    private val out by option("--out", help = "Run DB path.").default("runs/baseline.db")
    private val sim by option("--sim", help = "'default' (3-product), 'hard' (8-product), or 'hostile' (adversarial 8-product) — must match the arm under comparison.").default("default")

    override fun run() = runBlocking {
        val port = ModelFactory.create(model)
        File(out).parentFile?.mkdirs()
        val runId = "baseline-$model-$seed".replace('/', '_')
        val simConfig = when (sim.lowercase()) {
            "hostile" -> dev.supervend.sim.SimConfig.hostileMode()
            "hard" -> dev.supervend.sim.SimConfig.hardMode()
            else -> dev.supervend.sim.SimConfig.default()
        }
        val traces = RunDatabase.open(out).use { db ->
            BaselineRunner(port, model, config = simConfig).run(runId, seed, days, db.connection)
        }
        val last = traces.lastOrNull()
        echo("run=$runId  BARE  days=${traces.size}  parsedOk=${traces.count { it.parsedOk }}/${traces.size}")
        echo("NET WORTH=${last?.report?.netWorthAfter?.value ?: "n/a"}c  (bank=${last?.report?.bankBalanceAfter?.value ?: "n/a"}c)  terminated=${last?.report?.terminated ?: false}")
        echo("wrote $out")
    }
}

/** `harness run --model <id> --seed <n>` — the gated harnessed arm end-to-end into a run DB. */
class Run : CliktCommand() {
    private val model by option("--model", help = "Model id (or 'mock'/'mock:<text>' for offline).").required()
    private val seed by option("--seed", help = "Sim seed.").long().default(1L)
    private val days by option("--days", help = "Horizon in sim days.").int().default(30)
    private val out by option("--out", help = "Run DB path.").default("runs/harness.db")
    private val decisions by option("--decisions", help = "Who prices: 'model' (alpha on the floor) or 'script' (the control floor).").default("model")
    private val full by option("--full", help = "Full integration: model works every seat + adversarial procurement.").flag(default = false)
    private val sim by option("--sim", help = "'default' (3-product), 'hard' (8-product), 'hostile' (adversarial), or 'live' (LLM vendors).").default("default")
    private val vendorModel by option("--vendor-model", help = "Live-vendor model id (LIVE suppliers), e.g. qwen2.5:14b-instruct. 'mock:<json>' for offline.").default("")
    private val vendorUrl by option("--vendor-url", help = "Vendor model endpoint (/v1/chat/completions) — the vendor GPU, distinct from the buyer's HARNESS_BASE_URL.").default("")
    private val gates by option("--gates", help = "Full-arm gate enforcement: 'on' (default) or 'off' (R1a ablation — full call structure/budget/state, no gate ever binds).").default("on")
    private val state by option("--state", help = "Externalized state: 'harness' (default — model handed ground-truth state daily) or 'model' (R1b ablation — model maintains its own belief and decides from it).").default("harness")
    private val scriptSeats by option("--script-seats", help = "R5 seat ablation: comma list of model seats to replace with a script (only 'pricing' supported). Other seats stay model-driven; gates/state stay on.").default("")

    override fun run() = runBlocking {
        val port = ModelFactory.create(model)
        File(out).parentFile?.mkdirs()
        val enforceGates = !gates.equals("off", ignoreCase = true)
        val externalizeState = !state.equals("model", ignoreCase = true)
        val scriptPricing = scriptSeats.split(",").map { it.trim().lowercase() }.contains("pricing")
        val arm = when {
            !enforceGates -> "harness-r1a"
            !externalizeState -> "harness-r1b"
            scriptPricing -> "harness-r5pricing"
            else -> "harness"
        }
        val runId = "$arm-$model-$seed".replace('/', '_')
        if (full) {
            val simConfig = when (sim.lowercase()) {
                "live" -> dev.supervend.sim.SimConfig.liveMode()
                "hostile" -> dev.supervend.sim.SimConfig.hostileMode()
                "hard" -> dev.supervend.sim.SimConfig.hardMode()
                else -> dev.supervend.sim.SimConfig.default()
            }
            // LIVE suppliers: build a vendor brain on its own model/endpoint (distinct from the buyer).
            val vendorBrain = if (simConfig.supplierMode == dev.supervend.sim.SupplierMode.LIVE) {
                val vModel = vendorModel.ifBlank { error("--sim live requires --vendor-model (use 'mock:<json>' for offline)") }
                val vPort = if (vendorUrl.isBlank()) ModelFactory.create(vModel) else ModelFactory.createAt(vModel, vendorUrl)
                dev.supervend.app.harness.LiveVendorBrain(vPort, vModel, simConfig.buyerMarginFloorBps)
            } else null
            val fullMode = if (decisions.equals("script", ignoreCase = true)) dev.supervend.app.harness.FullRunner.Decisions.SCRIPT else dev.supervend.app.harness.FullRunner.Decisions.MODEL
            val runner = dev.supervend.app.harness.FullRunner(port, model, config = simConfig, decisions = fullMode, vendorBrain = vendorBrain, enforceGates = enforceGates, externalizeState = externalizeState, scriptPricing = scriptPricing)
            val reports = RunDatabase.open(out).use { db -> runner.run(runId, seed, days, db.connection) }
            val last = reports.lastOrNull()
            val s = runner.stats
            echo("run=$runId  FULL  days=${reports.size}  terminated=${last?.terminated ?: false}")
            echo("NET WORTH=${last?.netWorthAfter?.value ?: "n/a"}c  (bank=${last?.bankBalanceAfter?.value ?: "n/a"}c)")
            echo("pricing: applied=${s.priceApplied} gate-rejected=${s.priceRejected} fallback=${s.priceFallback}")
            echo("procurement: orders=${s.ordersPlaced} gate-blocked=${s.ordersGateBlocked} classify-calls=${s.classificationCalls} scams-flagged=${s.scamsFlaggedByModel} SCAM-ORDERS=${s.scamOrdersPlaced} unreliable-dropped=${s.unreliableDropped} units-delivered=${s.unitsDelivered}")
            echo("audit: amendments-proposed=${s.amendmentsProposed}")
            echo("wrote $out")
            return@runBlocking
        }
        val mode = if (decisions.equals("script", ignoreCase = true)) HarnessRunner.Decisions.SCRIPT else HarnessRunner.Decisions.MODEL
        val runner = HarnessRunner(port, model, decisions = mode)
        val reports = RunDatabase.open(out).use { db -> runner.run(runId, seed, days, db.connection) }
        val last = reports.lastOrNull()
        echo("run=$runId  decisions=${mode.name.lowercase()}  days=${reports.size}  terminated=${last?.terminated ?: false}")
        echo("final bank=${last?.bankBalanceAfter?.value ?: "n/a"}c")
        val s = runner.stats
        if (mode == HarnessRunner.Decisions.MODEL) {
            echo("pricing: model-applied=${s.modelApplied}  gate-rejected=${s.modelRejected}  no-proposal=${s.fallbackNoProposal}  (of ${s.total})")
        }
        echo("wrote $out")
    }
}

/** `harness report --runs <glob>` — the four headline tables across a set of run DBs. */
class Report : CliktCommand() {
    private val runs by option("--runs", help = "Glob of run DB files, e.g. 'runs/*.db'.").required()
    private val out by option("--out", help = "Write Markdown here instead of stdout.").default("")

    override fun run() {
        val summaries = expandGlob(runs).flatMap { RunReader.read(it) }
        if (summaries.isEmpty()) {
            echo("no runs matched $runs")
            return
        }
        val md = ReportRender.markdown(ReportBuilder.build(summaries))
        if (out.isBlank()) echo(md) else {
            File(out).apply { parentFile?.mkdirs() }.writeText(md)
            echo("summarized ${summaries.size} run(s) → $out")
        }
    }

    private fun expandGlob(pattern: String): List<String> {
        val p = java.nio.file.Paths.get(pattern)
        val dir = (p.parent ?: java.nio.file.Paths.get(".")).toFile()
        val matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:${p.fileName}")
        return (dir.listFiles()?.toList() ?: emptyList())
            .filter { it.isFile && matcher.matches(java.nio.file.Paths.get(it.name)) }
            .map { it.path }
            .sorted()
    }
}

/**
 * `harness validate --step <id> --artifact <path>` — run a step's registered validator over an
 * artifact file (software spec §12/§7). This is the *same* pure validator the orchestrator calls
 * in-process, so a CLI re-run is bit-identical to what the run saw. Exit 0 = Pass, 1 = Fail.
 */
class Validate : CliktCommand() {
    private val step by option("--step", help = "Plan step id, e.g. BOOT-01.").required()
    private val artifact by option("--artifact", help = "Path to the artifact JSON.").required()

    override fun run() {
        val stepDef = Workplan.PLAN.allSteps.firstOrNull { it.id.value == step }
            ?: throw com.github.ajalt.clikt.core.PrintMessage("unknown step $step", statusCode = 2)
        val validator = dev.supervend.validate.ValidatorRegistry.DEFAULT.require(stepDef.validator.id)
        val json = File(artifact).readText()
        when (val result = validator.validate(json, dev.supervend.validate.ValidationState.ANY)) {
            is dev.supervend.model.ValidationResult.Pass -> {
                echo("PASS  step=$step  validator=${stepDef.validator.id} v${stepDef.validator.version}")
            }
            is dev.supervend.model.ValidationResult.Fail -> {
                echo("FAIL  step=$step  validator=${stepDef.validator.id} v${stepDef.validator.version}")
                for (c in result.report.checks.filterNot { it.passed }) echo("  ${c.id}: expected ${c.expected}, observed ${c.observed}")
                throw com.github.ajalt.clikt.core.ProgramResult(1)
            }
        }
    }
}

/** `harness plan` — export Master Workplan v1 as canonical JSON (the step-for-step review artifact). */
class Plan : CliktCommand() {
    private val out by option("--out", help = "Write the plan JSON here instead of stdout.").default("")
    override fun run() {
        val plan = Workplan.PLAN
        val json = plan.prettyJson()
        if (out.isBlank()) echo(json) else {
            File(out).apply { parentFile?.mkdirs() }.writeText(json)
            echo("plan=${plan.version().hash.hex.take(12)}  steps=${plan.allSteps.size}  wrote $out")
        }
    }
}

/**
 * `harness models` — print the OpenRouter free-tier roster (PRD R43). Deliberately uncurated: the
 * matrix runs over whatever the free tier offers, which is the point (model-independence). Requires
 * `OPENROUTER_KEY`.
 */
class Models : CliktCommand() {
    override fun run() = runBlocking {
        val key = System.getenv("OPENROUTER_KEY")
            ?: throw com.github.ajalt.clikt.core.PrintMessage("OPENROUTER_KEY is not set (login-shell env).", statusCode = 2)
        val ids = dev.supervend.llm.FreeModels.list(key)
        for (id in ids) echo(id)
        echo("# ${ids.size} free models", err = true)
    }
}

/** `harness org` — print the company roster (personas spec §2: the org chart IS the system inventory). */
class Org : CliktCommand() {
    override fun run() {
        val reg = PersonaRegistry.DEFAULT
        echo("Persona registry ${reg.hash().hex.take(12)} — ${reg.personas.size} positions")
        for (p in reg.personas.sortedBy { it.role.value }) {
            val staffing = when (val s = p.staffing) {
                is Staffing.Script -> "Script(${s.fnRef.id})"
                is Staffing.Model -> "Model(${s.slot.value})"
            }
            echo("  ${p.role.value.padEnd(20)} v${p.version}  ${staffing.padEnd(24)} → ${p.outputContract.id}")
        }
    }
}

fun main(args: Array<String>) =
    Harness().subcommands(Qualify(), Baseline(), Run(), Report(), Validate(), Models(), Plan(), Org()).main(args)
