package dev.supervend.app.harness

import dev.supervend.model.ProductId
import dev.supervend.model.SupplierId

/**
 * Tit-for-tat vendor relationships (the game-theoretically robust strategy). No vendor is ever
 * permanently trusted; trust is earned by good trades and withdrawn the instant one goes bad:
 *
 *   new/demoted → PROBATION (verify-then-trust: re-RFQ + classify + gate + watch every trade)
 *   PROBATION + good trade → TRUSTED (trust-but-verify: fast-track reorders, but keep watching)
 *   TRUSTED   + bad trade  → PROBATION (trust withdrawn — re-earn it)
 *   PROBATION + 2nd bad    → DROPPED (persistent defection → cut)
 *
 * A "trade" is scored when its order matures (delivery known): good = delivered ≥ [goodRatio] of
 * ordered; bad = short/late/none. This is what a turn-bad vendor (rug-pull, slow-degrader) has to
 * beat — and can't, because farming the trusted state costs it that state on the very next failure.
 *
 * Long-horizon safe: matured orders are folded into the state transition and evicted, so only
 * in-flight orders are ever scanned — bounded memory/cost across a 10,000-day run.
 */
class VendorRelationships(
    private val maturityDays: Int,
    private val goodRatio: Double = 0.95,
    /** Bad trades on probation before the vendor is dropped (2 = forgive once, then cut). */
    private val dropAfterStrikes: Int = 2,
    /** Days a DROPPED vendor is shunned before it may re-enter on probation (concede its way back). */
    private val cooldownDays: Int = maturityDays * 4,
    /** Reliability assumed for a vendor with no delivery history yet (benefit of the doubt). */
    private val priorReliability: Double = 0.6,
) {
    enum class Trust { PROBATION, TRUSTED, DROPPED }

    private data class Rel(
        var state: Trust = Trust.PROBATION,
        var strikes: Int = 0,
        var droppedDay: Int = -1,
        var ordered: Long = 0,   // matured units ordered (for the reliability estimate)
        var delivered: Long = 0, // matured units actually delivered
    )
    private data class Order(val supplier: SupplierId, val product: ProductId, val units: Int, val day: Int, var delivered: Int = 0)

    private val rels = mutableMapOf<SupplierId, Rel>()
    private val active = LinkedHashMap<String, Order>()
    /** Transitions since the last drain — lets the runner clear a preferred vendor that fell from grace. */
    private val demotions = mutableSetOf<SupplierId>()

    fun recordOrder(rec: SimWorld.OrderRec) {
        active[rec.orderId] = Order(rec.supplier, rec.product, rec.units, rec.day)
        rels.getOrPut(rec.supplier) { Rel() }
    }

    fun recordDelivery(orderId: String, units: Int) {
        active[orderId]?.let { it.delivered += units }
    }

    /** Fold matured trades into trust transitions and evict them (called before any query). */
    private fun settle(today: Int) {
        // Re-entry: a shunned vendor returns to probation after the cooldown — a chance to concede
        // back into business (never a permanent blackball, so the harness can't starve itself out).
        for (r in rels.values) {
            if (r.state == Trust.DROPPED && r.droppedDay >= 0 && today - r.droppedDay >= cooldownDays) {
                r.state = Trust.PROBATION; r.strikes = 0; r.droppedDay = -1
            }
        }
        val it = active.values.iterator()
        while (it.hasNext()) {
            val o = it.next()
            if (today - o.day < maturityDays) continue
            val good = o.units == 0 || o.delivered.toDouble() / o.units >= goodRatio
            apply(o.supplier, good, o.units.toLong(), o.delivered.toLong(), today)
            it.remove()
        }
    }

    private fun apply(supplier: SupplierId, good: Boolean, units: Long, delivered: Long, today: Int) {
        val r = rels.getOrPut(supplier) { Rel() }
        r.ordered += units; r.delivered += delivered // reliability estimate accumulates
        when (r.state) {
            Trust.PROBATION -> if (good) { r.state = Trust.TRUSTED; r.strikes = 0 } else {
                r.strikes += 1
                if (r.strikes >= dropAfterStrikes) { r.state = Trust.DROPPED; r.droppedDay = today }
            }
            Trust.TRUSTED -> if (!good) { r.state = Trust.PROBATION; r.strikes = 1; demotions += supplier } // trust withdrawn
            Trust.DROPPED -> {}
        }
    }

    /**
     * Observed delivered/ordered ratio — the input to least-bad selection. A vendor with no mature
     * history gets [priorReliability]. This is what lets the harness pick the *least bad* vendor by
     * expected value (reliability × margin) when no one is clean, instead of blackballing everyone.
     */
    fun reliabilityEstimate(supplier: SupplierId): Double {
        val r = rels[supplier] ?: return priorReliability
        return if (r.ordered == 0L) priorReliability else r.delivered.toDouble() / r.ordered
    }

    fun state(supplier: SupplierId, today: Int): Trust {
        settle(today)
        return rels[supplier]?.state ?: Trust.PROBATION
    }

    fun trusted(supplier: SupplierId, today: Int): Boolean = state(supplier, today) == Trust.TRUSTED
    fun dropped(supplier: SupplierId, today: Int): Boolean = state(supplier, today) == Trust.DROPPED

    /** Vendors demoted/dropped since the last call — the runner clears them from its preferred set. */
    fun drainFallenVendors(today: Int): Set<SupplierId> {
        settle(today)
        return demotions.toSet().also { demotions.clear() }
    }

    /** In-transit units for [product] (bounds re-procurement while stock is on the way). */
    fun pendingUnits(product: ProductId, today: Int): Int {
        settle(today)
        return active.values.filter { it.product == product && it.delivered < it.units }.sumOf { it.units - it.delivered }
    }
}
