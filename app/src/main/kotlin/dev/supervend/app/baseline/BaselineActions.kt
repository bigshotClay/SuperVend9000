package dev.supervend.app.baseline

import dev.supervend.model.Cents
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The naive arm's action vocabulary (software spec §12). This is the *raw tool* surface a single
 * un-scaffolded prompt drives — no plan, no gates, no validation (CLAUDE.md #12: the baseline
 * shares only `sim/` and `llm/` and never gains a harness capability). The agent emits a
 * [BaselineTurn]; its JSON schema is generated from these `@Serializable` types (CLAUDE.md #7).
 *
 * The runner applies each action best-effort and records rejections — a weak model's incoherent
 * plan is *supposed* to fail here. That failure is the experimental signal.
 */
/**
 * One day's output from the bare, self-managing agent. [memory] is the agent's note to its future
 * self — appended verbatim to its single running context file (the ledger it alone maintains). We
 * supply the game and the file; whatever the agent chooses to record (events, supplier reputations,
 * cash tallies, strategy) is the capability it brings. Nothing else persists between days. As the
 * ledger grows the daily context balloons and the provider silently truncates the oldest of it —
 * the model coping with its own overflow is exactly the long-horizon rot this arm is meant to expose.
 */
@Serializable
data class BaselineTurn(
    val memory: String = "",
    val actions: List<BaselineAction>,
)

@Serializable
sealed interface BaselineAction {
    // Short @SerialName discriminators + plain-string ids: the same parseable convention the harness
    // uses for its model-facing types, so the bare model fails on *decisions*, not a serialization
    // gotcha (a fully-qualified discriminator or nested value-class id no reasonable model would emit).
    @Serializable @SerialName("SetPrice")
    data class SetPrice(val product: String, val priceCents: Long) : BaselineAction

    @Serializable @SerialName("Restock")
    data class Restock(val product: String, val units: Int) : BaselineAction

    @Serializable @SerialName("PlaceOrder")
    data class PlaceOrder(
        val supplier: String,
        val product: String,
        val units: Int,
        val unitCostCents: Long,
    ) : BaselineAction

    @Serializable @SerialName("CollectCash")
    data object CollectCash : BaselineAction

    @Serializable @SerialName("DoNothing")
    data object DoNothing : BaselineAction
}

/** Convenience for building a Cents from the wire's plain integer without touching Double. */
internal fun Long.cents(): Cents = Cents(this)
