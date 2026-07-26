package dev.supervend.plan

import dev.supervend.model.ActionType
import dev.supervend.model.ArtifactBinding
import dev.supervend.model.Cents
import dev.supervend.model.DayPhase
import dev.supervend.model.GateId
import dev.supervend.model.Mandate
import dev.supervend.model.Predicate
import dev.supervend.model.SchemaRef
import dev.supervend.model.Scope
import dev.supervend.model.Severity
import dev.supervend.model.StepId
import dev.supervend.model.ValidatorRef
import dev.supervend.model.SemVer

/**
 * Master Workplan v1, encoded step-for-step from `docs/spec/vending-harness-workplan.md`. This object
 * IS the normative plan; its JSON export ([Plan.prettyJson]) is the review artifact checked against
 * the doc. Gates are listed exactly as the tables specify; severity B = BLOCKING, N = NON_BLOCKING.
 *
 * Preconditions that the doc parameterizes at runtime (e.g. `CashAtLeast(order total)`,
 * `OrderInState(quote approved)`) are encoded with the correct [Predicate] variant and a
 * runtime-bound placeholder value; the variant is what the closure test verifies.
 */
object Workplan {

    private val V1 = SemVer(1, 0, 0)
    private const val B = "B"
    private const val N = "N"

    private fun av(step: String, artifact: String): Predicate =
        Predicate.ArtifactValidated(ArtifactBinding(StepId(step), SchemaRef(artifact)))

    private fun step(
        id: String,
        phase: DayPhase,
        role: String,
        output: String,
        pre: List<Predicate> = emptyList(),
        gates: List<String> = emptyList(),
        mandate: Set<ActionType> = emptySet(),
        sev: String = B,
        consumers: List<String> = emptyList(),
        firesOnlyOnTask: Boolean = false,
    ): StepDef = StepDef(
        id = StepId(id),
        phase = phase,
        kind = StepKind.PersonaStep(role, firesOnlyOnTask),
        mandate = Mandate(mandate),
        preconditions = pre,
        inputs = pre.filterIsInstance<Predicate.ArtifactValidated>().map { it.binding },
        outputSchema = SchemaRef(output),
        validator = ValidatorRef(output, V1),
        gates = gates.map { GateId(it) },
        severityOnExhaustion = if (sev == B) Severity.BLOCKING else Severity.NON_BLOCKING,
        consumers = consumers.map { StepId(it) },
    )

    val PLAN: Plan = Plan(
        name = "Master Workplan v1",
        bootstrap = bootstrap(),
        dailyLoop = dailyLoop(),
        weeklyAudit = weeklyAudit(),
    )

    // ---- §2 Bootstrap (Day 0) ----
    private fun bootstrap(): List<StepDef> = listOf(
        step("BOOT-01", DayPhase.RECEIVE, Roles.PRICE_SETTER, Artifacts.ASSORTMENT_PLAN, sev = B, consumers = listOf("BOOT-02", "BOOT-03")),
        step("BOOT-02", DayPhase.RECEIVE, Roles.MARKET_RESEARCHER, Artifacts.REFERENCE_PRICE, pre = listOf(av("BOOT-01", Artifacts.ASSORTMENT_PLAN)), sev = B, consumers = listOf("BOOT-04")),
        step("BOOT-03", DayPhase.RECEIVE, Roles.SOURCE_SCOUT, Artifacts.SOURCE_LIST, pre = listOf(av("BOOT-01", Artifacts.ASSORTMENT_PLAN)), sev = B, consumers = listOf("BOOT-05")),
        step("BOOT-04", DayPhase.RECEIVE, Roles.ELASTICITY_ANALYST, Artifacts.PRICE_BAND, pre = listOf(av("BOOT-02", Artifacts.REFERENCE_PRICE)), sev = B, consumers = listOf("BOOT-05", "BOOT-08")),
        step("BOOT-05", DayPhase.RECEIVE, Roles.NEGOTIATION_LOOP, Artifacts.NEGOTIATION_OUTCOME, pre = listOf(av("BOOT-03", Artifacts.SOURCE_LIST), av("BOOT-04", Artifacts.PRICE_BAND)), gates = listOf("QuoteSanity", "ConcessionPolicy"), mandate = setOf(ActionType.SEND_EMAIL), sev = B, consumers = listOf("BOOT-06")),
        step("BOOT-06", DayPhase.RECEIVE, Roles.ORDER_CLERK, Artifacts.PURCHASE_ORDER, pre = listOf(Predicate.CashAtLeast(Cents.ZERO)), gates = listOf("SpecBeforePay", "ValidatedPayee", "SpendCap"), mandate = setOf(ActionType.PLACE_ORDER), sev = B, consumers = listOf("BOOT-07")),
        step("BOOT-07", DayPhase.RECEIVE, Roles.BOOKKEEPER, Artifacts.CASH_LEDGER_ENTRIES, pre = listOf(av("BOOT-06", Artifacts.PURCHASE_ORDER)), gates = listOf("LedgerConsistency"), mandate = setOf(ActionType.WRITE_CASH_LEDGER), sev = B),
        step("BOOT-08", DayPhase.RECEIVE, Roles.PRICE_SETTER, Artifacts.PRICE_BOOK, pre = listOf(av("BOOT-04", Artifacts.PRICE_BAND)), gates = listOf("MarginFloor"), mandate = setOf(ActionType.SET_PRICE), sev = B),
        step("BOOT-09", DayPhase.RECEIVE, Roles.SCRIBE, Artifacts.DAY_ZERO_SUMMARY, sev = N),
    )

    // ---- §3 Daily Loop (Day ≥ 1) ----
    private fun dailyLoop(): List<StepDef> = listOf(
        // RECEIVE
        step("RCV-01", DayPhase.RECEIVE, Roles.RECEIVING_CLERK, Artifacts.DELIVERY_MATCH_REPORT, pre = listOf(Predicate.NoOpenBlockingIncidents(Scope.LEDGER)), sev = B, consumers = listOf("RCV-02")),
        step("RCV-02", DayPhase.RECEIVE, Roles.BOOKKEEPER, Artifacts.INVENTORY_LEDGER_ENTRIES, pre = listOf(av("RCV-01", Artifacts.DELIVERY_MATCH_REPORT)), gates = listOf("LedgerConsistency"), mandate = setOf(ActionType.WRITE_INVENTORY_LEDGER), sev = B),
        step("RCV-03", DayPhase.RECEIVE, Roles.RECEIVING_CLERK, Artifacts.ORDER_AGING_REPORT, mandate = setOf(ActionType.SUBMIT_TASK), sev = N),
        // OPS
        step("OPS-01", DayPhase.OPS, Roles.MACHINE_ATTENDANT, Artifacts.CASH_COLLECTION, mandate = setOf(ActionType.COLLECT_CASH), sev = B, consumers = listOf("OPS-02")),
        step("OPS-02", DayPhase.OPS, Roles.BOOKKEEPER, Artifacts.CASH_LEDGER_ENTRIES, pre = listOf(av("OPS-01", Artifacts.CASH_COLLECTION)), gates = listOf("LedgerConsistency"), mandate = setOf(ActionType.WRITE_CASH_LEDGER), sev = B),
        step("OPS-03", DayPhase.OPS, Roles.RESTOCK_PLANNER, Artifacts.MOVE_LIST, sev = B, consumers = listOf("OPS-04")),
        step("OPS-04", DayPhase.OPS, Roles.MACHINE_ATTENDANT, Artifacts.RESTOCK_RESULT, pre = listOf(av("OPS-03", Artifacts.MOVE_LIST)), mandate = setOf(ActionType.MOVE_INVENTORY), sev = B, consumers = listOf("OPS-05")),
        step("OPS-05", DayPhase.OPS, Roles.BOOKKEEPER, Artifacts.INVENTORY_LEDGER_ENTRIES, pre = listOf(av("OPS-04", Artifacts.RESTOCK_RESULT)), gates = listOf("LedgerConsistency"), mandate = setOf(ActionType.WRITE_INVENTORY_LEDGER), sev = B),
        // COMMS
        step("COM-01", DayPhase.COMMS, Roles.MAIL_SORTER, Artifacts.ROUTING_VERDICT, sev = N, consumers = listOf("COM-02")),
        step("COM-02", DayPhase.COMMS, Roles.QUOTE_EXTRACTOR, Artifacts.QUOTE, pre = listOf(av("COM-01", Artifacts.ROUTING_VERDICT)), sev = N),
        // PROCURE
        step("PRO-01", DayPhase.PROCURE, Roles.RESTOCK_PLANNER, Artifacts.REORDER_NEEDS, pre = listOf(Predicate.LedgerReconciled(dev.supervend.model.LedgerId("inventory"), asOfDay = -1)), sev = B, consumers = listOf("PRO-02")),
        step("PRO-02", DayPhase.PROCURE, Roles.NEGOTIATION_LOOP, Artifacts.NEGOTIATION_OUTCOME, pre = listOf(av("PRO-01", Artifacts.REORDER_NEEDS)), gates = listOf("QuoteSanity", "ConcessionPolicy"), mandate = setOf(ActionType.SEND_EMAIL), sev = N, consumers = listOf("PRO-03")),
        step("PRO-03", DayPhase.PROCURE, Roles.ORDER_CLERK, Artifacts.PURCHASE_ORDER, pre = listOf(Predicate.CashAtLeast(Cents.ZERO)), gates = listOf("SpecBeforePay", "ValidatedPayee", "SpendCap"), mandate = setOf(ActionType.PLACE_ORDER), sev = B, consumers = listOf("PRO-04")),
        step("PRO-04", DayPhase.PROCURE, Roles.BOOKKEEPER, Artifacts.CASH_LEDGER_ENTRIES, pre = listOf(av("PRO-03", Artifacts.PURCHASE_ORDER)), gates = listOf("LedgerConsistency"), mandate = setOf(ActionType.WRITE_CASH_LEDGER), sev = B),
        step("PRO-05", DayPhase.PROCURE, Roles.SOURCE_SCOUT, Artifacts.SOURCE_LIST, sev = N, firesOnlyOnTask = true),
        // PRICE
        step("PRC-01", DayPhase.PRICE, Roles.ELASTICITY_ANALYST, Artifacts.ELASTICITY_UPDATE, sev = N, firesOnlyOnTask = true, consumers = listOf("PRC-02")),
        step("PRC-02", DayPhase.PRICE, Roles.PRICE_SETTER, Artifacts.PRICE_BOOK_UPDATE, pre = listOf(av("PRC-01", Artifacts.ELASTICITY_UPDATE)), gates = listOf("MarginFloor"), mandate = setOf(ActionType.SET_PRICE), sev = B),
        // TASKS
        step("TSK-01", DayPhase.TASKS, Roles.TASK_DISPATCHER, Artifacts.TASK_DRAIN_REPORT, gates = listOf("TaskMandate"), mandate = setOf(ActionType.SUBMIT_TASK), sev = N),
        // CLOSE
        step("CLS-01", DayPhase.CLOSE, Roles.RECONCILER, Artifacts.RECONCILIATION_REPORT, gates = listOf("RecoveryWrite"), mandate = setOf(ActionType.WRITE_RECOVERY_ANNOTATION), sev = B, consumers = listOf("CLS-02", "CLS-03")),
        step("CLS-02", DayPhase.CLOSE, Roles.SCRIBE, Artifacts.DAILY_SUMMARY, pre = listOf(av("CLS-01", Artifacts.RECONCILIATION_REPORT)), sev = N),
        step("CLS-03", DayPhase.CLOSE, Roles.SYSTEM_SCRIPT, Artifacts.METRICS_SNAPSHOT, pre = listOf(av("CLS-01", Artifacts.RECONCILIATION_REPORT)), sev = B),
    )

    // ---- §4 Weekly Audit (every 7th day; replaces TASKS) ----
    private fun weeklyAudit(): List<StepDef> = listOf(
        step("AUD-01", DayPhase.TASKS, Roles.RECONCILER, Artifacts.WEEKLY_REPORT, sev = B, consumers = listOf("AUD-02")),
        step("AUD-02", DayPhase.TASKS, Roles.WEEKLY_AUDITOR, Artifacts.AMENDMENT_PROPOSAL, pre = listOf(av("AUD-01", Artifacts.WEEKLY_REPORT)), gates = listOf("TaskMandate"), mandate = setOf(ActionType.PROPOSE_AMENDMENT), sev = N, consumers = listOf("AUD-03")),
        // AUD-03 amendment-bounds is enforced by the PlanVersionUpdate validator's business layer
        // (reject out-of-bounds with logged reason); Stage 6 may promote it to a dedicated gate.
        step("AUD-03", DayPhase.TASKS, Roles.SYSTEM_SCRIPT, Artifacts.PLAN_VERSION_UPDATE, pre = listOf(av("AUD-02", Artifacts.AMENDMENT_PROPOSAL)), mandate = setOf(ActionType.APPLY_AMENDMENT), sev = B),
        step("AUD-04", DayPhase.TASKS, Roles.SYSTEM_SCRIPT, Artifacts.ESCALATION_ADDENDUM, sev = N),
    )
}
