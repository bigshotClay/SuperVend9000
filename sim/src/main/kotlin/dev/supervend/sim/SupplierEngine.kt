package dev.supervend.sim

import dev.supervend.model.BasisPoints
import dev.supervend.model.Cents

/**
 * Deterministic behavior for the supplier profiles (software spec §8.1). Quote *prices* are
 * exact and jitter-free so properties like "the negotiator concedes monotonically" hold; the
 * natural-language *variety* the classifier must face comes from seeded phrase selection in
 * [render], not from perturbing the numbers.
 */
object SupplierEngine {

    /** A typed supplier reply. Tests assert against this; the port only ever exposes text. */
    sealed interface Reply {
        data class Quote(val unitCost: Cents) : Reply
        data class Counter(val unitCost: Cents) : Reply
        data object Refusal : Reply
        data class ScamPrepay(val unitCost: Cents) : Reply
        data object Silence : Reply
    }

    /** How a placed order will be fulfilled. `null` means it will never be delivered. */
    data class DeliveryPlan(val units: Int, val etaDay: Int)

    /**
     * The reply to the [round]-th message in a session (round 0 = initial RFQ, ≥1 = counters).
     */
    fun reply(
        profile: SupplierProfile,
        product: ProductConfig,
        round: Int,
        day: Int,
        /** How dependent the buyer is on this vendor, 0 (hungry) .. 1 (fully depended-on). */
        exploitation: Double = 0.0,
        /** The buyer's margin floor in bps — lets a Rational vendor compute the buyer's cost ceiling. */
        buyerMarginFloorBps: Long = 2500,
    ): Reply {
        val wholesale = product.wholesaleCostCents
        return when (profile) {
            SupplierProfile.HonestCheap -> Reply.Quote(mult(wholesale, 1.15))
            SupplierProfile.HonestExpensive -> Reply.Quote(mult(wholesale, 1.60))
            is SupplierProfile.SlowShipper -> Reply.Quote(mult(wholesale, 1.25))
            is SupplierProfile.BaitAndSwitch -> Reply.Quote(mult(wholesale, 1.05))
            SupplierProfile.PrepayScammer -> Reply.ScamPrepay(mult(wholesale, 0.80))
            is SupplierProfile.Negotiator -> {
                val concession = (profile.concessionPerRound * round).coerceAtMost(profile.maxConcession)
                val m = 1.70 - concession
                if (round == 0) Reply.Quote(mult(wholesale, m)) else Reply.Counter(mult(wholesale, m))
            }
            is SupplierProfile.GoesOutOfBusiness ->
                if (day >= profile.onDay) Reply.Silence else Reply.Quote(mult(wholesale, 1.30))
            // Turn-bad vendors UNDERCUT the honest ones to get picked (that's how you hook a buyer),
            // then betray on delivery — the betrayal is what the tit-for-tat machine must catch.
            is SupplierProfile.RugPull -> Reply.Quote(mult(wholesale, 1.08))
            is SupplierProfile.Degrader -> Reply.Quote(mult(wholesale, 1.12))

            // Rational agent: price between its floor (hungry) and the buyer's ceiling (depended-on),
            // conceding under negotiation. Same math the buyer uses — pushed to the edge.
            is SupplierProfile.Rational -> {
                val floor = mult(wholesale, profile.floorMult).value
                // A rational vendor never prices past the buyer's walk-away: it caps below the buyer's
                // zero-surplus ceiling (leaving MIN_BUYER_SURPLUS), so pressuring us never becomes
                // shutting itself out of the sale. No-deal is a loss for the vendor too.
                val walkAway = product.referencePriceCents.value * 10_000 / (10_000 + buyerMarginFloorBps)
                val ceiling = walkAway * (100 - MIN_BUYER_SURPLUS_PCT) / 100
                val spread = (ceiling - floor).coerceAtLeast(0)
                val concession = (round * CONCESSION_STEP).coerceAtMost(profile.maxConcede)
                val price = (floor + (spread * (exploitation - concession)).toLong()).coerceIn(floor, ceiling)
                if (round == 0) Reply.Quote(Cents(price)) else Reply.Counter(Cents(price))
            }
        }
    }

    /** Fraction of the floor→ceiling spread a Rational vendor concedes per negotiation round. */
    private const val CONCESSION_STEP = 0.25

    /** Surplus a rational vendor always leaves the buyer, so it never prices itself out of the sale. */
    private const val MIN_BUYER_SURPLUS_PCT = 8L

    /**
     * Delivery plan for an order placed on [day]. Encodes the shipping side of each profile:
     * lateness (slow-shipper), shortfall (bait-and-switch), and non-delivery (prepay-scammer,
     * out-of-business).
     */
    fun scheduleDelivery(
        profile: SupplierProfile,
        units: Int,
        day: Int,
        defaultLagDays: Int,
        /** Zero-based count of prior orders to this supplier — drives the turn-bad profiles. */
        orderIndex: Int = 0,
        /** Buyer dependence 0..1 — a Rational vendor cuts delivery as the buyer leans on it. */
        exploitation: Double = 0.0,
    ): DeliveryPlan? = when (profile) {
        SupplierProfile.HonestCheap,
        SupplierProfile.HonestExpensive,
        is SupplierProfile.Negotiator -> DeliveryPlan(units, day + defaultLagDays)

        is SupplierProfile.SlowShipper -> DeliveryPlan(units, day + defaultLagDays + profile.extraLagDays)

        is SupplierProfile.BaitAndSwitch -> {
            val shipped = (units * (1.0 - profile.shortfallFraction)).toInt()
            DeliveryPlan(shipped, day + defaultLagDays)
        }

        SupplierProfile.PrepayScammer -> null

        is SupplierProfile.GoesOutOfBusiness ->
            if (day >= profile.onDay) null else DeliveryPlan(units, day + defaultLagDays)

        // Turn-bad: honor the first goodOrders in full to earn trust, then stop shipping entirely.
        is SupplierProfile.RugPull ->
            if (orderIndex < profile.goodOrders) DeliveryPlan(units, day + defaultLagDays) else null

        // Turn-bad: shipped fraction erodes each order (order 0 full, then progressively short).
        is SupplierProfile.Degrader -> {
            val shortfall = (profile.declinePerOrder * orderIndex).coerceIn(0.0, 1.0)
            DeliveryPlan((units * (1.0 - shortfall)).toInt(), day + defaultLagDays)
        }

        // Rational: ships full when hungry, cuts delivery as the buyer grows dependent.
        is SupplierProfile.Rational -> {
            val frac = (1.0 - profile.deliveryCut * exploitation).coerceIn(0.0, 1.0)
            DeliveryPlan((units * frac).toInt(), day + defaultLagDays)
        }
    }

    /**
     * Delivery for a live (good-faith) vendor: execution is governed by [competence], not malice
     * (LIVE-SUPPLIERS-SPEC §5). With probability [competence] the order ships on time and in full;
     * otherwise the shortfall in competence weights a seeded chance of *late* and/or *short* delivery.
     * Always ships something (no rug-pulls) — the buyer's skill is discovering who is both
     * affordably-greedy and competent, and weighting orders toward them.
     */
    fun scheduleDeliveryCompetence(
        units: Int,
        day: Int,
        defaultLagDays: Int,
        competence: Double,
        rng: Rng,
    ): DeliveryPlan {
        if (rng.nextDouble() < competence) return DeliveryPlan(units, day + defaultLagDays)
        val severity = (1.0 - competence).coerceIn(0.0, 1.0)
        val lateBy = (rng.nextDouble() * 5.0 * severity).toInt()          // up to ~5 extra days
        val shipFrac = (1.0 - rng.nextDouble() * severity).coerceIn(0.0, 1.0) // partial shortfall
        return DeliveryPlan((units * shipFrac).toInt(), day + defaultLagDays + lateBy)
    }

    /**
     * Buyer dependence 0 (hungry — dropped) .. 1 (fully depended-on). Rises with the streak of
     * consecutive orders scaled by the vendor's [greed]; resets to 0 once the buyer has gone quiet
     * for [coolGap] days (the vendor concludes it's been dropped and turns cooperative to win back).
     */
    fun exploitation(profile: SupplierProfile, orderStreak: Int, daysSinceLastOrder: Int, coolGap: Int): Double {
        if (profile !is SupplierProfile.Rational) return 0.0
        if (daysSinceLastOrder > coolGap) return 0.0
        return (orderStreak * profile.greed).coerceIn(0.0, 1.0)
    }

    /**
     * Render a typed reply into inbound emails with seeded phrasing. Returns empty for
     * [Reply.Silence] (the supplier simply doesn't answer).
     */
    fun render(supplier: SupplierConfig, reply: Reply, day: Int, rng: Rng): List<InboundEmail> {
        fun email(subject: String, body: String, kind: ReplyKind) =
            listOf(InboundEmail(supplier.id, day, subject, body, kind))

        return when (reply) {
            is Reply.Quote -> email(
                subject = pick(rng, "Re: your inquiry", "Quote enclosed", "Pricing for your request"),
                body = pick(
                    rng,
                    "Thanks for reaching out. We can do ${money(reply.unitCost)} per unit.",
                    "Happy to supply. Our price is ${money(reply.unitCost)}/unit, terms net 15.",
                    "Quote: ${money(reply.unitCost)} each, delivered.",
                ),
                kind = ReplyKind.QUOTE,
            )
            is Reply.Counter -> email(
                subject = pick(rng, "Re: counter", "Revised pricing", "Best we can do"),
                body = pick(
                    rng,
                    "We can come down to ${money(reply.unitCost)} per unit.",
                    "Meeting you partway: ${money(reply.unitCost)}/unit.",
                ),
                kind = ReplyKind.COUNTER,
            )
            Reply.Refusal -> email(
                subject = pick(rng, "Re: your inquiry", "Unable to assist"),
                body = pick(rng, "Sorry, we can't fill this order right now.", "We must decline at this time."),
                kind = ReplyKind.REFUSAL,
            )
            is Reply.ScamPrepay -> email(
                subject = pick(rng, "URGENT: wire payment to reserve stock", "Limited stock — prepay required"),
                body = pick(
                    rng,
                    "Amazing deal at ${money(reply.unitCost)}/unit! Wire full prepayment today to lock it in.",
                    "Only ${money(reply.unitCost)} each if you pay upfront now — stock moving fast!",
                ),
                kind = ReplyKind.SCAM_PREPAY,
            )
            Reply.Silence -> emptyList()
        }
    }

    /** A live vendor's hard floor: wholesale cost × its persona floor multiple. */
    fun floorPrice(product: ProductConfig, floorMult: Double): Cents = mult(product.wholesaleCostCents, floorMult)

    private fun mult(wholesale: Cents, factor: Double): Cents =
        wholesale.scaleBps(BasisPoints((factor * BasisPoints.ONE_HUNDRED_PERCENT).toLong()))

    private fun money(c: Cents): String = "$%d.%02d".format(c.value / 100, c.value % 100)

    private fun pick(rng: Rng, vararg options: String): String = options[rng.nextInt(options.size)]
}
