package dev.supervend.model

/**
 * A write intent submitted to the gate suite before it is applied (software spec §5.6: gate check
 * and write apply atomically inside the StateActor). Each variant carries the intrinsic facts of
 * the proposal; contextual facts come from [StateView]. Gates are pure functions of (action, view).
 *
 * `actionType` ties a proposal to the [ActionType] its producing step must have in its mandate —
 * the closure test uses it to prove no ungated discretionary money path exists.
 */
sealed interface ProposedAction {
    val actionType: ActionType

    /** Set a retail price. MarginFloorGate checks it against [StateView.costBasis]. */
    data class SetPrice(val product: ProductId, val price: Cents) : ProposedAction {
        override val actionType get() = ActionType.SET_PRICE
    }

    /**
     * Place (and prepay) a supplier order. Guarded by SpecBeforePay, ValidatedPayee, and SpendCap.
     * The unit cost and units are intrinsic; supplier validation and spec presence come from state.
     */
    data class PlaceOrder(
        val supplier: SupplierId,
        val product: ProductId,
        val units: Int,
        val unitCost: Cents,
    ) : ProposedAction {
        override val actionType get() = ActionType.PLACE_ORDER
        val total: Cents get() = unitCost * units
    }

    /** Offer a concession in a negotiation. ConcessionPolicyGate caps it (PRD R22). */
    data class ConcessionOffer(val session: String, val concessionBps: BasisPoints) : ProposedAction {
        override val actionType get() = ActionType.SEND_EMAIL
    }

    /**
     * Accept a supplier quote. QuoteSanityGate checks the band and the MAD outlier test against the
     * session's parallel quotes (PRD R23).
     */
    data class AcceptQuote(
        val quote: QuoteRef,
        val product: ProductId,
        val unitCost: Cents,
        val parallelUnitCosts: List<Cents>,
    ) : ProposedAction {
        override val actionType get() = ActionType.PLACE_ORDER
    }

    /**
     * A recovery-module write. RecoveryWriteGate rejects anything that moves a balance or overwrites
     * (PRD R24) — belt to the DAO's suspenders (CLAUDE.md #5).
     */
    data class RecoveryWrite(val movesBalance: Boolean, val overwrites: Boolean, val annotationOnly: Boolean) : ProposedAction {
        override val actionType get() = ActionType.WRITE_RECOVERY_ANNOTATION
    }

    /** An extemporaneous task submission. TaskMandateGate checks mandate + schema validity (PRD R25). */
    data class SubmitTask(val kind: String, val schemaValid: Boolean, val withinMandate: Boolean) : ProposedAction {
        override val actionType get() = ActionType.SUBMIT_TASK
    }

    /**
     * A Bookkeeper ledger-mirror write. LedgerConsistencyGate rejects any row whose [claimed] figure
     * disagrees with the [source] tool result it mirrors — closing the "no ungated path writes cash"
     * invariant (workplan §6.2, OPERATOR-INPUT #10) on the mirror steps too. [ledger] must be a
     * ledger-write action type.
     */
    data class WriteLedger(val ledger: ActionType, val claimed: Long, val source: Long) : ProposedAction {
        init {
            require(ledger == ActionType.WRITE_CASH_LEDGER || ledger == ActionType.WRITE_INVENTORY_LEDGER) {
                "WriteLedger.ledger must be a ledger-write action, was $ledger"
            }
        }
        override val actionType get() = ledger
    }
}
