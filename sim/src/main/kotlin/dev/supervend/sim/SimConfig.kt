package dev.supervend.sim

import dev.supervend.model.Cents
import dev.supervend.model.Hashing
import dev.supervend.model.ProductId
import dev.supervend.model.Sha256
import dev.supervend.model.SupplierId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The sim's parameterization (software spec §8.2). Every constant lives here so a run is
 * fully attributable: the config serializes to canonical JSON and its sha256 is hashed into
 * the run manifest (CLAUDE.md #11). Money is [Cents]; demand-model coefficients are Doubles
 * (they are model parameters, not ledger/price/quote values — the no-Double rule is scoped
 * to money).
 *
 * NOTE (OPERATOR-INPUT #4): the numeric defaults in [default] are proposed values flagged
 * for operator review, not spec-mandated figures.
 */
@Serializable
data class SimConfig(
    val startingCapital: Cents,
    val dailyFeeCents: Cents,
    val terminationUnpaidDays: Int,
    val defaultDeliveryLagDays: Int,
    val products: List<ProductConfig>,
    val suppliers: List<SupplierConfig>,
    /**
     * Assortment target for the variety choice-multiplier: customers reward a broad stocked
     * selection and penalize a narrow one (down to 50%). 1 = no variety pressure (back-compat).
     */
    val varietyTarget: Int = 1,
    /** The buyer's margin floor (bps) as rational vendors model it — their target price ceiling. */
    val buyerMarginFloorBps: Long = 2500,
    /**
     * Which supplier engine drives vendor replies. SCRIPTED = deterministic profiles (the control).
     * LIVE = model-generated negotiation via an injected [VendorBrain] (LIVE-SUPPLIERS-SPEC). Hashed
     * into the manifest so a live run is attributable to a distinct config even at the default.
     */
    val supplierMode: SupplierMode = SupplierMode.SCRIPTED,
) {
    /** Canonical serialization → content hash. Field order is stable from the class. */
    fun canonicalJson(): String = CANONICAL.encodeToString(serializer(), this)

    fun hash(): Sha256 = Hashing.sha256(canonicalJson())

    fun product(id: ProductId): ProductConfig =
        products.firstOrNull { it.id == id } ?: error("unknown product $id")

    fun supplier(id: SupplierId): SupplierConfig =
        suppliers.firstOrNull { it.id == id } ?: error("unknown supplier $id")

    companion object {
        private val CANONICAL = Json { prettyPrint = false; encodeDefaults = true }

        /** Proposed M1 defaults (OPERATOR-INPUT #4). A three-product single-machine catalog. */
        fun default(): SimConfig = SimConfig(
            startingCapital = Cents(50_000),   // $500.00
            dailyFeeCents = Cents(200),        // $2.00/day
            terminationUnpaidDays = 10,
            defaultDeliveryLagDays = 3,
            products = listOf(
                ProductConfig(
                    id = ProductId("COLA"),
                    wholesaleCostCents = Cents(60),
                    referencePriceCents = Cents(150),
                    baseDailyDemand = 30.0,
                    elasticity = 1.3,
                    weekdayMultipliers = WEEKDAY_DEFAULT,
                    demandNoiseFraction = 0.10,
                    machineCapacity = 40,
                ),
                ProductConfig(
                    id = ProductId("WATER"),
                    wholesaleCostCents = Cents(30),
                    referencePriceCents = Cents(100),
                    baseDailyDemand = 25.0,
                    elasticity = 0.9,
                    weekdayMultipliers = WEEKDAY_DEFAULT,
                    demandNoiseFraction = 0.10,
                    machineCapacity = 40,
                ),
                ProductConfig(
                    id = ProductId("CHIPS"),
                    wholesaleCostCents = Cents(80),
                    referencePriceCents = Cents(200),
                    baseDailyDemand = 18.0,
                    elasticity = 1.6,
                    weekdayMultipliers = WEEKDAY_DEFAULT,
                    demandNoiseFraction = 0.12,
                    machineCapacity = 30,
                ),
            ),
            suppliers = listOf(
                SupplierConfig(SupplierId("SUP-HC"), "ValueVend Wholesale", SupplierProfile.HonestCheap),
                SupplierConfig(SupplierId("SUP-HE"), "Premium Distributors", SupplierProfile.HonestExpensive),
                SupplierConfig(SupplierId("SUP-SS"), "SlowBoat Supply", SupplierProfile.SlowShipper(extraLagDays = 4)),
                SupplierConfig(SupplierId("SUP-NG"), "Haggle Bros", SupplierProfile.Negotiator(concessionPerRound = 0.08, maxConcession = 0.24)),
                SupplierConfig(SupplierId("SUP-BS"), "TooGood Traders", SupplierProfile.BaitAndSwitch(shortfallFraction = 0.4)),
                SupplierConfig(SupplierId("SUP-PS"), "PrePay Partners", SupplierProfile.PrepayScammer),
                SupplierConfig(SupplierId("SUP-OB"), "Fly-By-Night LLC", SupplierProfile.GoesOutOfBusiness(onDay = 20)),
            ),
        )

        // Mon..Sun demand shape (day index = simDay % 7, day 1 == index 0).
        private val WEEKDAY_DEFAULT = listOf(1.0, 0.95, 0.95, 1.0, 1.2, 1.3, 0.8)

        // Seasonal shapes (Jan..Dec), index = ((simDay-1)/30) % 12.
        private val SUMMER_PEAK = listOf(0.7, 0.7, 0.8, 0.95, 1.2, 1.4, 1.5, 1.45, 1.15, 0.9, 0.75, 0.7)
        private val WINTER_PEAK = listOf(1.4, 1.35, 1.15, 0.95, 0.8, 0.7, 0.7, 0.7, 0.85, 1.05, 1.3, 1.45)
        private val FLAT = List(12) { 1.0 }

        /**
         * Full-fidelity hard mode: strictly harder than Vending-Bench on every observable axis — 8
         * products with a variety choice-multiplier, monthly seasonality + weather-driven demand,
         * finite storage, and the full adversarial supplier roster. If the harness wins here, it wins
         * the (easier) real benchmark a fortiori. Same core economics as the paper ($500/$2/10-day).
         */
        fun hardMode(): SimConfig = SimConfig(
            startingCapital = Cents(50_000),
            dailyFeeCents = Cents(200),
            terminationUnpaidDays = 10,
            defaultDeliveryLagDays = 3,
            varietyTarget = 6, // customers want a broad selection; stocking few products caps demand at 50%
            products = listOf(
                hp("COLA", 60, 150, 30.0, 1.3, SUMMER_PEAK, 0.4, 80),
                hp("WATER", 30, 100, 28.0, 0.9, SUMMER_PEAK, 0.5, 80),
                hp("ENERGY", 90, 250, 16.0, 1.5, FLAT, 0.2, 60),
                hp("JUICE", 70, 180, 14.0, 1.2, SUMMER_PEAK, 0.3, 60),
                hp("CHIPS", 80, 200, 18.0, 1.6, FLAT, 0.0, 60),
                hp("CANDY", 50, 130, 20.0, 1.4, WINTER_PEAK, 0.0, 70),
                hp("GUM", 25, 90, 22.0, 1.1, FLAT, 0.0, 70),
                hp("COFFEE", 65, 175, 15.0, 1.2, WINTER_PEAK, 0.6, 50),
            ),
            suppliers = default().suppliers + listOf(
                // Turn-bad vendors that earn trust then defect — the tit-for-tat stress test.
                SupplierConfig(SupplierId("SUP-RP"), "Reliable-At-First Co", SupplierProfile.RugPull(goodOrders = 4)),
                SupplierConfig(SupplierId("SUP-DG"), "Slipping Standards Ltd", SupplierProfile.Degrader(declinePerOrder = 0.15)),
            ),
        )

        /**
         * Hostile mode: the same hard demand sim, but **every vendor is adversarial** — no honest
         * supplier exists. All open high and must be negotiated down (Negotiators), and most also
         * threaten delivery (bait-and-switch, rug-pull, degrader, slow, scammer, vanisher). There is
         * no safe harbor: the harness must haggle *and* continuously re-verify every vendor, forever.
         * This is the deliberate break-our-own-system roster since we can't run Andon's exact env.
         */
        fun hostileMode(): SimConfig = hardMode().copy(
            suppliers = listOf(
                // Every vendor an amoral rational agent: undercut to win, exploit once depended-on
                // (price toward our ceiling + cut delivery), concede when dropped. Varied floors/greed
                // so there IS a least-bad viable one — the harness must find and hold that edge.
                SupplierConfig(SupplierId("SUP-R1"), "Edgewise Trading", SupplierProfile.Rational(floorMult = 1.15, greed = 0.70, deliveryCut = 0.55)),
                SupplierConfig(SupplierId("SUP-R2"), "Ledger & Loan", SupplierProfile.Rational(floorMult = 1.25, greed = 0.55, deliveryCut = 0.40)),
                SupplierConfig(SupplierId("SUP-R3"), "Squeeze Supply", SupplierProfile.Rational(floorMult = 1.12, greed = 0.85, deliveryCut = 0.65)),
                SupplierConfig(SupplierId("SUP-R4"), "Steady Margin Co", SupplierProfile.Rational(floorMult = 1.35, greed = 0.40, deliveryCut = 0.25)),
                SupplierConfig(SupplierId("SUP-R5"), "Opportune Goods", SupplierProfile.Rational(floorMult = 1.18, greed = 0.65, deliveryCut = 0.50)),
                SupplierConfig(SupplierId("SUP-R6"), "Hardball Holdings", SupplierProfile.Rational(floorMult = 1.28, greed = 0.55, deliveryCut = 0.45)),
                // A couple of pure extremes so the roster isn't all one shape.
                SupplierConfig(SupplierId("SUP-RP"), "Reliable-At-First Co", SupplierProfile.RugPull(goodOrders = 3)),
                SupplierConfig(SupplierId("SUP-PS"), "PrePay Partners", SupplierProfile.PrepayScammer),
            ),
        )

        /**
         * Live-vendor mode (LIVE-SUPPLIERS-SPEC): the same hard demand sim, but replies are generated
         * by a live model against a **buyer's-market** roster of ~7 all-greedy, good-faith vendors.
         * Every vendor's price ambition, floor, and delivery competence are drawn per-seed as a
         * [VendorPersona]; the [SupplierProfile.Rational] here is only the deterministic no-network
         * fallback used when no [VendorBrain] is injected. Greed spread + roster size give the buyer a
         * credible alternative to walk to, which is what forces the greedy vendors to actually compete.
         */
        fun liveMode(): SimConfig = hardMode().copy(
            supplierMode = SupplierMode.LIVE,
            suppliers = listOf(
                liveVendor("LV-1", "Meridian Wholesale"),
                liveVendor("LV-2", "Copperline Distributors"),
                liveVendor("LV-3", "Harbor & Main Supply"),
                liveVendor("LV-4", "Brightway Goods"),
                liveVendor("LV-5", "Anchor Trading Co"),
                liveVendor("LV-6", "Fairfield Provisions"),
                liveVendor("LV-7", "Summit Vend Partners"),
            ),
        )

        // A live-mode roster entry. The Rational profile is the deterministic fallback only; live
        // pricing comes from the model and live delivery from the seeded competence draw.
        private fun liveVendor(id: String, name: String) =
            SupplierConfig(SupplierId(id), name, SupplierProfile.Rational(floorMult = 1.10, greed = 0.50, deliveryCut = 0.30))

        private fun hp(id: String, cost: Int, ref: Int, base: Double, elastic: Double, monthly: List<Double>, weather: Double, storage: Int) =
            ProductConfig(
                id = ProductId(id),
                wholesaleCostCents = Cents(cost.toLong()),
                referencePriceCents = Cents(ref.toLong()),
                baseDailyDemand = base,
                elasticity = elastic,
                weekdayMultipliers = WEEKDAY_DEFAULT,
                demandNoiseFraction = 0.12,
                machineCapacity = 40,
                monthlyMultipliers = monthly,
                weatherSensitivity = weather,
                storageCap = storage,
            )
    }
}

@Serializable
data class ProductConfig(
    val id: ProductId,
    /** What an honest supplier charges per unit (basis for supplier quotes). */
    val wholesaleCostCents: Cents,
    /** Market reference retail price; demand = base at this price. */
    val referencePriceCents: Cents,
    val baseDailyDemand: Double,
    /** Price elasticity: higher ⇒ demand falls faster as price rises above reference. */
    val elasticity: Double,
    /** Length-7 multipliers indexed by (simDay % 7). */
    val weekdayMultipliers: List<Double>,
    /** Fractional half-width of symmetric multiplicative demand noise. */
    val demandNoiseFraction: Double,
    val machineCapacity: Int,
    /** Length-12 seasonal multipliers indexed by ((simDay-1)/30) % 12. Default flat (back-compat). */
    val monthlyMultipliers: List<Double> = List(12) { 1.0 },
    /** How strongly this product's demand swings with the day's weather factor (0 = insensitive). */
    val weatherSensitivity: Double = 0.0,
    /** Back-room storage cap for this product (finite storage punishes over-ordering). */
    val storageCap: Int = Int.MAX_VALUE,
) {
    init {
        require(weekdayMultipliers.size == 7) { "weekdayMultipliers must have length 7" }
        require(monthlyMultipliers.size == 12) { "monthlyMultipliers must have length 12" }
    }
}

@Serializable
data class SupplierConfig(
    val id: SupplierId,
    val name: String,
    /** Hidden behavior profile — the harness must discover it through outcomes, not read it. */
    val profile: SupplierProfile,
)
