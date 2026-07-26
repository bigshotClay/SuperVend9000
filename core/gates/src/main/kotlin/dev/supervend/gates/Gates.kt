package dev.supervend.gates

import dev.supervend.model.BasisPoints
import dev.supervend.model.Cents
import dev.supervend.model.GateDecision
import dev.supervend.model.GateId
import dev.supervend.model.ProposedAction
import dev.supervend.model.StateView

/**
 * The registered gate suite (software spec §6, PRD R18–R25). Each is a pure object function; the
 * social-engineering and adversarial-supplier resistance lives *here*, in the gate, not in the
 * model (PRD R22/R23). Gates not matching an action Allow by default.
 */

/** R18: sale price ≥ cost basis × (1 + margin floor). */
class MarginFloorGate(private val cfg: GateConfig) : Gate {
    override val id = GateId("MarginFloor")
    override fun evaluate(action: ProposedAction, state: StateView): GateDecision {
        if (action !is ProposedAction.SetPrice) return GateDecision.Allow
        val cost = state.costBasis(action.product)
        val minPrice = cost.scaleBps(BasisPoints(BasisPoints.ONE_HUNDRED_PERCENT + cfg.marginFloorBps.value))
        return if (action.price >= minPrice) GateDecision.Allow
        else id.reject(
            reason = "price below margin floor",
            expected = "price ≥ ${minPrice.value} (cost ${cost.value} + ${cfg.marginFloorBps.value}bp)",
            observed = "price = ${action.price.value}",
        )
    }
}

/** R19: payments only to suppliers in the validated registry. */
class ValidatedPayeeGate : Gate {
    override val id = GateId("ValidatedPayee")
    override fun evaluate(action: ProposedAction, state: StateView): GateDecision {
        val supplier = when (action) {
            is ProposedAction.PlaceOrder -> action.supplier
            else -> return GateDecision.Allow
        }
        return if (state.supplierValidated(supplier)) GateDecision.Allow
        else id.reject("payee not in validated registry", "supplier validated=true", "supplier ${supplier.value} validated=false")
    }
}

/** R20: spec-before-pay ordering — an order spec must be on file before payment. */
class SpecBeforePayGate : Gate {
    override val id = GateId("SpecBeforePay")
    override fun evaluate(action: ProposedAction, state: StateView): GateDecision {
        if (action !is ProposedAction.PlaceOrder) return GateDecision.Allow
        return if (state.specOnFile(action.supplier, action.product)) GateDecision.Allow
        else id.reject("no order spec on file before payment", "spec on file=true", "spec on file=false")
    }
}

/** R21: single-order cap and total daily spend cap. */
class SpendCapGate(private val cfg: GateConfig) : Gate {
    override val id = GateId("SpendCap")
    override fun evaluate(action: ProposedAction, state: StateView): GateDecision {
        if (action !is ProposedAction.PlaceOrder) return GateDecision.Allow
        val total = action.total
        if (total > cfg.spendCapPerOrder) {
            return id.reject("order exceeds single-order cap", "order ≤ ${cfg.spendCapPerOrder.value}", "order = ${total.value}")
        }
        val dayAfter = state.spendCommittedToday() + total
        if (dayAfter > cfg.spendCapPerDay) {
            return id.reject("order exceeds daily spend cap", "day total ≤ ${cfg.spendCapPerDay.value}", "day total = ${dayAfter.value}")
        }
        return GateDecision.Allow
    }
}

/** R22: concessions beyond policy threshold rejected unconditionally (social-engineering lives here). */
class ConcessionPolicyGate(private val cfg: GateConfig) : Gate {
    override val id = GateId("ConcessionPolicy")
    override fun evaluate(action: ProposedAction, state: StateView): GateDecision {
        if (action !is ProposedAction.ConcessionOffer) return GateDecision.Allow
        return if (action.concessionBps.value <= cfg.concessionCeilingBps.value) GateDecision.Allow
        else id.reject("concession exceeds policy ceiling", "concession ≤ ${cfg.concessionCeilingBps.value}bp", "concession = ${action.concessionBps.value}bp")
    }
}

/**
 * R23: quotes rejected when outside the researched band OR a statistical outlier (MAD test) against
 * parallel quotes. Integer math throughout — the median and MAD are computed on `Long` cents with
 * truncating division, so the verdict is bit-reproducible (CLAUDE.md #4).
 */
class QuoteSanityGate(private val cfg: GateConfig) : Gate {
    override val id = GateId("QuoteSanity")
    override fun evaluate(action: ProposedAction, state: StateView): GateDecision {
        if (action !is ProposedAction.AcceptQuote) return GateDecision.Allow
        val band = state.sanityBand(action.product)
        if (!band.contains(action.unitCost)) {
            return id.reject("quote outside researched band", "unit cost in [${band.low.value}, ${band.high.value}]", "unit cost = ${action.unitCost.value}")
        }
        val costs = action.parallelUnitCosts.map { it.value }
        if (costs.size >= MIN_FOR_MAD) {
            val median = median(costs)
            val mad = median(costs.map { kotlin.math.abs(it - median) })
            if (mad > 0) {
                val deviation = kotlin.math.abs(action.unitCost.value - median)
                // deviation > mad * multiplier  ⇔  deviation*10000 > mad*multiplierBps
                if (deviation * BasisPoints.ONE_HUNDRED_PERCENT > mad * cfg.madMultiplierBps.value) {
                    return id.reject(
                        "quote is a statistical outlier (MAD test)",
                        "|cost - median| ≤ ${cfg.madMultiplierBps.value / 10_000.0}×MAD",
                        "deviation=$deviation median=$median mad=$mad",
                    )
                }
            }
        }
        return GateDecision.Allow
    }

    private companion object {
        const val MIN_FOR_MAD = 3
        fun median(values: List<Long>): Long {
            val s = values.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
        }
    }
}

/** R24: recovery writes may only add information — never move a balance or overwrite. */
class RecoveryWriteGate : Gate {
    override val id = GateId("RecoveryWrite")
    override fun evaluate(action: ProposedAction, state: StateView): GateDecision {
        if (action !is ProposedAction.RecoveryWrite) return GateDecision.Allow
        return if (action.annotationOnly && !action.movesBalance && !action.overwrites) GateDecision.Allow
        else id.reject(
            "recovery write is not additive-only",
            "annotationOnly=true, movesBalance=false, overwrites=false",
            "annotationOnly=${action.annotationOnly}, movesBalance=${action.movesBalance}, overwrites=${action.overwrites}",
        )
    }
}

/**
 * Ledger-integrity gate (workplan §6.2, OPERATOR-INPUT #10). A Bookkeeper mirror-write must post
 * exactly the figure the source tool result reported — it cannot invent or adjust a balance. This
 * makes the "no ungated path writes cash" invariant literal on the mirror steps, complementing the
 * ArtifactValidated precondition. Pure integer comparison.
 */
class LedgerConsistencyGate : Gate {
    override val id = GateId("LedgerConsistency")
    override fun evaluate(action: ProposedAction, state: StateView): GateDecision {
        if (action !is ProposedAction.WriteLedger) return GateDecision.Allow
        return if (action.claimed == action.source) GateDecision.Allow
        else id.reject(
            "ledger entry does not mirror the source tool result",
            "claimed = source (${action.source})",
            "claimed = ${action.claimed}",
        )
    }
}

/** R25: extemporaneous task submissions gated for mandate compliance and schema validity. */
class TaskMandateGate : Gate {
    override val id = GateId("TaskMandate")
    override fun evaluate(action: ProposedAction, state: StateView): GateDecision {
        if (action !is ProposedAction.SubmitTask) return GateDecision.Allow
        if (!action.schemaValid) return id.reject("task fails schema validation", "schemaValid=true", "schemaValid=false")
        if (!action.withinMandate) return id.reject("task outside submitter mandate", "withinMandate=true", "withinMandate=false")
        return GateDecision.Allow
    }
}

/**
 * The full registered suite, keyed by [GateId]. A run pins one [GateConfig]; the registry is built
 * from it so the closure test can resolve every [GateId] a plan step names.
 */
class GateRegistry private constructor(private val gates: Map<GateId, Gate>) {
    constructor(cfg: GateConfig) : this(realSuite(cfg))

    val ids: Set<GateId> get() = gates.keys
    fun get(id: GateId): Gate? = gates[id]
    fun require(id: GateId): Gate = gates[id] ?: error("unknown gate ${id.value}")

    companion object {
        private fun realSuite(cfg: GateConfig): Map<GateId, Gate> = listOf(
            MarginFloorGate(cfg),
            ValidatedPayeeGate(),
            SpecBeforePayGate(),
            SpendCapGate(cfg),
            ConcessionPolicyGate(cfg),
            QuoteSanityGate(cfg),
            RecoveryWriteGate(),
            LedgerConsistencyGate(),
            TaskMandateGate(),
        ).associateBy { it.id }

        /**
         * R1a ablation (gates OFF). Same gate ids as the full suite — so every plan step's declared
         * gate still resolves via [require] and the write path is structurally identical — but every
         * gate is a no-op that returns [GateDecision.Allow]. Isolates "structured call budget +
         * externalized state" from "gates" without touching the plan or StateActor.
         */
        fun passthrough(cfg: GateConfig): GateRegistry =
            GateRegistry(realSuite(cfg).mapValues { (id, _) -> AllowGate(id) })
    }
}

/** A gate that has no opinion — always [GateDecision.Allow]. Used only by [GateRegistry.passthrough]. */
private class AllowGate(override val id: GateId) : Gate {
    override fun evaluate(action: ProposedAction, state: StateView): GateDecision = GateDecision.Allow
}
