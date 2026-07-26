package dev.supervend.model

import kotlinx.serialization.Serializable

/**
 * The closed set of side-effecting tool actions a step may be authorized to take (software spec
 * §4.3). A [Mandate] is an allow-list of these; dispatch binds *only* the listed tools, so an
 * agent literally has no tool for an action outside its mandate (the first line of defense; gates
 * are the second). Model-only seats (classify, research, summarize) have an empty allow-list —
 * they produce an artifact and touch no tool.
 */
@Serializable
enum class ActionType {
    SET_PRICE,
    PLACE_ORDER,
    MOVE_INVENTORY,
    COLLECT_CASH,
    SEND_EMAIL,
    WRITE_CASH_LEDGER,
    WRITE_INVENTORY_LEDGER,
    SUBMIT_TASK,
    PROPOSE_AMENDMENT,
    APPLY_AMENDMENT,
    WRITE_RECOVERY_ANNOTATION,
}

/**
 * Per-mandate numeric bounds. Every field is present (no nullable business fields, CLAUDE.md #3);
 * "unbounded" is a large sentinel, not `null`. Bounds are the mandate's own limits; the gate suite
 * enforces the run-wide policy thresholds separately.
 */
@Serializable
data class Bounds(
    val maxSpendPerOrder: Cents = UNBOUNDED_CENTS,
    val maxSpendPerDay: Cents = UNBOUNDED_CENTS,
    val maxPriceChangeBps: Long = Long.MAX_VALUE,
    val maxOutboundEmails: Int = Int.MAX_VALUE,
) {
    companion object {
        val UNBOUNDED_CENTS = Cents(Long.MAX_VALUE)
        val UNBOUNDED = Bounds()
    }
}

/**
 * Shared by [dev.supervend.model] step defs and personas (personas spec §2 — the mandate is the
 * same type in both). An allow-list plus bounds.
 */
@Serializable
data class Mandate(val allowed: Set<ActionType>, val bounds: Bounds = Bounds.UNBOUNDED) {
    fun allows(action: ActionType): Boolean = action in allowed

    companion object {
        /** A read/emit-only seat: no side-effecting tools. */
        val NONE = Mandate(emptySet())
    }
}
