package dev.supervend.llm

/**
 * The fixed context-package layout (CLAUDE.md #8, personas spec §7): every prompt is assembled
 * in the order **state brief → inputs → creed → output schema**. The creed/instructions sit at
 * the *bottom* on purpose — recency-weighted attention is the derpy-model-friendly position, and
 * qualifying in the exact production layout is the whole point of the qualification battery.
 *
 * The type enforces the order structurally: callers supply the four sections by name and cannot
 * reorder them. There is no personality and no named author in a creed (CLAUDE.md #8).
 */
data class ContextPackage(
    val stateBrief: String,
    val inputs: String,
    val creed: String,
    val outputSchema: String,
) {
    /** Deterministic rendering. Sections are labelled so a weak model can find each block. */
    fun render(): String = buildString {
        appendLine("=== STATE BRIEF ===")
        appendLine(stateBrief.trim())
        appendLine()
        appendLine("=== INPUTS ===")
        appendLine(inputs.trim())
        appendLine()
        appendLine("=== INSTRUCTIONS ===")
        appendLine(creed.trim())
        appendLine()
        appendLine("=== OUTPUT SCHEMA ===")
        append(outputSchema.trim())
    }
}
