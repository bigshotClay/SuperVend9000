package dev.supervend.orchestrator

import dev.supervend.model.FailureReport
import dev.supervend.model.ProposedAction
import dev.supervend.model.UnusableReason
import dev.supervend.plan.StepDef

/**
 * The seam through which a step is actually executed — the personas-spec §5 dispatch outcome
 * (resolve role → persona → staffing → build brief → bind mandate tools → execute → produce
 * artifact). The driver stays model-agnostic: script seats and model seats both implement this,
 * so the whole orchestrator is testable with a deterministic runner and no network (CLAUDE.md
 * testing requirements).
 */
fun interface StepRunner {
    fun run(step: StepDef, day: Int, attempt: AttemptContext): AttemptResult
}

/**
 * Retry context, sealed to make the scrubbed-retry constraint (CLAUDE.md #6, PRD R12) unrepresentable
 * to violate: a retry carries only the prior [FailureReport], never the prior artifact. A model-seat
 * runner maps [First] → `PromptBuilder.initial` and [Again] → `PromptBuilder.retry(pkg, priorFailure)`;
 * there is no channel here through which a failed completion could be threaded back into a prompt.
 */
sealed interface AttemptContext {
    val attemptIndex: Int

    data class First(override val attemptIndex: Int = 0) : AttemptContext
    data class Again(override val attemptIndex: Int, val priorFailure: FailureReport) : AttemptContext
}

/** What one attempt yielded. */
sealed interface AttemptResult {
    /** A parseable artifact plus the write intents the step wants to make (each faces the gates). */
    data class Produced(val artifactJson: String, val actions: List<ProposedAction>) : AttemptResult

    /** The model produced nothing usable — truncation, refusal, non-JSON, empty (spec §9). */
    data class Unusable(val reason: UnusableReason) : AttemptResult
}
