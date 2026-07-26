package dev.supervend.procure

/**
 * Canned outbound templates (workplan §5 NEG-A/NEG-D, PRD R28). Outbound supplier messages are
 * *script-instantiated*, never model-authored — the model only classifies inbound replies. This is
 * the harness's social-engineering resistance on the send side: there is no path for a model to
 * emit an arbitrary concession or promise, only to select a pre-written template.
 *
 * Templates are deterministic pure functions of their slots; no clock, no randomness.
 */
object Templates {

    fun rfq(product: String, tightBand: Boolean): String = buildString {
        append("Request for quote: product $product. ")
        append(if (tightBand) "Please quote your best firm price; we buy promptly on acceptance."
        else "Please quote unit price and lead time; we are sourcing from multiple vendors.")
    }

    /**
     * Counter template. The leverage variant ("we have a quote at $X…") is only reachable with ≥2
     * live quotes (NEG-D) — the FSM decides [leverage]; this only renders what it was told.
     */
    fun counter(product: String, leverage: Boolean): String =
        if (leverage) "Counter on $product: we hold a competing quote and ask you to beat it."
        else "Counter on $product: your price is above our ceiling; can you improve it?"

    fun clarify(product: String): String =
        "Your reply on $product was unclear to us. Please restate your unit price and terms explicitly."
}
