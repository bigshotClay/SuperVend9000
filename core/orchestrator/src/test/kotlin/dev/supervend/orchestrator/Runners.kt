package dev.supervend.orchestrator

import dev.supervend.model.UnusableReason
import dev.supervend.plan.StepDef

/** A [StepRunner] that fails one targeted step and runs every other step normally (golden). */
internal class FailingRunner(private val target: String, private val mode: Fail) : StepRunner {
    private val golden = GoldenRunner()
    override fun run(step: StepDef, day: Int, attempt: AttemptContext): AttemptResult =
        if (step.id.value == target) {
            when (mode) {
                is Fail.Unusable -> AttemptResult.Unusable(mode.reason)
                is Fail.BadJson -> AttemptResult.Produced(mode.json, emptyList())
            }
        } else {
            golden.run(step, day, attempt)
        }
}

internal sealed interface Fail {
    data class Unusable(val reason: UnusableReason) : Fail
    data class BadJson(val json: String) : Fail
}

/** A [StepRunner] emitting valid artifacts but never any economic action — drives NO_ECONOMIC_ACTION. */
internal class NoEconomyRunner : StepRunner {
    override fun run(step: StepDef, day: Int, attempt: AttemptContext): AttemptResult =
        AttemptResult.Produced(goldenArtifact(step.outputSchema.id), actions = emptyList())
}
