package dev.supervend.plan

/**
 * The name catalogs the plan references (workplan §6 invariant 4: the plan references only roles,
 * predicates, gates, and validators that exist in their registries). Keeping the strings in one
 * place lets the persona/validator registries and the closure test agree by construction.
 */

/** The 19 company positions (personas spec §4) plus the orchestrator's own mechanical seats. */
object Roles {
    // Procurement
    const val SOURCE_SCOUT = "SourceScout"
    const val QUOTE_EXTRACTOR = "QuoteExtractor"
    const val REPLY_CLASSIFIER = "ReplyClassifier"
    const val CORRESPONDENT = "Correspondent"
    const val ORDER_CLERK = "OrderClerk"
    const val RECEIVING_CLERK = "ReceivingClerk"

    // Finance
    const val BOOKKEEPER = "Bookkeeper"
    const val RECONCILER = "Reconciler"
    const val CONTROLLER = "Controller"

    // Pricing
    const val MARKET_RESEARCHER = "MarketResearcher"
    const val ELASTICITY_ANALYST = "ElasticityAnalyst"
    const val PRICE_SETTER = "PriceSetter"

    // Operations
    const val RESTOCK_PLANNER = "RestockPlanner"
    const val MACHINE_ATTENDANT = "MachineAttendant"

    // Communications
    const val MAIL_SORTER = "MailSorter"
    const val SCRIBE = "Scribe"

    // Quality
    const val INCIDENT_CLASSIFIER = "IncidentClassifier"
    const val RESUME_SELECTOR = "ResumeSelector"
    const val WEEKLY_AUDITOR = "WeeklyAuditor"

    // Orchestration scaffolding (script-staffed; not among the 19 company seats).
    const val NEGOTIATION_LOOP = "NegotiationLoop"
    const val TASK_DISPATCHER = "TaskDispatcher"
    const val SYSTEM_SCRIPT = "SystemScript"

    /** The full set the plan may name; registries must cover exactly these. */
    val all: Set<String> = setOf(
        SOURCE_SCOUT, QUOTE_EXTRACTOR, REPLY_CLASSIFIER, CORRESPONDENT, ORDER_CLERK, RECEIVING_CLERK,
        BOOKKEEPER, RECONCILER, CONTROLLER,
        MARKET_RESEARCHER, ELASTICITY_ANALYST, PRICE_SETTER,
        RESTOCK_PLANNER, MACHINE_ATTENDANT,
        MAIL_SORTER, SCRIBE,
        INCIDENT_CLASSIFIER, RESUME_SELECTOR, WEEKLY_AUDITOR,
        NEGOTIATION_LOOP, TASK_DISPATCHER, SYSTEM_SCRIPT,
    )

    /** The nine irreducibly-linguistic seats (personas spec §4: LLM headcount 9 of 19). */
    val modelSeats: Set<String> = setOf(
        SOURCE_SCOUT, QUOTE_EXTRACTOR, REPLY_CLASSIFIER, MARKET_RESEARCHER,
        MAIL_SORTER, SCRIBE, INCIDENT_CLASSIFIER, RESUME_SELECTOR, WEEKLY_AUDITOR,
    )
}

/** Output artifact schema names (SchemaRef ids). One per distinct step output type. */
object Artifacts {
    const val ASSORTMENT_PLAN = "AssortmentPlan"
    const val REFERENCE_PRICE = "ReferencePrice"
    const val SOURCE_LIST = "SourceList"
    const val PRICE_BAND = "PriceBand"
    const val NEGOTIATION_OUTCOME = "NegotiationOutcome"
    const val PURCHASE_ORDER = "PurchaseOrder"
    const val CASH_LEDGER_ENTRIES = "CashLedgerEntries"
    const val INVENTORY_LEDGER_ENTRIES = "InventoryLedgerEntries"
    const val PRICE_BOOK = "PriceBook"
    const val DAY_ZERO_SUMMARY = "DayZeroSummary"
    const val DELIVERY_MATCH_REPORT = "DeliveryMatchReport"
    const val ORDER_AGING_REPORT = "OrderAgingReport"
    const val CASH_COLLECTION = "CashCollection"
    const val MOVE_LIST = "MoveList"
    const val RESTOCK_RESULT = "RestockResult"
    const val ROUTING_VERDICT = "RoutingVerdict"
    const val QUOTE = "Quote"
    const val REORDER_NEEDS = "ReorderNeeds"
    const val PRICE_BOOK_UPDATE = "PriceBookUpdate"
    const val ELASTICITY_UPDATE = "ElasticityUpdate"
    const val TASK_DRAIN_REPORT = "TaskDrainReport"
    const val RECONCILIATION_REPORT = "ReconciliationReport"
    const val DAILY_SUMMARY = "DailySummary"
    const val METRICS_SNAPSHOT = "MetricsSnapshot"
    const val WEEKLY_REPORT = "WeeklyReport"
    const val AMENDMENT_PROPOSAL = "AmendmentProposal"
    const val PLAN_VERSION_UPDATE = "PlanVersionUpdate"
    const val ESCALATION_ADDENDUM = "EscalationAddendum"

    val all: Set<String> = setOf(
        ASSORTMENT_PLAN, REFERENCE_PRICE, SOURCE_LIST, PRICE_BAND, NEGOTIATION_OUTCOME, PURCHASE_ORDER,
        CASH_LEDGER_ENTRIES, INVENTORY_LEDGER_ENTRIES, PRICE_BOOK, DAY_ZERO_SUMMARY, DELIVERY_MATCH_REPORT,
        ORDER_AGING_REPORT, CASH_COLLECTION, MOVE_LIST, RESTOCK_RESULT, ROUTING_VERDICT, QUOTE, REORDER_NEEDS,
        PRICE_BOOK_UPDATE, ELASTICITY_UPDATE, TASK_DRAIN_REPORT, RECONCILIATION_REPORT, DAILY_SUMMARY,
        METRICS_SNAPSHOT, WEEKLY_REPORT, AMENDMENT_PROPOSAL, PLAN_VERSION_UPDATE, ESCALATION_ADDENDUM,
    )
}
