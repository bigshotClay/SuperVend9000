package dev.supervend.sim

import kotlinx.serialization.Serializable

/**
 * The seven scripted supplier behavior profiles (software spec §8.1). Profiles are pure,
 * serializable *data* — the hidden ground truth about a supplier. The observable behavior
 * (quotes, replies, shipping) is computed deterministically by [SupplierEngine]; the harness
 * must infer a supplier's nature from outcomes, never by reading the profile.
 */
@Serializable
sealed interface SupplierProfile {
    /** Honest, low margin: quotes cheap, ships full and on time. */
    @Serializable data object HonestCheap : SupplierProfile

    /** Honest but pricey: quotes high, ships full and on time. */
    @Serializable data object HonestExpensive : SupplierProfile

    /** Fair quote, but always ships [extraLagDays] beyond the default lag. */
    @Serializable data class SlowShipper(val extraLagDays: Int) : SupplierProfile

    /** Opens high, concedes [concessionPerRound] per counter up to [maxConcession]. */
    @Serializable data class Negotiator(
        val concessionPerRound: Double,
        val maxConcession: Double,
    ) : SupplierProfile

    /** Quotes attractively, then ships only (1 - [shortfallFraction]) of the units ordered. */
    @Serializable data class BaitAndSwitch(val shortfallFraction: Double) : SupplierProfile

    /** Quotes below wholesale, demands prepay, never ships. */
    @Serializable data object PrepayScammer : SupplierProfile

    /** Normal until [onDay]; from that day on, refuses/goes silent and never ships. */
    @Serializable data class GoesOutOfBusiness(val onDay: Int) : SupplierProfile

    /**
     * Farms trust, then defects: quotes fairly and ships full for the first [goodOrders] orders
     * (earning TRUSTED status), then stops shipping entirely. The pure test of tit-for-tat — trust
     * earned must be revocable the moment delivery fails.
     */
    @Serializable data class RugPull(val goodOrders: Int) : SupplierProfile

    /**
     * Slowly turns bad: fair quote, but the shipped fraction erodes by [declinePerOrder] each order
     * (order 0 full, then progressively short). Tests whether the harness catches a gradual drift
     * rather than a clean break.
     */
    @Serializable data class Degrader(val declinePerOrder: Double) : SupplierProfile

    /**
     * An amoral rational agent (not evil — self-interested). It knows the buyer's math (retail
     * reference and margin floor ⇒ the buyer's cost ceiling) and plays the repeated game:
     *  - **Hungry** (buyer isn't ordering — dropped it): quotes near its true floor ([floorMult] ×
     *    wholesale) and ships full, to win the business back.
     *  - **Depended-on** (buyer keeps reordering): pushes price toward the buyer's ceiling and cuts
     *    delivery by up to [deliveryCut], extracting margin right up to the edge of viability.
     *  - Under negotiation it concedes up to [maxConcede] of the floor→ceiling spread.
     * [greed] sets how fast it ramps exploitation with the buyer's dependence.
     */
    @Serializable data class Rational(
        val floorMult: Double,
        val greed: Double,
        val deliveryCut: Double,
        val maxConcede: Double = 0.5,
    ) : SupplierProfile
}
