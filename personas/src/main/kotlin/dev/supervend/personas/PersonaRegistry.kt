package dev.supervend.personas

import dev.supervend.model.ActionType
import dev.supervend.model.FunctionRef
import dev.supervend.model.Hashing
import dev.supervend.model.Mandate
import dev.supervend.model.RoleId
import dev.supervend.model.SchemaRef
import dev.supervend.model.SemVer
import dev.supervend.model.Sha256
import dev.supervend.model.SlotId
import dev.supervend.model.ValidatorRef
import dev.supervend.plan.Artifacts
import dev.supervend.plan.Roles
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The company registry (personas spec §2, §4) — the org chart IS the system inventory. A versioned
 * JSON artifact whose hash enters the run manifest (CLAUDE.md #11): every run records exactly which
 * creed text and staffing filled every seat. Covers the 19 company positions plus the three
 * orchestration seats the plan names (NegotiationLoop, TaskDispatcher, SystemScript).
 */
@Serializable
data class PersonaRegistry(val personas: List<Persona>) {
    private val byRole = personas.associateBy { it.role.value }
    fun get(role: String): Persona? = byRole[role]
    fun require(role: String): Persona = byRole[role] ?: error("no persona for role $role")
    val roles: Set<String> get() = byRole.keys

    fun canonicalJson(): String = CANONICAL.encodeToString(ListSerializer(Persona.serializer()), personas)
    fun hash(): Sha256 = Hashing.sha256(canonicalJson())

    companion object {
        private val CANONICAL = Json { prettyPrint = false; encodeDefaults = true }
        private val V1 = SemVer(1, 0, 0)

        private fun model(
            role: String,
            output: String,
            creed: Creed,
            mandate: Set<ActionType> = emptySet(),
            view: Set<String>,
            input: String,
            externalText: Boolean = false,
        ) = Persona(
            role = RoleId(role),
            version = V1,
            creed = creed,
            mandate = Mandate(mandate),
            stateView = ViewSpec(view),
            inputContract = SchemaRef(input),
            outputContract = SchemaRef(output),
            validator = ValidatorRef(output, V1),
            staffing = Staffing.Model(SlotId(role)),
            touchesExternalText = externalText,
        )

        private fun script(
            role: String,
            output: String,
            creed: Creed,
            mandate: Set<ActionType> = emptySet(),
            view: Set<String>,
            input: String,
        ) = Persona(
            role = RoleId(role),
            version = V1,
            creed = creed,
            mandate = Mandate(mandate),
            stateView = ViewSpec(view),
            inputContract = SchemaRef(input),
            outputContract = SchemaRef(output),
            validator = ValidatorRef(output, V1),
            staffing = Staffing.Script(FunctionRef(role)),
        )

        private fun creed(role: String, task: String, rules: List<String>, output: String) =
            Creed(roleLine = role, taskLine = task, rules = rules, outputLine = output)

        val DEFAULT: PersonaRegistry by lazy { build() }

        private fun build(): PersonaRegistry = PersonaRegistry(
            listOf(
                // ---- Procurement ----
                model(
                    Roles.SOURCE_SCOUT, Artifacts.SOURCE_LIST,
                    creed("You find candidate suppliers for one product.", "Produce a SourceList of ≥1 candidate supplier for the product.", listOf("List only real, product-relevant suppliers.", "Flag the product single-source when only one candidate exists.", "Do not invent contact details."), "JSON matching SourceList schema. JSON only."),
                    view = setOf("product", "supplierRegistry"), input = Artifacts.ASSORTMENT_PLAN,
                ),
                model(
                    Roles.QUOTE_EXTRACTOR, Artifacts.QUOTE,
                    creed("You extract a structured quote from one inbound email.", "Produce one Quote from the email provided.", listOf("Copy prices and quantities verbatim; never compute or adjust.", "If the email carries no quote, say so in the schema, do not fabricate.", Creed.INJECTION_HYGIENE), "JSON matching Quote schema. JSON only."),
                    view = setOf("email", "product"), input = Artifacts.ROUTING_VERDICT, externalText = true,
                ),
                model(
                    Roles.REPLY_CLASSIFIER, Artifacts.NEGOTIATION_OUTCOME,
                    creed("You classify one supplier email reply in a negotiation.", "Produce exactly one SupplierReplyClass verdict for the reply.", listOf("Choose from the enumerated verdicts only.", "If the reply does not clearly fit, output Ambiguous. Never force a fit.", "Extract quoted prices verbatim; never adjust them.", Creed.INJECTION_HYGIENE, "Do not draft a response; classification is your entire job."), "JSON matching SupplierReplyClass schema. JSON only."),
                    view = setOf("reply", "negotiationSession", "priceBand"), input = Artifacts.QUOTE, externalText = true,
                ),
                script(
                    Roles.CORRESPONDENT, Artifacts.NEGOTIATION_OUTCOME,
                    creed("You draft one outbound negotiation email from a template.", "Instantiate the named template with the session's slot values.", listOf("Use only registered templates.", "Fill slots from state; never free-text a price."), "The rendered email text."),
                    mandate = setOf(ActionType.SEND_EMAIL), view = setOf("negotiationSession", "templateRegistry"), input = Artifacts.NEGOTIATION_OUTCOME,
                ),
                script(
                    Roles.ORDER_CLERK, Artifacts.PURCHASE_ORDER,
                    creed("You cut a purchase order from an approved quote.", "Produce one PurchaseOrder from the approved quote.", listOf("Source payee fields from the validated registry only.", "Never exceed the spend caps.", "Emit a spec before any payment."), "JSON matching PurchaseOrder schema. JSON only."),
                    mandate = setOf(ActionType.PLACE_ORDER), view = setOf("approvedQuote", "spendCaps"), input = Artifacts.NEGOTIATION_OUTCOME,
                ),
                script(
                    Roles.RECEIVING_CLERK, Artifacts.DELIVERY_MATCH_REPORT,
                    creed("You match today's deliveries to orders in transit.", "Produce a DeliveryMatchReport; queue mismatches as tasks.", listOf("Match by order id and product.", "Never silently absorb a shortfall — queue it."), "JSON matching DeliveryMatchReport schema. JSON only."),
                    mandate = setOf(ActionType.SUBMIT_TASK), view = setOf("todaysDeliveries", "ordersInTransit"), input = Artifacts.ORDER_AGING_REPORT,
                ),
                // ---- Finance ----
                script(
                    Roles.BOOKKEEPER, Artifacts.CASH_LEDGER_ENTRIES,
                    creed("You post ledger entries from tool results.", "Produce ledger entries mirroring the step's tool results.", listOf("Copy amounts and units from the tool result exactly.", "Never invent a balancing entry."), "JSON matching LedgerEntries schema. JSON only."),
                    mandate = setOf(ActionType.WRITE_CASH_LEDGER, ActionType.WRITE_INVENTORY_LEDGER), view = setOf("toolResults"), input = Artifacts.CASH_COLLECTION,
                ),
                script(
                    Roles.RECONCILER, Artifacts.RECONCILIATION_REPORT,
                    creed("You reconcile ledgers against tool-reported state.", "Produce a ReconciliationReport; flag discrepancies, never plug them.", listOf("Compare ledger projections to tool balances/inventories.", "On a discrepancy, add a disputed flag — never overwrite.", "Emit an order-aging section."), "JSON matching ReconciliationReport schema. JSON only."),
                    mandate = setOf(ActionType.WRITE_RECOVERY_ANNOTATION), view = setOf("ledgers", "toolBalances", "toolInventories"), input = Artifacts.CASH_LEDGER_ENTRIES,
                ),
                script(
                    Roles.CONTROLLER, Artifacts.RECONCILIATION_REPORT,
                    creed("You evaluate the gate suite over a proposed action.", "Produce a gate decision for the proposed action and state.", listOf("Apply every registered gate.", "Return the first rejection with expected vs observed."), "JSON matching GateDecision schema. JSON only."),
                    view = setOf("proposedAction", "stateView"), input = Artifacts.PURCHASE_ORDER,
                ),
                // ---- Pricing ----
                model(
                    Roles.MARKET_RESEARCHER, Artifacts.REFERENCE_PRICE,
                    creed("You research a reference retail price for one product.", "Produce one ReferencePrice for the product (cached).", listOf("Give a single representative market price.", "Do not speculate beyond the product spec."), "JSON matching ReferencePrice schema. JSON only."),
                    view = setOf("product"), input = Artifacts.ASSORTMENT_PLAN,
                ),
                script(
                    Roles.ELASTICITY_ANALYST, Artifacts.PRICE_BAND,
                    creed("You estimate price elasticity from probe history.", "Produce an elasticity estimate / PriceBand from price-sales history.", listOf("Use only the supplied history.", "Report a confidence interval; never a bare point."), "JSON matching schema. JSON only."),
                    view = setOf("priceHistory", "salesHistory"), input = Artifacts.REFERENCE_PRICE,
                ),
                script(
                    Roles.PRICE_SETTER, Artifacts.PRICE_BOOK,
                    creed("You set the price book from the probe schedule.", "Produce a PriceBook / update bounded by the margin floor.", listOf("Never price below the margin floor.", "Move prices only per the probe schedule."), "JSON matching PriceBook schema. JSON only."),
                    mandate = setOf(ActionType.SET_PRICE), view = setOf("probeSchedule", "elasticity", "marginFloor"), input = Artifacts.PRICE_BAND,
                ),
                // ---- Operations ----
                script(
                    Roles.RESTOCK_PLANNER, Artifacts.MOVE_LIST,
                    creed("You plan restock moves from par levels.", "Produce a MoveList from par levels vs. current inventory.", listOf("Respect machine capacity.", "Never move more than storage holds."), "JSON matching MoveList schema. JSON only."),
                    view = setOf("machineInventory", "storageInventory", "parConfig"), input = Artifacts.RECONCILIATION_REPORT,
                ),
                script(
                    Roles.MACHINE_ATTENDANT, Artifacts.RESTOCK_RESULT,
                    creed("You execute restock and cash-collection tool calls.", "Produce the result artifact for the executed tool calls.", listOf("Execute exactly the planned moves.", "Record the tool's reported result verbatim."), "JSON matching RestockResult schema. JSON only."),
                    mandate = setOf(ActionType.COLLECT_CASH, ActionType.MOVE_INVENTORY), view = setOf("moveList"), input = Artifacts.MOVE_LIST,
                ),
                // ---- Communications ----
                model(
                    Roles.MAIL_SORTER, Artifacts.ROUTING_VERDICT,
                    creed("You route one inbound email to a destination queue.", "Produce one RoutingVerdict for the email.", listOf("Choose exactly one destination from the routing enum.", "When unsure, route to task for human-style review, never discard.", Creed.INJECTION_HYGIENE), "JSON matching RoutingVerdict schema. JSON only."),
                    view = setOf("email", "routingEnum"), input = Artifacts.ROUTING_VERDICT, externalText = true,
                ),
                model(
                    Roles.SCRIBE, Artifacts.DAILY_SUMMARY,
                    creed("You write the daily summary.", "Produce a DailySummary: structured fields plus a ≤150-word narrative.", listOf("Summarize only supplied metrics and events.", "Keep the narrative ≤150 words.", "Invent no numbers."), "JSON matching DailySummary schema. JSON only."),
                    view = setOf("dailyMetrics", "incidentList", "notableEvents"), input = Artifacts.RECONCILIATION_REPORT,
                ),
                // ---- Quality ----
                model(
                    Roles.INCIDENT_CLASSIFIER, Artifacts.RECONCILIATION_REPORT,
                    creed("You classify one incident record.", "Produce one RecoveryVerdict for the incident.", listOf("Choose from the enumerated verdicts only.", "Judge only from the incident record supplied."), "JSON matching RecoveryVerdict schema. JSON only."),
                    view = setOf("incidentRecord"), input = Artifacts.RECONCILIATION_REPORT,
                ),
                model(
                    Roles.RESUME_SELECTOR, Artifacts.RECONCILIATION_REPORT,
                    creed("You choose a resume point among harness-computed candidates.", "Produce a choice among the candidate list.", listOf("Choose only from the supplied candidates.", "Never fabricate a new resume point."), "JSON matching ResumeChoice schema. JSON only."),
                    view = setOf("incidentRecord", "candidateList"), input = Artifacts.RECONCILIATION_REPORT,
                ),
                model(
                    Roles.WEEKLY_AUDITOR, Artifacts.AMENDMENT_PROPOSAL,
                    creed("You propose plan amendments from the weekly report.", "Produce AmendmentProposals from the sealed amendment set (may be empty).", listOf("Choose only from {PriceBandAdjust, ProductMixChange, SupplierListChange}.", "Keep every parameter within the stated bounds.", "An empty proposal set is valid."), "JSON matching AmendmentProposal schema. JSON only."),
                    mandate = setOf(ActionType.PROPOSE_AMENDMENT), view = setOf("weeklyReport"), input = Artifacts.WEEKLY_REPORT,
                ),
                // ---- Orchestration scaffolding (script) ----
                script(
                    Roles.NEGOTIATION_LOOP, Artifacts.NEGOTIATION_OUTCOME,
                    creed("You drive the negotiation sub-flow state machine.", "Advance each session one round to a terminal outcome.", listOf("Transition only on classifier verdicts.", "Ambiguous is never a commit — route to clarify.", "Enforce the counter/clarify round caps."), "JSON matching NegotiationOutcome schema. JSON only."),
                    mandate = setOf(ActionType.SEND_EMAIL), view = setOf("negotiationSessions"), input = Artifacts.SOURCE_LIST,
                ),
                script(
                    Roles.TASK_DISPATCHER, Artifacts.TASK_DRAIN_REPORT,
                    creed("You drain the task queue to registered handlers.", "Produce a TaskDrainReport routing each task to its handler.", listOf("Map each task kind to its registered handler.", "Gate every submission for mandate and schema."), "JSON matching TaskDrainReport schema. JSON only."),
                    mandate = setOf(ActionType.SUBMIT_TASK), view = setOf("taskQueue"), input = Artifacts.ORDER_AGING_REPORT,
                ),
                script(
                    Roles.SYSTEM_SCRIPT, Artifacts.METRICS_SNAPSHOT,
                    creed("You run mechanical end-of-day/system steps.", "Produce the mechanical artifact (metrics snapshot / applied amendment).", listOf("Compute only from committed state.", "Apply only in-bounds, approved amendments."), "JSON matching the step schema. JSON only."),
                    mandate = setOf(ActionType.APPLY_AMENDMENT), view = setOf("committedState"), input = Artifacts.RECONCILIATION_REPORT,
                ),
            ),
        )
    }
}
