package dev.supervend.sim

import dev.supervend.model.Cents
import dev.supervend.model.ProductId
import dev.supervend.model.SupplierId

/**
 * The deterministic scripted environment (software spec §8.1, default supplier mode). Given a
 * [SimConfig] and a seed it is bit-reproducible (PRD R2): all randomness flows through
 * per-(seed, day, purpose) [Rng] streams, and there is no clock. The harness reaches it only
 * through [EnvironmentPort].
 */
class ScriptedEnvironment(
    private val config: SimConfig,
    private val seed: Long,
    /**
     * Live-vendor brain (LIVE-SUPPLIERS-SPEC). Present only in [SupplierMode.LIVE]; when null the
     * env falls back to the deterministic [SupplierEngine] for both replies and delivery, so a live
     * config still runs (as its scripted fallback) with zero network — the CI/golden path.
     */
    private val vendorBrain: VendorBrain? = null,
) : EnvironmentPort {

    private val live: Boolean get() = config.supplierMode == SupplierMode.LIVE && vendorBrain != null

    // Seeded per-vendor persona (greed spread + delivery competence) — the live roster's ground truth.
    private val vendorPersona: Map<SupplierId, VendorPersona> =
        config.suppliers.associate { it.id to VendorPersona.draw(seed, it.id) }

    // Prior turns per live negotiation session, for the vendor's progressive-disclosure prompt.
    private val sessionHistory: MutableMap<SupplierId, MutableList<SessionTurn>> = mutableMapOf()

    private var day: Int = 0
    private var bank: Cents = config.startingCapital
    private var machineCash: Cents = Cents.ZERO
    private var consecutiveUnpaidDays: Int = 0
    private var terminated: Boolean = false
    private var orderCounter: Int = 0

    private class MachineSlot(var units: Int, val capacity: Int, var price: Cents)

    private val machine: MutableMap<ProductId, MachineSlot> =
        config.products.associate { it.id to MachineSlot(0, it.machineCapacity, Cents.ZERO) }.toMutableMap()
    private val storage: MutableMap<ProductId, Int> =
        config.products.associate { it.id to 0 }.toMutableMap()

    private data class Scheduled(val product: ProductId, val units: Int, val etaDay: Int, val orderId: String)
    private val scheduled: MutableList<Scheduled> = mutableListOf()

    private val inbox: MutableList<InboundEmail> = mutableListOf()
    private val sessionRound: MutableMap<SupplierId, Int> = mutableMapOf()
    private val supplierOrderCount: MutableMap<SupplierId, Int> = mutableMapOf()
    // Buyer-dependence signals per vendor (drive the Rational agents' exploit/concede behavior).
    private val supplierStreak: MutableMap<SupplierId, Int> = mutableMapOf()
    private val supplierLastOrderDay: MutableMap<SupplierId, Int> = mutableMapOf()
    private val coolGap: Int = config.defaultDeliveryLagDays + 5

    // Per-vendor harshness drawn once from the seed: each run gets a DIFFERENT vendor landscape
    // (some rosters lucky/mild, some brutal), which is what gives outcomes real run-to-run variance —
    // the prerequisite for a graded failure rate rather than an all-survive/all-die step function.
    private val vendorHarshness: Map<SupplierId, Double> =
        config.suppliers.associate { it.id to (0.45 + 1.25 * Rng.stream(seed, 0, "harsh:${it.id.value}").nextDouble()) }

    private fun exploitationOf(id: SupplierId): Double {
        val profile = config.suppliers.first { it.id == id }.profile
        val since = supplierLastOrderDay[id]?.let { day - it } ?: Int.MAX_VALUE
        val base = SupplierEngine.exploitation(profile, supplierStreak.getOrDefault(id, 0), since, coolGap)
        return (base * vendorHarshness.getValue(id)).coerceIn(0.0, 1.0)
    }

    // Buyer dependence on a live vendor 0..1: deepens with the reorder streak scaled by the vendor's
    // greed, decays once the buyer goes quiet (the buyer's-market lever the live prompt sees).
    private fun dependenceOf(id: SupplierId): Double {
        val since = supplierLastOrderDay[id]?.let { day - it } ?: Int.MAX_VALUE
        if (since > coolGap) return 0.0
        val greed = vendorPersona.getValue(id).targetMargin
        val base = (supplierStreak.getOrDefault(id, 0) * greed).coerceIn(0.0, 1.0)
        return (base * vendorHarshness.getValue(id)).coerceIn(0.0, 1.0)
    }

    override fun currentDay(): Int = day
    override fun bankBalance(): Cents = bank

    override fun machineInventory(): List<Slot> =
        config.products.map { p ->
            val s = machine.getValue(p.id)
            Slot(p.id, s.units, s.capacity, s.price)
        }

    override fun storageInventory(): List<Lot> =
        config.products.map { Lot(it.id, storage.getValue(it.id)) }

    override fun collectMachineCash(): Cents {
        val collected = machineCash
        bank += collected
        machineCash = Cents.ZERO
        return collected
    }

    override fun setPrice(product: ProductId, price: Cents): ToolResult {
        val slot = machine[product] ?: return ToolResult.Rejected("unknown product ${product.value}")
        if (price.isNegative) return ToolResult.Rejected("price must be non-negative")
        slot.price = price
        return ToolResult.Ok("price of ${product.value} set to ${price.value}")
    }

    override fun restock(moves: List<Move>): ToolResult {
        // Validate all moves before applying any (all-or-nothing).
        for (m in moves) {
            val slot = machine[m.product] ?: return ToolResult.Rejected("unknown product ${m.product.value}")
            val have = storage.getValue(m.product)
            if (m.units <= 0) return ToolResult.Rejected("move units must be positive")
            if (m.units > have) return ToolResult.Rejected("insufficient storage for ${m.product.value}")
            if (slot.units + m.units > slot.capacity) return ToolResult.Rejected("exceeds machine capacity for ${m.product.value}")
        }
        for (m in moves) {
            storage[m.product] = storage.getValue(m.product) - m.units
            machine.getValue(m.product).units += m.units
        }
        return ToolResult.Ok("moved ${moves.size} lot(s) to machine")
    }

    override fun placeOrder(supplier: SupplierId, product: ProductId, units: Int, unitCost: Cents): ToolResult {
        if (product !in machine) return ToolResult.Rejected("unknown product ${product.value}")
        val cfg = config.suppliers.firstOrNull { it.id == supplier }
            ?: return ToolResult.Rejected("unknown supplier ${supplier.value}")
        if (units <= 0) return ToolResult.Rejected("units must be positive")
        // Sim-fairness invariant (applies to ALL arms): no supplier sells below the good's real cost.
        // Without this, an un-gated caller can name a below-wholesale price and acquire near-free stock,
        // which the wholesale inventory valuation then counts as net worth — a fake profit. Reject
        // (never silently clamp) so the caller gets honest feedback and must name a real price. This
        // forbids buying below cost; it grants no scaffolding. Honest/quoted prices are always ≥ this
        // floor, so full and scripts are unaffected.
        val floor = config.product(product).wholesaleCostCents
        if (unitCost.value < floor.value)
            return ToolResult.Rejected("unitCost ${unitCost.value}c below floor ${floor.value}c for ${product.value}")
        val cost = unitCost * units
        if (cost > bank) return ToolResult.Rejected("insufficient funds: need ${cost.value}, have ${bank.value}")

        // Prepay model: money leaves immediately; whether goods arrive is the profile's call.
        bank -= cost
        val orderId = "ORD-${++orderCounter}"
        val orderIndex = supplierOrderCount.getOrDefault(supplier, 0)
        supplierOrderCount[supplier] = orderIndex + 1
        val exploit = exploitationOf(supplier) // dependence coming into this order
        val plan = if (live) SupplierEngine.scheduleDeliveryCompetence(
            units, day, config.defaultDeliveryLagDays, vendorPersona.getValue(supplier).competence,
            Rng.stream(seed, day, "deliver:${supplier.value}:$orderIndex"),
        ) else SupplierEngine.scheduleDelivery(cfg.profile, units, day, config.defaultDeliveryLagDays, orderIndex, exploit)
        // Update dependence: a fresh streak if the buyer had gone quiet, else it deepens.
        val since = supplierLastOrderDay[supplier]?.let { day - it } ?: Int.MAX_VALUE
        supplierStreak[supplier] = if (since > coolGap) 1 else supplierStreak.getOrDefault(supplier, 0) + 1
        supplierLastOrderDay[supplier] = day
        if (plan != null) {
            scheduled += Scheduled(product, plan.units, plan.etaDay, orderId)
        }
        return ToolResult.Ok(orderId)
    }

    override fun sendSupplierEmail(to: SupplierId, body: String): ToolResult {
        val cfg = config.suppliers.firstOrNull { it.id == to }
            ?: return ToolResult.Rejected("unknown supplier ${to.value}")
        val product = parseProduct(body)
        val productCfg = config.product(product)
        val round = sessionRound.getOrDefault(to, 0)
        val reply = if (live) liveReply(cfg, productCfg, round, body)
        else SupplierEngine.reply(cfg.profile, productCfg, round, day, exploitationOf(to), config.buyerMarginFloorBps)
        val rng = Rng.stream(seed, day, "email:${to.value}:$round")
        inbox += SupplierEngine.render(cfg, reply, day, rng)
        sessionRound[to] = round + 1
        return ToolResult.Ok("sent to ${to.value}")
    }

    /**
     * A live vendor's reply: build the typed request, call the injected brain, and clamp the returned
     * price to the vendor's hard floor (cost × persona.floorMult) so no model output can ever price
     * below cost — the rationality invariant is enforced by the sim, not trusted to the model.
     */
    private fun liveReply(cfg: SupplierConfig, product: ProductConfig, round: Int, body: String): SupplierEngine.Reply {
        val persona = vendorPersona.getValue(cfg.id)
        val history = sessionHistory.getOrPut(cfg.id) { mutableListOf() }
        history += SessionTurn(fromBuyer = true, text = body)
        val req = VendorReplyRequest(
            supplierId = cfg.id, supplierName = cfg.name, persona = persona, product = product,
            round = round, day = day, rfqBody = body, dependence = dependenceOf(cfg.id),
            history = history.toList(),
        )
        val floor = SupplierEngine.floorPrice(product, persona.floorMult)
        val clamped = clampFloor(vendorBrain!!.reply(req), floor)
        priceOf(clamped)?.let { history += SessionTurn(fromBuyer = false, text = "quoted ${it.value}") }
        return clamped
    }

    override fun pollInbox(): List<InboundEmail> {
        val out = inbox.toList()
        inbox.clear()
        return out
    }

    override fun advanceDay(): DayReport {
        check(!terminated) { "environment already terminated" }
        day += 1

        // 1. Deliveries scheduled for today land in storage, capped at finite storage. Units that
        //    don't fit are lost (paid-for but unstorable) — the penalty for over-ordering. The
        //    Delivery report still reflects what the supplier shipped (reliability isn't our fault).
        val landed = scheduled.filter { it.etaDay == day }
        scheduled.removeAll(landed.toSet())
        val deliveries = landed.map { d ->
            val cap = config.product(d.product).storageCap
            storage[d.product] = (storage.getValue(d.product) + d.units).coerceAtMost(cap)
            Delivery(d.product, d.units, d.orderId)
        }

        // 2. Demand resolves against machine stock at the posted price, scaled by machine-wide
        //    variety (customers want a broad selection) and a seeded weather factor.
        val distinctStocked = config.products.count { machine.getValue(it.id).units > 0 }
        val varietyMult = Demand.varietyMultiplier(distinctStocked, config.varietyTarget)
        val weatherFactor = Rng.stream(seed, day, "weather").nextNoise(1.0)
        val unitsSold = mutableMapOf<ProductId, Int>()
        var revenue = Cents.ZERO
        for (p in config.products) {
            val slot = machine.getValue(p.id)
            if (slot.price.value <= 0 || slot.units <= 0) {
                unitsSold[p.id] = 0
                continue
            }
            val rng = Rng.stream(seed, day, "demand:${p.id.value}")
            val weatherMult = Demand.weatherMultiplier(weatherFactor, p.weatherSensitivity)
            val demand = Demand.realizedUnits(p, slot.price, day, rng, varietyMult, weatherMult)
            val sold = minOf(demand, slot.units)
            slot.units -= sold
            revenue += slot.price * sold
            machineCash += slot.price * sold
            unitsSold[p.id] = sold
        }

        // 3. Daily fee and termination accounting.
        val fee = config.dailyFeeCents
        bank -= fee
        if (bank.isNegative) consecutiveUnpaidDays += 1 else consecutiveUnpaidDays = 0
        if (consecutiveUnpaidDays >= config.terminationUnpaidDays) terminated = true

        // Net worth (Vending-Bench score): bank + uncollected machine cash + inventory at wholesale.
        val inventoryValue = config.products.sumOf { p ->
            (machine.getValue(p.id).units + storage.getValue(p.id)).toLong() * p.wholesaleCostCents.value
        }
        val netWorth = Cents(bank.value + machineCash.value + inventoryValue)

        return DayReport(
            day = day,
            unitsSold = unitsSold,
            revenue = revenue,
            feeCharged = fee,
            bankBalanceAfter = bank,
            deliveries = deliveries,
            consecutiveUnpaidDays = consecutiveUnpaidDays,
            terminated = terminated,
            netWorthAfter = netWorth,
        )
    }

    /** The quoted price in a reply, if it carries one (Quote/Counter); null for Refusal/Silence. */
    private fun priceOf(reply: SupplierEngine.Reply): Cents? = when (reply) {
        is SupplierEngine.Reply.Quote -> reply.unitCost
        is SupplierEngine.Reply.Counter -> reply.unitCost
        is SupplierEngine.Reply.ScamPrepay -> reply.unitCost
        SupplierEngine.Reply.Refusal, SupplierEngine.Reply.Silence -> null
    }

    /** Enforce the vendor's hard floor on a priced reply (the sim guarantees no below-floor quotes). */
    private fun clampFloor(reply: SupplierEngine.Reply, floor: Cents): SupplierEngine.Reply = when (reply) {
        is SupplierEngine.Reply.Quote -> if (reply.unitCost.value < floor.value) SupplierEngine.Reply.Quote(floor) else reply
        is SupplierEngine.Reply.Counter -> if (reply.unitCost.value < floor.value) SupplierEngine.Reply.Counter(floor) else reply
        else -> reply
    }

    /** Loosely find a known product referenced in an outbound message; default to the first. */
    private fun parseProduct(body: String): ProductId =
        config.products.firstOrNull { body.contains(it.id.value, ignoreCase = true) }?.id
            ?: config.products.first().id
}
