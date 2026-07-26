package dev.supervend.app.harness

import dev.supervend.llm.CompletionRequest
import dev.supervend.llm.CompletionResult
import dev.supervend.llm.ContextPackage
import dev.supervend.llm.JsonSchema
import dev.supervend.llm.ModelPort
import dev.supervend.llm.PromptBuilder
import dev.supervend.llm.qualify.JsonExtract
import dev.supervend.model.Cents
import dev.supervend.model.ProductId
import dev.supervend.sim.ProductConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The model's decision seat: propose today's retail price per product. This is where the model earns
 * its keep — the sim's demand is price-elastic, so a smarter price than the script's reference-price
 * default is real profit (the "alpha on top of the fundamentals"). The seat is pure upside by
 * construction: the harness gates every proposal (MarginFloor etc.) and the caller falls back to the
 * script price on any rejection or unusable output, so a weak model can only match the floor, never
 * sink below it.
 *
 * Context package order and schema-from-type both follow the hard constraints (CLAUDE.md #7/#8):
 * the output schema is generated from [PricingDecision], and instructions sit at the bottom.
 */
class PricingSeat(private val model: ModelPort, private val modelId: String) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val schema = JsonSchema.of<PricingDecision>()

    /** One product's state as the model sees it (no hidden fields — it prices from observable state). */
    data class Observation(
        val product: ProductConfig,
        val currentPrice: Cents,
        val soldYesterday: Int,
        val soldPrior: Int,
        val machineUnits: Int,
        /** Actual weighted-average cost we paid — rises when vendors squeeze us. */
        val unitCost: Cents,
    )

    data class Result(val prices: Map<ProductId, Cents>, val usage: CompletionResult)

    /** Ask the model for prices. Returns whatever it proposed (parsed); the caller gates + fills gaps. */
    suspend fun propose(day: Int, observations: List<Observation>): Result {
        val pkg = ContextPackage(
            stateBrief = brief(day, observations),
            inputs = "none",
            creed = CREED,
            outputSchema = schema,
        )
        val completion = model.complete(CompletionRequest(modelId, PromptBuilder.initial(pkg)))
        val prices = when (completion) {
            is CompletionResult.Completed -> parse(completion.text, observations)
            is CompletionResult.Unusable -> emptyMap()
        }
        return Result(prices, completion)
    }

    /**
     * R1b path: price from the model's own self-maintained records (no ground-truth Observation,
     * no harness-computed SIGNAL). Same creed/schema/parse; only the state brief differs — the
     * model must read its own believed cost/price/stock/trend and infer demand itself.
     */
    suspend fun proposeFromBelief(day: Int, belief: R1bBelief, config: dev.supervend.sim.SimConfig): Result {
        val pkg = ContextPackage(
            stateBrief = beliefBrief(day, belief),
            inputs = "none",
            creed = CREED,
            outputSchema = schema,
        )
        val completion = model.complete(CompletionRequest(modelId, PromptBuilder.initial(pkg)))
        val known = config.products.map { it.id.value }.toSet()
        val prices = when (completion) {
            is CompletionResult.Completed -> parseKnown(completion.text, known)
            is CompletionResult.Unusable -> emptyMap()
        }
        return Result(prices, completion)
    }

    private fun beliefBrief(day: Int, belief: R1bBelief): String = buildString {
        appendLine("Day $day. Price each product from YOUR OWN records below — the business keeps no numbers for you.")
        appendLine("Profit = margin per unit × units sold: an overpriced product that stops selling earns nothing.")
        appendLine("product | believed_cost_c | last_price_c | believed_in_machine | your_note")
        for (b in belief.products) {
            appendLine("${b.product} | ${b.unitCostCents} | ${b.lastPriceCents} | ${b.onHand} | ${b.note}")
        }
    }

    private fun parseKnown(text: String, known: Set<String>): Map<ProductId, Cents> {
        val obj = JsonExtract.firstObject(text) ?: return emptyMap()
        val decision = runCatching { json.decodeFromJsonElement(PricingDecision.serializer(), obj) }.getOrNull() ?: return emptyMap()
        return decision.prices
            .filter { it.product in known && it.priceCents > 0 }
            .associate { ProductId(it.product) to Cents(it.priceCents) }
    }

    private fun brief(day: Int, obs: List<Observation>): String = buildString {
        appendLine("Day $day. Profit = margin per unit × units sold. VOLUME MATTERS AS MUCH AS MARGIN:")
        appendLine("a high price that stops the machine selling earns less than a fair price that keeps units moving.")
        appendLine("Watch the demand signals below and react to them.")
        appendLine("product | unit_cost_c | current_price_c | sold_yesterday | in_machine | market_ref_c | signal")
        for (o in obs) {
            val ref = o.product.referencePriceCents.value
            val signal = when {
                // Stock on the machine but nothing (or almost nothing) sold ⇒ far too expensive.
                o.machineUnits > 0 && o.soldYesterday == 0 -> "NOT SELLING — price is far too high; set it to the market reference ($ref) or below"
                o.machineUnits > 2 && o.soldYesterday < o.soldPrior -> "sales FALLING with stock left — lower toward the market reference"
                o.machineUnits <= 2 && o.soldYesterday > 0 -> "selling out — you may raise a little"
                else -> "ok"
            }
            appendLine("${o.product.id.value} | ${o.unitCost.value} | ${o.currentPrice.value} | ${o.soldYesterday} | ${o.machineUnits} | $ref | $signal")
        }
    }

    private fun parse(text: String, obs: List<Observation>): Map<ProductId, Cents> {
        val obj = JsonExtract.firstObject(text) ?: return emptyMap()
        val decision = runCatching { json.decodeFromJsonElement(PricingDecision.serializer(), obj) }.getOrNull() ?: return emptyMap()
        val known = obs.map { it.product.id.value }.toSet()
        return decision.prices
            .filter { it.product in known && it.priceCents > 0 }
            .associate { ProductId(it.product) to Cents(it.priceCents) }
    }

    private companion object {
        val CREED = """
            ROLE: pricing analyst for a vending machine business.
            TASK: price each product to KEEP UNITS SELLING at a healthy margin. Profit needs volume:
            an overpriced product that sits unsold earns nothing.
            RULES:
            1. Price must be above unit cost, but demand comes first — a fair price that keeps the
               machine selling beats a high price that stalls it.
            2. If a product has stock but sold ZERO (or very few) units, it is badly overpriced —
               set its price AT OR BELOW the market reference so it starts moving again.
            3. Only raise a price when the product is selling out (near-empty machine).
            4. The market reference price is your default — start there and deviate only with clear
               evidence from the sales signal.
            5. Include every product exactly once; prices are integer cents.
            OUTPUT: JSON only, matching the schema.
        """.trimIndent()
    }
}

/** The model's pricing output. Its JSON schema (CLAUDE.md #7) is generated from this type. */
@Serializable
data class PricingDecision(val prices: List<PriceLine>)

@Serializable
data class PriceLine(val product: String, val priceCents: Long)
