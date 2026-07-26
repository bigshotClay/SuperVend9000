package dev.supervend.llm

import dev.supervend.model.FailureReport

/**
 * Builds the final prompt text from a [ContextPackage]. The retry path is the load-bearing
 * constraint (CLAUDE.md #6): a failed completion is NEVER re-shown to a model. This is enforced
 * at the type level — [retry] accepts only the original package and a [FailureReport]; there is
 * no parameter through which a prior completion could be threaded. A weak model's garbage cannot
 * contaminate its own retry because the builder's signature makes it unrepresentable.
 */
object PromptBuilder {

    /** First attempt: just the rendered package. */
    fun initial(pkg: ContextPackage): String = pkg.render()

    /**
     * Retry: original instruction package + a structured description of what was wrong. The
     * failed text is absent by construction — [FailureReport] carries the failure classification
     * and the offending field paths, not the model's output.
     */
    fun retry(pkg: ContextPackage, failure: FailureReport): String = buildString {
        append(pkg.render())
        appendLine()
        appendLine()
        appendLine("=== YOUR PREVIOUS ATTEMPT WAS REJECTED ===")
        val failing = failure.checks.filterNot { it.passed }
        if (failing.isNotEmpty()) {
            appendLine("These checks failed:")
            for (c in failing) appendLine("  - ${c.id}: expected ${c.expected}, observed ${c.observed}")
        }
        append("Produce a fresh response that satisfies the OUTPUT SCHEMA above. Output JSON only.")
    }
}
