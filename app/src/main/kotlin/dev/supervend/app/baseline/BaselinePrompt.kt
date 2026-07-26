package dev.supervend.app.baseline

import dev.supervend.llm.ContextPackage
import dev.supervend.llm.JsonSchema
import dev.supervend.sim.EnvironmentPort
import dev.supervend.sim.InboundEmail
import dev.supervend.sim.SupplierConfig

/**
 * The bare arm's daily prompt. Unlike the harness — which rebuilds a bounded, structured state brief
 * each day (so it never accumulates and never rots) — the bare arm carries a single running context
 * file that it, and only it, maintains. We supply the game and the file; we do no bookkeeping for it.
 *
 * Layout (via [ContextPackage.render]: state brief → inputs → creed → schema): the running ledger sits
 * at the very top of the state brief, oldest first, so when the day's prompt outgrows the model's
 * context window the provider silently truncates the *distant past* first — the rot. The rules of the
 * game live in the creed near the bottom, so they survive truncation and the agent can always play.
 */
object BaselinePrompt {

    private val schema: String by lazy { JsonSchema.of<BaselineTurn>() }

    fun forDay(
        env: EnvironmentPort,
        inbox: List<InboundEmail>,
        suppliers: List<SupplierConfig>,
        ledger: String,
    ): ContextPackage {
        val machine = env.machineInventory().joinToString("\n") { s ->
            "  ${s.product.value}: ${s.units}/${s.capacity} in machine @ ${s.price.value}c"
        }
        val storage = env.storageInventory().joinToString("\n") { "  ${it.product.value}: ${it.units} in storage" }
        val roster = suppliers.joinToString("\n") { "  ${it.id.value}: ${it.name}" }
        val mail = if (inbox.isEmpty()) "  (no new mail)" else inbox.joinToString("\n") { e ->
            "  from ${e.from.value}: ${e.subject} — ${e.body}"
        }
        val file = ledger.ifBlank { "(empty — this is day one)" }
        return ContextPackage(
            stateBrief = """
                === YOUR CONTEXT FILE — the running record of your business ===
                Every day is appended here automatically: what you saw, what you did, and what happened.
                This is your memory across days. Read it to run the business over the long haul.
                $file

                === TODAY: day ${env.currentDay()} (current state, right now) ===
                Bank balance: ${env.bankBalance().value} cents.
                Machine:
                $machine
                Storage:
                $storage
                Suppliers you can order from (id: name):
                $roster
                Inbox:
                $mail
            """.trimIndent(),
            inputs = "Take today's actions. You may also add a private note (\"memory\") on top of the record.",
            creed = CREED,
            outputSchema = schema,
        )
    }

    // The rules of the game. Accumulation is NOT the agent's choice: the harness appends every day's
    // record to the context file automatically, so the file grows over the whole run whatever the agent
    // does. What we measure is whether the model can keep running the business well as that record grows
    // ever longer — long-horizon coherence, i.e. rot. A "memory" note is offered on top, but the growing
    // context does not depend on the agent choosing to write.
    private val CREED = """
        ROLE: You run one vending machine as a business. Each turn is one day; the run lasts a long
        time. You start with money in the bank and an empty machine. GOAL: grow your net worth (bank
        cash plus the value of your stock) as high as possible over the whole run.

        HOW YOU MAKE MONEY: a product sells ONLY when it is IN THE MACHINE and has a price above 0.
        Stock sitting in storage does not sell. A price of 0 does not sell. So for each product you
        want to sell you must get it ordered, moved into the machine, and priced above what you paid.

        THE ACTIONS you may take each day (put any number in "actions"):
          - PlaceOrder{supplier, product, units, unitCostCents}: prepay a supplier now; the stock
            arrives in STORAGE a few days later.
          - Restock{product, units}: move that many units from storage INTO the machine.
          - SetPrice{product, priceCents}: set the machine price for a product (must be > 0 to sell).
          - CollectCash: sweep the machine's takings into your bank.
          - DoNothing.

        YOU CANNOT SIT IDLE: you pay a fixed fee EVERY day no matter what. An empty or unpriced machine
        earns nothing, so idle days only lose money, and enough days unable to pay the fee shut you
        down for good. Keep the machine stocked, priced, selling, and reorder before you run out.

        YOUR MEMORY: the context file at the top is the running record of the business — every past day
        is written into it for you (what you saw, did, and what resulted), and it only grows. It is all
        you know beyond today's snapshot; use it to stay coherent over hundreds of days. You may also add
        your own note in "memory" (plans, supplier judgments, reminders); it is appended to the record.

        OUTPUT: one JSON BaselineTurn — an optional "memory" note and an "actions" list — matching the
        schema.
    """.trimIndent()
}
