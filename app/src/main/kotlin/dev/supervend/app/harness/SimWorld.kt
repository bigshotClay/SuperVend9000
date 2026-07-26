package dev.supervend.app.harness

import dev.supervend.model.Cents
import dev.supervend.model.Hashing
import dev.supervend.model.Predicate
import dev.supervend.model.PredicateResult
import dev.supervend.model.ProductId
import dev.supervend.model.ProposedAction
import dev.supervend.model.SanityBand
import dev.supervend.model.Severity
import dev.supervend.model.StateSnapshotRef
import dev.supervend.model.SupplierId
import dev.supervend.orchestrator.StepWorld
import dev.supervend.plan.StepDef
import dev.supervend.sim.EnvironmentPort
import dev.supervend.sim.SimConfig

/**
 * The [StepWorld] the harnessed run submits actions against — the bridge between the deterministic
 * gate suite and the live sim. Gate facts (cost basis, validated payees, spec-on-file, day spend,
 * sanity band) come from config and tracked state; [apply] is the *only* place an allowed action
 * reaches the sim, so the "single writer, gate-then-apply-atomically" invariant holds literally
 * (CLAUDE.md #10).
 */
class SimWorld(
    private val env: EnvironmentPort,
    private val config: SimConfig,
    validated: Set<SupplierId> = emptySet(),
) : StepWorld {
    private var spentToday: Cents = Cents.ZERO
    // Real cost basis: weighted-average of what we ACTUALLY paid per unit (not the hidden wholesale).
    // A vendor squeezing its price up raises this, tightening our margin — the "pushed to the edge".
    private val paidUnits = mutableMapOf<ProductId, Long>()
    private val paidCost = mutableMapOf<ProductId, Long>()
    // A supplier is validated only once it has EARNED it — passed reply classification (not a scam)
    // and the quote-sanity check. ValidatedPayee then guarantees we never pay an un-vetted contact.
    private val validatedSuppliers: MutableSet<SupplierId> = validated.toMutableSet()
    private var bandOverride: Map<ProductId, SanityBand> = emptyMap()
    // Orders placed but not yet drained by the reliability tracker — keyed by the sim's order id so
    // deliveries (which carry that id) can be attributed back to the supplier that promised them.
    private val newOrders = mutableListOf<OrderRec>()

    /** One placed order, for delivery reconciliation (bait-and-switch / non-delivery detection). */
    data class OrderRec(val orderId: String, val supplier: SupplierId, val product: ProductId, val units: Int, val day: Int)

    /** Drain the orders placed since the last call (the reliability tracker consumes these). */
    fun takeNewOrders(): List<OrderRec> = newOrders.toList().also { newOrders.clear() }

    /** Reset the per-day spend accumulator (feeds the SpendCap daily cap). */
    fun startDay() { spentToday = Cents.ZERO }

    /** Mark a supplier vetted (earned by passing classification + sanity), so ValidatedPayee allows it. */
    fun validate(supplier: SupplierId) { validatedSuppliers += supplier }

    /** Set the researched unit-cost sanity band per product (from the live quote cluster). */
    fun setSanityBands(bands: Map<ProductId, SanityBand>) { bandOverride = bands }

    fun machineOf(product: ProductId): Int = env.machineInventory().firstOrNull { it.product == product }?.units ?: 0
    fun storageOf(product: ProductId): Int = env.storageInventory().firstOrNull { it.product == product }?.units ?: 0
    fun onHand(product: ProductId): Int = machineOf(product) + storageOf(product)

    // ---- StateView ----
    /** Real weighted-average paid price; before any purchase, the product's reference-derived guess. */
    override fun costBasis(product: ProductId): Cents {
        val u = paidUnits.getOrDefault(product, 0L)
        if (u > 0L) return Cents(paidCost.getValue(product) / u)
        return config.products.firstOrNull { it.id == product }?.wholesaleCostCents ?: Cents.ZERO
    }

    override fun supplierValidated(supplier: SupplierId): Boolean = supplier in validatedSuppliers
    override fun specOnFile(supplier: SupplierId, product: ProductId): Boolean = supplier in validatedSuppliers
    override fun spendCommittedToday(): Cents = spentToday
    override fun sanityBand(product: ProductId): SanityBand =
        bandOverride[product] ?: SanityBand(Cents.ZERO, costBasis(product).scaleBps(dev.supervend.model.BasisPoints(30_000)))

    // ---- ValidationState ----
    override fun productExists(product: ProductId): Boolean = config.products.any { it.id == product }
    override fun supplierExists(supplier: SupplierId): Boolean = config.suppliers.any { it.id == supplier }

    // ---- StepWorld ----
    override fun evaluate(predicate: Predicate): PredicateResult = PredicateResult(predicate, held = true, observed = "sim")
    override fun currentDay(): Int = env.currentDay()
    override fun taskAvailableFor(step: StepDef): Boolean = false
    override fun enqueueTask(kind: String, severity: Severity) = Unit
    override fun snapshotRef(): StateSnapshotRef = StateSnapshotRef(env.currentDay(), Hashing.sha256("sim-day-${env.currentDay()}"))

    /** The single write path: an allowed action reaches the sim only here. */
    override fun apply(action: ProposedAction) {
        when (action) {
            is ProposedAction.SetPrice -> env.setPrice(action.product, action.price)
            is ProposedAction.PlaceOrder -> {
                val result = env.placeOrder(action.supplier, action.product, action.units, action.unitCost)
                if (result is dev.supervend.sim.ToolResult.Ok) {
                    newOrders += OrderRec(result.detail, action.supplier, action.product, action.units, env.currentDay())
                    spentToday += action.total
                    paidUnits[action.product] = paidUnits.getOrDefault(action.product, 0L) + action.units
                    paidCost[action.product] = paidCost.getOrDefault(action.product, 0L) + action.total.value
                }
            }
            else -> Unit
        }
    }
}
