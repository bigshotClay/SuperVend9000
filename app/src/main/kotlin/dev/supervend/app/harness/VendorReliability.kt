package dev.supervend.app.harness

import dev.supervend.model.ProductId
import dev.supervend.model.SupplierId

/**
 * Deterministic vendor-reliability learning (PRD R14: supplier reliability record) — the defense the
 * model can't provide from an email alone. A supplier lies about *delivery*, not price: the
 * bait-and-switch ships short, the scammer and the folded vendor ship nothing. Those only show up
 * after the fact, so the harness matches each order against what actually arrived and, once an order
 * is *mature* (enough days for even a slow shipper to deliver), scores the supplier. Under-deliverers
 * are dropped from future candidate sets — the harness gets burned at most once per bad vendor.
 *
 * Long-horizon safe: matured orders fold into per-supplier running totals and are dropped, so only
 * the small set of in-flight orders is ever scanned. Memory and per-call cost stay bounded across a
 * 10,000-day run instead of growing with the tens of thousands of lifetime orders.
 */
class VendorReliability(
    private val maturityDays: Int,
    /** Minimum mature delivered/ordered ratio to keep using a supplier. */
    private val floor: Double = 0.9,
) {
    private data class Order(val supplier: SupplierId, val product: ProductId, val units: Int, val day: Int, var delivered: Int = 0)

    private val active = LinkedHashMap<String, Order>()          // in-flight (not yet matured)
    private val maturedOrdered = mutableMapOf<SupplierId, Long>() // folded aggregates
    private val maturedDelivered = mutableMapOf<SupplierId, Long>()

    fun recordOrder(rec: SimWorld.OrderRec) {
        active[rec.orderId] = Order(rec.supplier, rec.product, rec.units, rec.day)
    }

    /** Credit a delivery (partial deliveries accumulate) against its originating in-flight order. */
    fun recordDelivery(orderId: String, units: Int) {
        active[orderId]?.let { it.delivered += units }
    }

    /** Fold now-mature orders into per-supplier totals and evict them (called before any query). */
    private fun promote(today: Int) {
        val it = active.values.iterator()
        while (it.hasNext()) {
            val o = it.next()
            if (today - o.day >= maturityDays) {
                maturedOrdered[o.supplier] = maturedOrdered.getOrDefault(o.supplier, 0L) + o.units
                maturedDelivered[o.supplier] = maturedDelivered.getOrDefault(o.supplier, 0L) + o.delivered
                it.remove()
            }
        }
    }

    /**
     * Units still legitimately in transit for [product] — placed, not yet fully delivered, still
     * within its delivery window. The daily loop skips procurement while pending stock covers the
     * target, keeping LLM calls far under budget instead of re-RFQing the roster every day.
     */
    fun pendingUnits(product: ProductId, today: Int): Int {
        promote(today)
        return active.values.filter { it.product == product && it.delivered < it.units }
            .sumOf { it.units - it.delivered }
    }

    /**
     * Whether to keep sourcing from [supplier]. No mature orders yet ⇒ benefit of the doubt; once
     * mature orders exist, the delivered/ordered ratio must clear the [floor].
     */
    fun acceptable(supplier: SupplierId, today: Int): Boolean {
        promote(today)
        val ordered = maturedOrdered.getOrDefault(supplier, 0L)
        if (ordered == 0L) return true
        return maturedDelivered.getOrDefault(supplier, 0L).toDouble() / ordered >= floor
    }

    /** Mature reliability score in [0,1], or null when the vendor has no mature history yet. */
    fun score(supplier: SupplierId, today: Int): Double? {
        promote(today)
        val ordered = maturedOrdered.getOrDefault(supplier, 0L)
        return if (ordered == 0L) null else maturedDelivered.getOrDefault(supplier, 0L).toDouble() / ordered
    }
}
