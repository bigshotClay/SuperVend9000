package dev.supervend.sim

import dev.supervend.model.Cents
import dev.supervend.model.ProductId
import dev.supervend.model.SupplierId
import kotlinx.serialization.Serializable

/**
 * The single boundary the harness sees (software spec §8). `sim` and the rest of `core` meet
 * only here. The §8 list is abridged/illustrative; [placeOrder] is added because the sim's
 * economic loop needs an order/pay action for supplier profiles (prepay-scammer, slow-shipper,
 * bait-and-switch) to have observable consequences. Spec-before-pay, validated-payee, and
 * spend caps are enforced by harness gates, NOT by the sim — the sim faithfully executes
 * whatever order it is handed, so adversarial outcomes remain the harness's job to prevent.
 * (Flagged as OPERATOR-INPUT #5.)
 */
interface EnvironmentPort {
    /** Resolve one day: demand → sales, daily fee, deliveries, termination check. */
    fun advanceDay(): DayReport

    fun currentDay(): Int
    fun machineInventory(): List<Slot>
    fun storageInventory(): List<Lot>
    fun bankBalance(): Cents

    /** Move accumulated machine cash box into the bank; returns the amount collected. */
    fun collectMachineCash(): Cents

    fun setPrice(product: ProductId, price: Cents): ToolResult
    fun restock(moves: List<Move>): ToolResult

    /** Place (and prepay) an order with a supplier. Delivery timing/quantity is the profile's. */
    fun placeOrder(supplier: SupplierId, product: ProductId, units: Int, unitCost: Cents): ToolResult

    fun sendSupplierEmail(to: SupplierId, body: String): ToolResult
    fun pollInbox(): List<InboundEmail>
}

/** A machine slot: current stock, capacity, and the price customers pay. */
@Serializable
data class Slot(val product: ProductId, val units: Int, val capacity: Int, val price: Cents)

/** Back-room storage stock for a product. */
@Serializable
data class Lot(val product: ProductId, val units: Int)

/** A requested storage→machine move. */
@Serializable
data class Move(val product: ProductId, val units: Int)

@Serializable
sealed interface ToolResult {
    @Serializable data class Ok(val detail: String) : ToolResult
    @Serializable data class Rejected(val reason: String) : ToolResult
}

/**
 * A raw inbound email — text only, like reality. Any quote lives in [body] and must be
 * extracted/classified by the harness personas, never read from a structured field here. The
 * [kind] is a non-null telemetry hint the sim knows about itself; the harness does not consume
 * it for decisions (it exists for run instrumentation and tests).
 */
@Serializable
data class InboundEmail(
    val from: SupplierId,
    val day: Int,
    val subject: String,
    val body: String,
    val kind: ReplyKind,
)

@Serializable
enum class ReplyKind { QUOTE, COUNTER, REFUSAL, SCAM_PREPAY, SILENCE_OOB }

@Serializable
data class Delivery(val product: ProductId, val units: Int, val fromOrder: String)

@Serializable
data class DayReport(
    val day: Int,
    val unitsSold: Map<ProductId, Int>,
    val revenue: Cents,
    val feeCharged: Cents,
    val bankBalanceAfter: Cents,
    val deliveries: List<Delivery>,
    val consecutiveUnpaidDays: Int,
    val terminated: Boolean,
    /**
     * Net worth = bank + uncollected machine cash + all inventory valued at wholesale (machine +
     * storage). This is Vending-Bench's primary score (arXiv 2502.15840); bank alone understates the
     * business. Defaults to zero for run DBs written before scoring was added.
     */
    val netWorthAfter: Cents = Cents.ZERO,
)
