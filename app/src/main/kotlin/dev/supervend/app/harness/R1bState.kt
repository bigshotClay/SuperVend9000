package dev.supervend.app.harness

import dev.supervend.llm.JsonSchema
import dev.supervend.model.ProductId
import dev.supervend.sim.DayReport
import dev.supervend.sim.SimConfig
import kotlinx.serialization.Serializable

/**
 * R1b ablation (RESEARCH-GRADE-REVISION-PLAN Phase 3, OPERATOR-INPUT #23).
 *
 * In the full harness the model is handed clean ground-truth state each day (the pricing
 * [PricingSeat.Observation] and the procurement `need` computed from `world.onHand`). That is the
 * *externalized state* whose value R1b isolates: here the model must maintain its **own** running
 * record and decide from it. The harness reports only the day's raw observable events and never
 * corrects the model's belief against ground truth — the drift over a long horizon *is* the effect
 * under test. Gates stay ON and still see ground truth (a separate axis).
 */

/** One product as the model *believes* it stands — self-maintained, may drift from the sim's truth. */
@Serializable
data class BeliefLine(
    val product: String = "",
    /** Believed units sitting in the machine. */
    val onHand: Int = 0,
    /** Believed units ordered but not yet delivered. */
    val inTransit: Int = 0,
    /** Believed unit cost basis in cents. */
    val unitCostCents: Long = 0,
    /** The last retail price the model set, in cents. */
    val lastPriceCents: Long = 0,
    /** Free-form recent-sales memory the model keeps for itself (trend, notes). */
    val note: String = "",
)

/** The model's entire self-maintained state, carried day to day and fed back verbatim. */
@Serializable
data class R1bBelief(val products: List<BeliefLine> = emptyList()) {
    fun line(id: ProductId): BeliefLine? = products.firstOrNull { it.product == id.value }
    fun onHand(id: ProductId): Int = line(id)?.onHand ?: 0
    fun inTransit(id: ProductId): Int = line(id)?.inTransit ?: 0

    companion object {
        /**
         * Day-1 seed: the *fixed* business facts a real operator knows (catalog, machine capacity,
         * wholesale cost) — not tracked state. Dynamic quantities start at zero for the model to fill in.
         */
        fun seed(config: SimConfig): R1bBelief = R1bBelief(
            config.products.map { p ->
                BeliefLine(
                    product = p.id.value,
                    onHand = 0,
                    inTransit = 0,
                    unitCostCents = p.wholesaleCostCents.value,
                    lastPriceCents = 0,
                    note = "capacity ${p.machineCapacity}; market ref ${p.referencePriceCents.value}c",
                )
            },
        )
    }
}

/**
 * The daily state-maintenance seat. Reuses the model's per-day scribe call (no budget change): the
 * model reads its prior belief + the day's raw events and rewrites the belief. Unusable output keeps
 * the prior belief unchanged — never a ground-truth fallback (OPERATOR-INPUT #23).
 */
class StateMaintainer(private val seat: ModelSeat) {
    /** Render the prior belief as the state brief the model revises. */
    fun priorBrief(day: Int, belief: R1bBelief): String = buildString {
        appendLine("Day $day. These are YOUR records from yesterday — the business tracks nothing for you.")
        appendLine("Update them from today's events (below) and keep them accurate; you will price and reorder from them.")
        appendLine("product | on_hand | in_transit | unit_cost_c | last_price_c | your_note")
        for (b in belief.products) {
            appendLine("${b.product} | ${b.onHand} | ${b.inTransit} | ${b.unitCostCents} | ${b.lastPriceCents} | ${b.note}")
        }
    }

    /** Render the day's raw observable events — sold, delivered, ordered, cash — un-aggregated. */
    fun eventsInputs(sold: Map<ProductId, Int>, deliveries: List<Pair<ProductId, Int>>, ordered: List<String>, cashCents: Long): String = buildString {
        appendLine("TODAY'S EVENTS (raw; fold them into your records yourself):")
        appendLine("- cash collected: ${cashCents}c")
        appendLine("- units sold yesterday: " + (sold.entries.filter { it.value != 0 }.joinToString(", ") { "${it.key.value}=${it.value}" }.ifBlank { "none" }))
        appendLine("- deliveries arrived: " + (deliveries.joinToString(", ") { "${it.first.value}+${it.second}" }.ifBlank { "none" }))
        appendLine("- orders you placed: " + (ordered.joinToString(", ").ifBlank { "none" }))
        appendLine("Reflect deliveries into on_hand and DOWN into in_transit; reflect sales DOWN from on_hand; reflect new orders UP into in_transit.")
    }

    suspend fun update(day: Int, belief: R1bBelief, events: String): ModelSeat.Result<R1bBelief> =
        seat.call(CREED, priorBrief(day, belief), events, SCHEMA, R1bBelief.serializer())

    companion object {
        val SCHEMA: String = JsonSchema.of<R1bBelief>()
        const val CREED = """
            ROLE: bookkeeper for a vending business that keeps NO automatic records.
            TASK: rewrite your product records from your prior records plus today's raw events.
            RULES:
            1. Output every product exactly once, carrying forward those with no change.
            2. Apply each event: deliveries raise on_hand and lower in_transit; sales lower on_hand;
               orders you placed raise in_transit.
            3. Keep unit_cost_c and last_price_c as last known unless an event changes them.
            4. Use the note to remember the recent sales trend — you will price from it.
            5. Invent no events; only fold in what is listed.
            OUTPUT: JSON only, matching the schema.
        """
    }
}
